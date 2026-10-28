---
title: Quickstart
description: Run Rahdar and its Postgres from the published image, register your first cluster, and sign in.
---

# Quickstart

Rahdar needs two things: a PostgreSQL database, and network reach to your
brokers' management endpoints. Your Artemis clusters already exist and are
registered through the UI — nothing here starts a broker.

::: warning Alpha
Published images are pre-stable dev builds (`sudoit1/rahdar:dev`; no
`:latest` is published yet). Expect breaking changes between releases.
:::

## With `just`, from a clone

[`just`](https://github.com/casey/just#packages) is the task runner this repo
uses. `just` on its own lists every task, grouped.

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up
```

`just up` runs `just setup` first: on a clean checkout it writes
`deploy/compose/.env` with a random `SECRET_KEY` and `DB_PASSWORD`, and pins
`RAHDAR_IMAGE` to the newest published release tag. Then it starts two Rahdar
replicas behind HAProxy, on one Postgres, and waits until they answer (`just up-single` starts one
replica and no load balancer). Open <http://localhost:8080>.

Run `just setup` again later to move the version pin forward.

## Without `just`

```bash
base=https://raw.githubusercontent.com/sudoitir/rahdar/main/deploy/compose
mkdir -p rahdar/compose rahdar/postgres && cd rahdar/compose   # the compose file mounts ../postgres
curl -sO "$base/compose.prod.yaml"
for f in postgresql.conf 10-pg-stat-statements.sql; do curl -s "${base%/compose}/postgres/$f" -o "../postgres/$f"; done
curl -s "$base/.env.example" -o .env   # then edit it — see Configuration
docker compose -f compose.prod.yaml --env-file .env up -d
```

Postgres reads `postgresql.conf` and the SQL file as its own user, not as you. If your umask makes new
files owner-only (`077`), it stops with `could not open configuration file … Permission denied`: run
`chmod a+r ../postgres/*` and start it again. `just up` does this for you.

Or a bare container against a Postgres you already have:

```bash
docker run -p 8080:8080 \
  -e RAHDAR_DB_URL=jdbc:postgresql://db:5432/rahdar \
  -e RAHDAR_DB_USER=rahdar \
  -e RAHDAR_DB_PASSWORD=... \
  -e RAHDAR_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/rahdar:dev
```

## First login

The username is `admin`. The first run against an empty database generates the
password and prints it **once** to the container log:

```bash
docker compose -f compose.prod.yaml logs rahdar | grep -A4 'Created administrator'
```

You are forced to set a new password on first login. Lose that first password
before changing it and the only recovery is resetting the row directly in
Postgres — there is no password-reset flow yet.

The built-in **ADMIN** role requires two-step verification, so right after you choose a
password Rahdar asks you to set up an authenticator app (scan the QR code, then confirm
with a code) or a passkey, and shows ten single-use recovery codes once. Keep them: they
are the way back in when the device is lost. Every later sign-in asks for the password and
then a code.

A script or `curl` signs in the same way. `POST /api/v1/auth/login` answers
`{"status": "SECOND_FACTOR_REQUIRED", …}` for an account that has a factor, and
`POST /api/v1/auth/second-factor` takes `{"totpCode": "123456"}`. An account with none yet
sets one up with `POST /api/v1/auth/mfa/totp` (it returns the `secret` and an
`otpauthUri`) and confirms it with `POST /api/v1/auth/mfa/totp/confirm` and
`{"code": "123456"}`, which returns the recovery codes. API keys are not asked for a code.

## Register a cluster

Sign in, then **Clusters → Register cluster** (or the button on the empty estate). Registration is a page of
its own, at `/clusters/new`, that you can link to: `/clusters/new?system=kafka` opens it on Apache Kafka.
Choose the broker system first. Rahdar lists the systems it has drivers for, and then asks only for that
system's connection. When **Check connection** has passed on exactly the details entered, give the cluster a
name, a description and an environment, and **Register cluster** is enabled. It stays disabled, with its
reason beside it, until then, and again as soon as a detail changes after a check.

Credentials are encrypted at rest with `RAHDAR_SECRET_KEY` and never shown again.

### Apache Kafka

Enter one or more **bootstrap servers** as `host:port`, separated by commas, spaces or new lines. The port
defaults to 9092, each server appears as its own entry under the field, and a server written with a scheme
(`PLAINTEXT://k1:9092`) or an account is refused with a message saying what to remove. One server is enough:
Rahdar asks it for the other brokers.

Choose the **security protocol**: plaintext, TLS, SASL over plaintext or SASL over TLS. A SASL protocol asks for
the **mechanism**: `PLAIN`, `SCRAM-SHA-256` or `SCRAM-SHA-512` take a username and a password (give both or
neither), and `OAUTHBEARER` takes a token. SASL over plaintext sends the credentials unencrypted, so the form
warns you, and still lets you register. A TLS protocol can name a **TLS bundle** (a Spring SSL bundle) for the
trust store and a client certificate. Host name verification is always on: a broker whose certificate does not
name the address it advertises is refused. The client id is under *Advanced*.

**Check connection** reports the Kafka version (as a lower bound where the brokers cannot say more) and its
mode, every broker with the address it **advertises** and whether Rahdar reached it there, the controller
quorum or why it is unavailable, and what the account may do. A failed check names its cause and what to do:

| Cause | Meaning | Next |
| --- | --- | --- |
| The name does not resolve | DNS has no answer for a server. | Check the spelling and that Rahdar's network resolves it. |
| The connection was refused | Nothing listens on that port. | Check the port and the listener. |
| The connection timed out | A firewall or route drops the traffic. | Check the network path from Rahdar. |
| The certificate is not trusted, or does not match the host name | TLS failed. | Give a TLS bundle that trusts the broker's certificate, and check that it names the advertised address. |
| Authentication failed | The broker rejected the credentials. | Check the mechanism, the username and the secret. |
| The account may not describe the cluster | The credentials were accepted, but the account lacks the permission. | Grant `Describe` on the cluster. This is different from a rejected password. |
| This Kafka version is not supported | The cluster is older than 3.0. | See [Supported broker versions](/guide/supported-versions). |

A bootstrap server may answer while a broker advertises an address Rahdar cannot reach, for example a name
that only resolves inside the cluster's network. The check then names that broker and its advertised address as
not reached: a broker's **advertised listeners** must be reachable from Rahdar. If some brokers were reached
and others were not, you can still register after a warning that the cluster will be shown degraded.

A Kafka cluster is registered once, by the cluster id its brokers report. If the check finds a cluster that is
already registered, even under another name, it links to that cluster and **Register cluster** stays disabled.

### Apache Artemis

One broker's management URL is enough: give Rahdar that URL and the
management account (and a separate Core account, if the brokers use one), and it finds the rest of the
cluster from the broker itself. Type several URLs in the same box, separated by commas, spaces or new lines,
to give more than one seed; each is shown as Rahdar will use it.

Artemis advertises only its Core connectors, never where a broker's management endpoint is. Rahdar
therefore keeps a **management URL pattern** for the cluster (`http://{host}:8161/console/jolokia`: your
URL with the host left free, editable under *Advanced*), puts each other node's host into it, and asks
that address which broker answers. The address is kept only when the answering broker reports the node's
NodeID; otherwise the node is listed as found, with the reason, and you can give it a URL by hand. If your
host name resolves to several addresses, Rahdar tries each one as a seed.

**Check connection** lists every node it found, with its role, NodeID, version, management URL (and whether
it came from your seed, from the pattern or was set by hand), and what the management account and the Core
account each did there: accepted, rejected, unreachable or not tried. A refused Core account does not hide
an accepted management account. When the brokers' running configuration can be read, the check also offers
to **adopt** it as the cluster's first declared revision: the switch is on when the nodes agree, and off,
with the differences listed, when they do not. Nothing is written to a broker either way.

Artemis brokers are registered once. Rahdar knows a broker by the NodeID it reports, not by the URL you
typed, so the same broker reached by its IP address, another port or with a trailing slash is still the
same broker. If any broker the check reaches or discovers already belongs to a registered cluster,
**Check connection** says which cluster, links to it, and **Register cluster** stays disabled. That holds
when only some of the brokers overlap, and when two people register the same brokers at the same moment:
one cluster is created and the other registration is refused the same way. A broker cloned or restored from
another broker's data directory carries the same NodeID, so Rahdar takes it for that broker; give it a
fresh journal and it gets its own.

If an Artemis capability is missing — the Core client is not reachable, or a management
operation is not exposed — Rahdar tells you which one and shows the exact
`broker.xml` snippet that enables it, rather than hiding the feature.

## Behind a reverse proxy

The live UI stream is Server-Sent Events at `GET /api/v1/stream`, and a proxy in
front of Rahdar **must not buffer it**:

- nginx — `proxy_buffering off;` on that path
- Apache — no output buffering on that path
- Traefik — works as-is

Without this the topology graph and the queue grid only update on the 5-second
poll, which looks like a product that is barely alive.

A resource is addressed by its id in one URL segment, so a name that holds a slash
or a percent sign arrives as `%2F` or `%25`
(`/api/v1/clusters/…/resources/artemis.queue/orders%252Feu`). The proxy must pass
those through as they came, or such a resource cannot be reached:

- nginx — `proxy_pass` **without** a URI part (`proxy_pass http://rahdar:8080;`, not
  `http://rahdar:8080/`), which forwards the request line unchanged
- Apache — `AllowEncodedSlashes NoDecode` and `ProxyPass … nocanon`
- HAProxy and Traefik — work as they are

## Try it against throwaway brokers

If you want to see it working before pointing it at anything real, the
repository ships a dev stack that builds Rahdar from source and starts two real
live/backup Artemis pairs, then fills them with traffic that looks like a
working estate — including one address with no consumer so depth climbs, a real
dead-letter backlog, and one stopped node.

```bash
just dev-up                          # Postgres + an Artemis pair + Rahdar
ADMIN_PASSWORD=… NEW_ADMIN_PASSWORD=… just demo   # a second pair, plus realistic traffic
```

The password `just dev-up` prints is one-time: Rahdar refuses everything else until it
is changed. `NEW_ADMIN_PASSWORD` is the one you choose; the seed changes it for you on
the first run. It also sets up an authenticator app for `admin`, which its role requires,
and prints `ADMIN_TOTP_SECRET`. Later runs, and `just shots` and `just demo-gif`, need
`ADMIN_PASSWORD` set to the new password and `ADMIN_TOTP_SECRET` to that secret.

Nothing in that seed writes to `metric_sample` or `subscription_snapshot` directly —
the traffic is produced and consumed through the broker's own CLI, so the charts
are measurements rather than fiction.

## Next

- [Configuration](/guide/configuration) — every variable it reads
- [Broker configuration](/guide/broker-configuration) — declaring what a cluster
  should run, and closing the capability gaps a first launch reports
- [SQL Console](/guide/sql-console) — asking a question that spans queues
- [MCP server](/guide/mcp) — the same capabilities for an assistant
