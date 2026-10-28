# Rahdar — task runner.  Run `just` for the menu.

set shell := ["bash", "-uc"]
set dotenv-load := true

compose_dev  := "docker compose -f deploy/compose/dev/compose.dev.yaml"
compose_demo := "docker compose -f deploy/compose/dev/compose.dev.yaml -f deploy/compose/dev/compose.demo.yaml"
compose_ha   := "docker compose --env-file deploy/compose/.env -f deploy/compose/compose.ha.yaml"
compose_prod := "docker compose --env-file deploy/compose/.env -f deploy/compose/compose.prod.yaml"
mvn          := "./mvnw"
npm          := "npm --prefix web"
# Pinned to match .github/workflows/ci.yml, so a local preview matches the release.
git_cliff    := "git-cliff@2.13.1"

# ── default ──────────────────────────────────────────────────────────────────

# List all recipes, grouped.
default:
    @just --list --unsorted --list-heading $'Rahdar tasks\n'

# The files the stacks bind-mount are read by the containers' own users (postgres, haproxy, artemis), not by
# whoever cloned the repo. A clone made under a strict umask (077) leaves them owner-only, and Postgres and
# HAProxy then refuse to start. Make those files readable, and nothing else: deploy/compose/.env holds secrets.
[private]
mounts:
    @chmod a+r deploy/postgres/postgresql.conf deploy/postgres/*.sql deploy/compose/ha/haproxy.cfg deploy/compose/dev/artemis/*/broker.xml

# ── stack ────────────────────────────────────────────────────────────────────

# Run Rahdar from the published image: two replicas behind HAProxy on one Postgres (runs `just setup`).
# Register your brokers in the UI. Rahdar is on http://localhost:8080.
[group('stack')]
up: mounts setup
    {{compose_ha}} up -d --wait --wait-timeout 300
    @echo "→ http://localhost:8080"
    @{{compose_ha}} logs rahdar-1 rahdar-2 2>/dev/null | grep -A8 'Created administrator' \
        || echo "→ admin account already exists (its password was shown on first boot)"

# The same, with one Rahdar replica and no load balancer (compose.prod.yaml).
[group('stack')]
up-single: mounts setup
    {{compose_prod}} up -d --wait --wait-timeout 300
    @echo "→ http://localhost:8080"
    @{{compose_prod}} logs rahdar 2>/dev/null | grep -A8 'Created administrator' \
        || echo "→ admin account already exists (its password was shown on first boot)"

# Stop the HA stack (keeps the Postgres volume).
[group('stack')]
down:
    {{compose_ha}} down

# Stop the single-replica stack (keeps the Postgres volume).
[group('stack')]
down-single:
    {{compose_prod}} down

# Tail logs of the HA stack (all services, or `just logs rahdar-1`).
[group('stack')]
logs *service:
    {{compose_ha}} logs -f {{service}}

# Show HA stack status.
[group('stack')]
ps:
    {{compose_ha}} ps

# Write deploy/compose/.env (generated secrets on first run), pinned to the latest release tag.
[group('stack')]
setup:
    #!/usr/bin/env bash
    set -euo pipefail
    env=deploy/compose/.env
    if [ ! -f "$env" ]; then
        sed -e "s|^SECRET_KEY=.*|SECRET_KEY=$(openssl rand -base64 32)|" \
            -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" \
            deploy/compose/.env.example > "$env"
        echo "→ wrote $env with generated secrets"
    fi
    # BSD- and GNU-portable: no `sed -i`, no `sort -V`.
    latest=$(git ls-remote --tags --refs origin 2>/dev/null | sed 's;.*refs/tags/;;' \
        | grep -E '^[0-9]{4}\.[0-9]{2}\.[0-9]+$' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 || true)
    if [ -n "$latest" ]; then
        tmp=$(mktemp)
        sed "s|^RAHDAR_IMAGE=.*|RAHDAR_IMAGE=sudoit1/rahdar:$latest|" "$env" > "$tmp"
        mv "$tmp" "$env"
        echo "→ pinned RAHDAR_IMAGE to :$latest"
    else
        echo "→ no release tag found; RAHDAR_IMAGE stays :dev"
    fi

# ── develop ──────────────────────────────────────────────────────────────────

# Full dev stack: Postgres + a real Artemis primary/backup pair + Rahdar, built locally.
[group('develop')]
dev-up: mounts
    {{compose_dev}} up --build -d --wait --wait-timeout 180
    @echo "→ http://localhost:8080   (Artemis console: http://localhost:8161)"
    @{{compose_dev}} logs rahdar 2>/dev/null | grep -A8 'Created administrator' \
        || echo "→ admin account already exists (reset with 'just dev-down' then 'just dev-up')"

# Dev stack plus a second and a third live/backup pair, filled with realistic traffic.
# Needs the admin password `just dev-up` printed: ADMIN_PASSWORD=... just demo
# On a fresh stack that password is one-time; add NEW_ADMIN_PASSWORD=... the first time. The seed also sets up
# the admin's authenticator app (its role requires one) and prints ADMIN_TOTP_SECRET, which later runs need.
[group('develop')]
demo: mounts
    {{compose_demo}} up --build -d --wait --wait-timeout 300
    COMPOSE="{{compose_demo}}" ./scripts/demo-seed.sh

# Capture the README screenshots against whatever is running on :8080.
# Needs ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... (the password and the secret `just demo` printed).
[group('develop')]
shots:
    {{npm}} run shots

# Record the README demo GIFs (product, flow, SQL console, plugin install) from a real session on :8080.
# Same prerequisite as `shots`: ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... just demo-gif
# PLUGIN_JAR=<a built plugin-template jar> adds the plugin-install clip; CLIPS=demo,flow records only those.
[group('develop')]
demo-gif:
    {{npm}} run demo

# Stop the dev stack and delete its volumes.
[group('develop')]
dev-down:
    {{compose_demo}} down -v

# Project rahdar-qa-<name>, Rahdar on 127.0.0.1:<port>, every broker on a random loopback port, Postgres
# unpublished. It seeds long and large content (scripts/qa-seed.sh), raises the session timeouts, signs in once
# per account and saves the Playwright sessions to web/.sweep/auth. `demo=1` adds the demo's second and third
# broker pair and its traffic. The admin's credentials stay in web/.sweep/auth/credentials.env for reruns.
# Start a second, isolated stack for a UI sweep, beside whatever else runs.
[group('develop')]
qa-up name port demo="": mounts
    #!/usr/bin/env bash
    set -euo pipefail
    export QA_PORT={{port}}
    # The QA stack's stuck coordinator (compose.isolated.yaml) lets the seed's transaction hang after a minute.
    export KAFKA_TXN_TIMEOUT_MS=60000
    files=(-f deploy/compose/dev/compose.dev.yaml)
    [ -z "{{demo}}" ] || files+=(-f deploy/compose/dev/compose.demo.yaml)
    files+=(-f deploy/compose/dev/compose.isolated.yaml)
    [ -z "{{demo}}" ] || files+=(-f deploy/compose/dev/compose.isolated-demo.yaml)
    # QA_JAR runs a jar built on the host in the released runtime image, instead of building the image.
    build=--build
    if [ -n "${QA_JAR:-}" ]; then files+=(-f deploy/compose/dev/compose.isolated-jar.yaml); build=; fi
    export COMPOSE="docker compose -p rahdar-qa-{{name}} ${files[*]}"
    export RAHDAR=http://127.0.0.1:{{port}}
    auth=web/.sweep/auth
    creds=$auth/credentials.env
    $COMPOSE up $build -d --wait
    mkdir -p "$auth"
    export ADMIN_USER=admin COOKIES=$(mktemp)
    trap 'rm -f "$COOKIES"' EXIT
    . scripts/lib/signin.sh
    if [ -f "$creds" ]; then
        . "$creds"
    else
        # The one-time password the first boot printed; the admin then chooses its own, and enrols two-step verification.
        logs=$($COMPOSE logs --no-log-prefix rahdar 2>/dev/null || true)
        ADMIN_PASSWORD=$(awk '/^ *password: / { print $2; exit }' <<<"$logs")
        [ -n "$ADMIN_PASSWORD" ] || { echo "no one-time admin password in the logs and no $creds: run just qa-down {{name}} first" >&2; exit 1; }
        NEW_ADMIN_PASSWORD=$(openssl rand -hex 16)
        QA_USER_PASSWORD=$(openssl rand -hex 16)
    fi
    rahdar_sign_in "${NEW_ADMIN_PASSWORD:-}" NEW_ADMIN_PASSWORD
    (umask 077; printf 'ADMIN_PASSWORD=%s\nADMIN_TOTP_SECRET=%s\nQA_USER_PASSWORD=%s\n' \
        "$ADMIN_PASSWORD" "${ADMIN_TOTP_SECRET:-}" "$QA_USER_PASSWORD" > "$creds")
    # Sessions created after this last for the sweep: 24 hours idle, 72 hours absolute (ADR-0145).
    api POST /settings/changes -f -o /dev/null -d '{"changes":[{"key":"security.session.idle-timeout","value":"PT24H"},{"key":"security.session.absolute-lifetime","value":"PT72H"}]}'
    export ADMIN_PASSWORD ADMIN_TOTP_SECRET QA_USER_PASSWORD
    if [ -n "{{demo}}" ]; then TRAFFIC_MINUTES=${TRAFFIC_MINUTES:-3} ./scripts/demo-seed.sh; fi
    ./scripts/qa-seed.sh
    node --experimental-strip-types web/scripts/sweep/auth.ts
    echo "→ $RAHDAR   (sweep: RAHDAR=$RAHDAR npm --prefix web run sweep -- --label <name>)"

# Stop an isolated QA stack, delete its volumes and forget its saved sessions.
[group('develop')]
qa-down name:
    docker compose -p rahdar-qa-{{name}} down -v --remove-orphans
    rm -rf web/.sweep/auth

# Sizes come from load/profiles/<profile>.env and any LOAD_* variable; LOAD_IMAGE or LOAD_JAR picks the Rahdar build
# (else the Dockerfile is built). The stack is project rahdar-load on 127.0.0.1:18090, removed with its volumes.
# Run the load test (ADR-0198, docs/sizing.md) and write load/target/report; REGRESSION marks a figure below its target.
[group('test')]
load profile="reference": mounts
    ./scripts/load.sh {{profile}}

# Run the load test with a tiny profile, to check the test itself works. Not a measurement.
[group('test')]
load-smoke: mounts
    ./scripts/load.sh smoke

# Tail dev stack logs (all services, or `just dev-logs rahdar`).
[group('develop')]
dev-logs *service:
    {{compose_dev}} logs -f {{service}}

# Show dev stack status.
[group('develop')]
dev-ps:
    {{compose_dev}} ps

# Run backend (:8080) and Vite (:5173) together; Ctrl-C stops both.
[group('develop')]
dev:
    #!/usr/bin/env bash
    set -uo pipefail
    trap 'kill 0' EXIT
    {{mvn}} spring-boot:run &
    {{npm}} run dev &
    wait

# Backend only, with live reload.
[group('develop')]
dev-api:
    {{mvn}} spring-boot:run

# Frontend only (expects the API on :8080).
[group('develop')]
dev-web:
    {{npm}} run dev

# ── build ────────────────────────────────────────────────────────────────────

# Full jar with the SPA baked in.
[group('build')]
build:
    {{mvn}} -Pfrontend clean package

# Build the container image locally.
[group('build')]
image tag="rahdar:local":
    docker build -t {{tag}} .

# ── quality ──────────────────────────────────────────────────────────────────

# Everything CI runs: backend verify + frontend build + lint.
[group('quality')]
verify: verify-api verify-web

# Backend: compile, migrate against Testcontainers Postgres, test. Needs Docker.
[group('quality')]
verify-api:
    {{mvn}} verify

# Frontend, as CI runs it: build, lint, format, jsdom and browser tests. Needs Chromium once: `npx playwright install chromium`.
[group('quality')]
verify-web:
    {{npm}} run build
    {{npm}} run check:bundle
    {{npm}} run lint
    {{npm}} run format:check
    {{npm}} run test:coverage
    {{npm}} run test:browser

# Kill, then drain, a replica of the HA reference stack under load (HA_IMAGE_BUILT=1 reuses rahdar:ci). Needs Docker, Node.
[group('quality')]
ha-failover: mounts
    #!/usr/bin/env bash
    set -euo pipefail
    # Requests, event streams and scraping must carry on; web/scripts/ha-failover.ts says what is checked.
    # Throwaway secrets for a stack that lives for one run.
    export DB_PASSWORD="$(openssl rand -hex 16)" SECRET_KEY="$(openssl rand -base64 32)"
    export COMPOSE="docker compose -p rahdar-ha-test --env-file deploy/compose/dev/ha/test.env -f deploy/compose/compose.ha.yaml -f deploy/compose/dev/compose.ha.test.yaml"
    [ -n "${HA_IMAGE_BUILT:-}" ] || docker build -t rahdar:ci .
    trap 'status=$?; [ "$status" = 0 ] || $COMPOSE logs --tail 200 rahdar-1 rahdar-2 lb; $COMPOSE down -v' EXIT
    $COMPOSE up -d --wait --wait-timeout 300
    node --experimental-strip-types web/scripts/ha-failover.ts

# Format Java (Palantir, via Spotless) and the web sources.
[group('quality')]
fmt:
    {{mvn}} spotless:apply
    {{npm}} run format
    {{npm}} run lint -- --fix

# ── api ──────────────────────────────────────────────────────────────────────

# The API-break gate CI runs: web/openapi.json against where the branch left origin/main. Needs Docker.
[group('quality')]
api-diff:
    scripts/api-compat.sh

# ── changelog ────────────────────────────────────────────────────────────────

# Preview the next release's notes, rendered from the commits since the last tag.
[group('changelog')]
changelog:
    npx -y {{git_cliff}} --config cliff.toml --unreleased


# ── database ─────────────────────────────────────────────────────────────────

# Print the SQL the pending changesets would run.
[group('database')]
db-sql:
    {{mvn}} liquibase:updateSQL

# Show applied vs pending changesets.
[group('database')]
db-status:
    {{mvn}} liquibase:status

# Roll back the last N changesets (default 1).
[group('database')]
db-rollback count="1":
    {{mvn}} liquibase:rollback -Dliquibase.rollbackCount={{count}}

# psql into the dev-stack database.
[group('database')]
db-shell:
    {{compose_dev}} exec postgres psql -U rahdar -d rahdar
