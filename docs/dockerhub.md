# Rahdar

**One open-source console for your Apache ActiveMQ® Artemis and Apache Kafka® clusters.**

One instance, every cluster you run, in one estate: health judged on one scale and every
backlog in one table, Artemis queues and Kafka consumer groups side by side. Kafka lag is
shown as the estimate it is, with its per-partition breakdown. Nothing destructive runs
before its preview, and where the approval gate is on, a second person approves it.

- **Apache Artemis** 2.33.0 and later: live/backup topology with HA roles polled from every
  node, cross-node queues and addresses, message flow, safe message operations,
  request-reply tracing and SQL over your messages.
- **Apache Kafka** 3.0 and later, KRaft and ZooKeeper mode: consumer, share and classic
  groups with per-partition lag, previewed offset resets, topic configuration, ACLs,
  hanging transactions, brokers and the controller quorum.
- **RabbitMQ** is coming next; it is not supported yet.

It works against your **existing** brokers. Register an Artemis cluster from one
management URL, or a Kafka cluster from its bootstrap servers; nothing here starts or
reconfigures a broker.

Extend it with **plugins**, installed from the UI: screens, API, assistant tools and
data of their own, most without a restart. When a plugin needs one, Rahdar restarts
itself if the container has a restart policy — the compose file sets
`RAHDAR_PLUGINS_RESTART_SUPERVISED=true` beside `restart: unless-stopped`.

> **⚠️ Alpha.** Under active development, not yet feature-complete. These images are
> **pre-stable dev builds** — the `:dev` tag is the moving pointer and there is no
> `:latest` yet. Expect breaking changes.

**Documentation:** [rahdar.dev](https://rahdar.dev/) ([Kafka guide](https://rahdar.dev/guide/kafka))
&nbsp;·&nbsp; **Source and issues:**
[github.com/sudoitir/rahdar](https://github.com/sudoitir/rahdar)

Every image is signed by the project's release workflow and carries SLSA provenance and a
CycloneDX SBOM: [verify a release](https://rahdar.dev/guide/verify-releases).


## Screenshots

**Every cluster in one estate: Artemis and Kafka side by side, the worst backlog first.**

[![The estate with an Apache Artemis cluster and an Apache Kafka cluster and their health, above one table of subscriptions sorted by backlog, where Kafka lag is marked as an estimate.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/estate-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/estate-light.webp)

**Kafka lag is an estimate, and opens to every partition, slowest first.**

[![The subscriptions table on a Kafka cluster with a group's backlog breakdown open: each partition's committed offset, end offset and lag, slowest first, with the sample's age.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/subscriptions-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/subscriptions-light.webp)

**A consumer group's lag per partition and its members.**

[![A Kafka consumer group's page: its type and state, lag per partition topic by topic, and its members.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/kafka-group-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/kafka-group-light.webp)

**An offset reset is previewed per partition before it is held to confirm.**

[![The reset-offsets preview: each partition's current and new offset and the records skipped, with a press-and-hold button that names the partition count.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/offset-reset-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/offset-reset-light.webp)

**A hanging transaction, found and aborted with its reach stated first.**

[![The transactions page with a hanging transaction flagged, and its abort preview naming the partitions it unblocks and that the producer is fenced.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/transactions-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/transactions-light.webp)

**Registration checks every broker on the address it advertises.**

[![The Kafka registration page after a passed check: the version and mode, every broker with its advertised address reached, and the controller quorum.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/register-kafka-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/register-kafka-light.webp)

**Artemis topology, with HA roles polled from every node.**

[![The Artemis topology: live and backup pairs with their replication state, one node stopped.](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/artemis-topology-light.webp)](https://raw.githubusercontent.com/sudoitir/rahdar/main/docs/img/readme/artemis-topology-light.webp)

---

## Run it

Rahdar and its Postgres. Your Artemis and Kafka clusters already exist and are registered
through the UI — nothing here starts a broker.

```bash
base=https://raw.githubusercontent.com/sudoitir/rahdar/main/deploy/compose
mkdir -p rahdar/compose rahdar/postgres && cd rahdar/compose   # the compose file mounts ../postgres
curl -sO "$base/compose.prod.yaml"
for f in postgresql.conf 10-pg-stat-statements.sql; do curl -s "${base%/compose}/postgres/$f" -o "../postgres/$f"; done
curl -s "$base/.env.example" | sed \
  -e "s|^SECRET_KEY=.*|SECRET_KEY=$(openssl rand -base64 32)|" \
  -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" > .env
docker compose -f compose.prod.yaml --env-file .env up -d
```

The `sed` fills in a fresh secret key and database password; every other setting in
`.env` has a working default. The table below lists what the container reads.

Or a bare container against your own Postgres:

```bash
docker run -p 8080:8080 \
  -e RAHDAR_DB_URL=jdbc:postgresql://db:5432/rahdar \
  -e RAHDAR_DB_USER=rahdar \
  -e RAHDAR_DB_PASSWORD=... \
  -e RAHDAR_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/rahdar:dev
```

| Variable | Required | Notes |
|---|---|---|
| `RAHDAR_DB_URL` | yes | `jdbc:postgresql://host:5432/rahdar` |
| `RAHDAR_DB_USER` / `_DB_PASSWORD` | yes | — |
| `RAHDAR_SECRET_KEY` | with the default `env` key provider | Protects every stored secret. Base64 of **exactly 32 bytes** or the app will not start (`openssl rand -base64 32`), or `1=<b64>,2=<b64>` to hold several versions for key rotation. Other key providers (file, Vault, Kubernetes) and rotation: [configuration guide](https://rahdar.dev/guide/configuration#secrets-and-key-rotation) |
| `RAHDAR_CONFIG_ENCRYPT_KEY` | no | Decrypts `{cipher}` values in `rahdar_config_property`. A **different** key from `RAHDAR_SECRET_KEY` — do not reuse it |
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=50` |

**First login.** Username `admin`. The first run against an empty database
generates the password and prints it **once** to the container log:

```bash
docker compose -f compose.prod.yaml logs rahdar | grep -A4 'Created administrator'
```

At the first sign-in you choose your own password and set up two-step verification,
which the admin role requires. An administrator who has lost both their device and their
recovery codes is recovered with the break-glass `RAHDAR_IDENTITY_LOCAL_RECOVER`
([configuration guide](https://rahdar.dev/guide/configuration)).

**Reverse proxy.** The live UI stream is SSE at `GET /api/v1/stream`; a proxy in
front of Rahdar **must not buffer it** (nginx `proxy_buffering off;`, Apache no
output buffering on that path; Traefik works as-is). Without this the topology
graph and queue grid only update on the 5-second poll.

## Tags

| Tag | What it is |
|---|---|
| `:dev` | the newest build; moves with every release |
| `:YYYY.MM` | the newest build of that calendar month; moves within it |
| `:YYYY.MM.N` | one exact release; **immutable**, never republished |

`linux/amd64` and `linux/arm64`. Versioning is CalVer and the number carries no
compatibility promise — a release's breaking changes are listed at the top of its
[release notes](https://github.com/sudoitir/rahdar/releases).

## Licence

Apache-2.0.

Apache, Apache Kafka, Kafka, Apache ActiveMQ, ActiveMQ and Artemis are trademarks of
the Apache Software Foundation; RabbitMQ is a trademark of its owner. Rahdar is not
affiliated with or endorsed by them; the names describe what the software works with.
