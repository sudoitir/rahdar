# syntax=docker/dockerfile:1
# Rahdar — single image: React SPA baked into the Spring Boot jar.
#
# The SPA and the jar are the same bytes on every architecture, so both build stages run on the
# runner's own platform ($BUILDPLATFORM); only the runtime stage is per-target. Building them
# under QEMU for arm64 made the release image take ten times longer for no different output.

# ── 1. Build the frontend ────────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── 2. Build the jar (frontend copied in via the `frontend` profile is skipped;
#      we pass the already-built dist straight through instead) ───────────────
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS app
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -e -B dependency:go-offline
COPY src/ ./src/
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN mvn -q -B clean package -DskipTests

# ── 3. Runtime (Ubuntu 26.04 LTS "resolute") ─────────────────────────────────
FROM eclipse-temurin:25-jre-resolute@sha256:41d9263c0d5b2a74d89af6e29e73b04298bc65e3cb705d03e9e2271ef49fef84 AS runtime
# The distribution's security updates are applied at build time: a fix often reaches the Ubuntu
# archive days before a new base image does, and the image scan fails on a fixable high finding.
# Ubuntu's base also ships Pebble, a service manager outside any package, so no upgrade patches it;
# Rahdar never runs it, so it is removed with its Go runtime and that runtime's vulnerabilities.
RUN groupadd -r rahdar && useradd -r -g rahdar rahdar \
    && apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /usr/bin/pebble /var/lib/pebble
WORKDIR /app
COPY --from=app /src/target/rahdar-exec.jar app.jar
USER rahdar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
