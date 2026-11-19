# syntax=docker/dockerfile:1
# Rahdar: one image, the React SPA baked into the Spring Boot jar.
#
# Both build stages run on the runner's own platform. Their output is identical on every
# architecture, and building them under QEMU for arm64 was ten times slower for no different bytes.

# ── 1. Frontend ──────────────────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim@sha256:c86ecead9e55a5210ac1727de38a4ca840bf03dcaf34dc9749addbf383b1920c AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── 2. Jar ───────────────────────────────────────────────────────────────────
# The `frontend` profile is skipped: the stage above already built dist.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS app
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -e -B dependency:go-offline
COPY src/ ./src/
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN mvn -q -B clean package -DskipTests

# ── 3. Runtime (Ubuntu 26.04 LTS "resolute") ─────────────────────────────────
# Distribution security updates are applied here: a fix reaches the archive days before a new
# base image carries it, and the image scan fails on a fixable high finding. The base is Temurin
# 25.0.5, which carries the OpenJDK fixes the previous pin did not.
# - apt is upgraded first with retries off, so a cached layer cannot keep a vulnerable apt.
# - resolute-security is enabled because the release pocket lacks some fixes (libbrotli1).
# - Pebble ships outside any package and Rahdar never runs it, so it goes with its Go runtime.
FROM eclipse-temurin:25-jre-resolute@sha256:480ef64e4540d45b0301e8afb7873bd3fa61ce93c5f66273eb569420718f38e2 AS runtime
RUN groupadd -r rahdar && useradd -r -g rahdar rahdar \
    && printf '%s\n' \
        'deb http://archive.ubuntu.com/ubuntu resolute-updates main universe' \
        'deb http://security.ubuntu.com/ubuntu resolute-security main universe' \
        > /etc/apt/sources.list.d/rahdar-security.list \
    && apt-get update \
    && apt-get install -y --only-upgrade --no-install-recommends -o Acquire::Retries=0 apt libapt-pkg7.0 \
    && apt-get upgrade -y --no-install-recommends -o Acquire::Retries=0 \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /usr/bin/pebble /var/lib/pebble
WORKDIR /app
COPY --from=app /src/target/rahdar-exec.jar app.jar
USER rahdar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
