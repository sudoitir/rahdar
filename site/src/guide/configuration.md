---
title: Configuration
description: The environment variables Rahdar reads, which are required, and the two configuration planes it keeps.
---

# Configuration

## Environment

| Variable | Required | Notes |
|---|---|---|
| `RAHDAR_DB_URL` | yes | `jdbc:postgresql://host:5432/rahdar` |
| `RAHDAR_DB_USER` / `_DB_PASSWORD` | yes | — |
| `RAHDAR_DB_POOL_SIZE` | no | Connections each replica keeps open, 16 by default. Every replica together, plus its listening connection and the plugins' pools, must stay under the server's `max_connections`. A request that waits more than 5 s for a connection fails; a query that runs past 30 s is cancelled, except the SQL console, exports and maintenance, which raise it for their own transaction |
| `RAHDAR_SECRET_KEY` | with the `env` provider | The key that protects every stored secret. Base64 of **exactly 32 bytes**, or the application will not start: `openssl rand -base64 32`. To hold several versions, see [Secrets and key rotation](#secrets-and-key-rotation) |
| `RAHDAR_CAPTURE_BROKER_ROLE` | for capture | The broker role Rahdar's own broker user holds. Capture queues are restricted to it, and message capture is refused until it is set. Use a dedicated role, not the default `amq` |
| `RAHDAR_CONFIG_ENCRYPT_KEY` | no | Decrypts `{cipher}` values stored in `rahdar_config_property`. A **different** key from `RAHDAR_SECRET_KEY` — do not reuse it |
| `RAHDAR_PUBLIC_URL` | for passkeys | The address people open Rahdar at, e.g. `https://rahdar.example.com` (property `rahdar.public-url`; it was `rahdar.alerting.public-url`). Alert notifications link back to the cluster's alerts when it is set, see [Alert delivery](./alert-delivery). It is also what **passkeys** are tied to: with it unset, users can still use an authenticator app but not a passkey, and the account page says so. Passkeys belong to its host, so changing the host (not just the port or path) strands every passkey enrolled under the old one; recovery codes and authenticator apps keep working. Startup fails if it is set to something that is not an `http` or `https` address |
| `RAHDAR_IDENTITY_LOCAL_RECOVER` | no | **Break-glass.** A local username. At startup Rahdar unlocks that account, removes its authenticator app, passkeys, recovery codes and trusted devices, revokes its API tokens, requires a password change at the next sign-in, ends its sessions, and writes the action to the audit trail and a warning to the log. Use it when the only administrator has lost both their device and their recovery codes. **Remove it after the restart**, or the next restart recovers the account again; an unknown username is logged as an error and changes nothing. The administrator signs in with the current password, sets a new one and enrols a new factor |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | behind a proxy outside the private ranges | A regular expression matching the addresses of your reverse proxies. Only these may set the client address through `X-Forwarded-For`; the sign-in limits and the audit trail use it. The default trusts loopback, `10/8`, `172.16/12`, `192.168/16` and `fc00::/7`. Never leave it empty: that trusts every client |
| `JAVA_OPTS` | no | Defaults to `-XX:MaxRAMPercentage=50` |

## Secrets and key rotation

Every stored secret is sealed under its own random data key, and that data key is wrapped by a
versioned **key-encryption key** (KEK). The secrets are broker and bridge credentials,
notification channel secrets, plugin secrets and the sealed message originals of governance.
A key provider supplies the KEKs. It is chosen once, at startup, and there is no fallback: if the
provider cannot deliver a valid key, Rahdar does not start and names the provider.

### Providers

`rahdar.secrets.provider` selects one. In an environment variable a dash becomes an
underscore and a dot becomes an underscore, so `rahdar.secrets.vault.uri` is
`RAHDAR_SECRETS_VAULT_URI`.

| Provider | Where the keys live | Settings (default in brackets) |
|---|---|---|
| `env` (default) | `RAHDAR_SECRET_KEY`: a bare base64 key (version 1), or `1=<b64>,2=<b64>` for several versions | none |
| `file` | A directory of files `kek-<n>` (base64 of 32 bytes; `n` is the version). A mounted Kubernetes Secret volume fits | `rahdar.secrets.file.directory` |
| `vault` | HashiCorp Vault KV version 2. The field `kek` of each live KV version is a key version | `rahdar.secrets.vault.uri`, `.mount` [`secret`], `.path`, `.oidc-path` [the same as `.path`], `.authentication` [`token`; or `approle`, `kubernetes`], `.token`, `.role-id`, `.secret-id`, `.kubernetes-role`, `.kubernetes-token-path` [`/var/run/secrets/kubernetes.io/serviceaccount/token`] |
| `kubernetes` | One Kubernetes Secret with keys `kek-<n>`, read through the API server with the pod's service account | `rahdar.secrets.kubernetes.secret-name`, `.namespace` [the pod's own], `.api-url` [`https://kubernetes.default.svc`], `.token-path` [`/var/run/secrets/kubernetes.io/serviceaccount/token`], `.ca-path` [`/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`] |

The service account of the `kubernetes` provider needs `get` on that one Secret.

Every key must be base64 of exactly 32 bytes. The version of a key is the number in its name
(`kek-2`, or `2=` in the environment variable, or the KV version in Vault).

### Vault keeps few versions by default

KV version 2 keeps only **10 versions** of a secret and deletes the oldest when an eleventh is
written, and every write counts, even one that changes nothing but `oidc-client-secret`. Losing a
version that stored secrets are still wrapped under makes them unreadable. So:

- Set `max_versions` on the path high (for example `vault kv metadata put -max-versions=0 secret/rahdar`,
  where `0` means unlimited).
- Keep `oidc-client-secret` on a separate path with `rahdar.secrets.vault.oidc-path`, so
  editing it never adds a version to the key path.
- Never delete or destroy a version that is still in use. **Settings → Encryption keys** lists a version
  that stored secrets use but the provider lacks as missing, and the log warns with its version and
  count.

### The OIDC client secret

The provider also supplies the OIDC client secret, under the name `oidc-client-secret`: the file or
Kubernetes Secret key of that name, the field `oidc-client-secret` of the latest version at `.oidc-path` in Vault, or
`RAHDAR_OIDC_CLIENT_SECRET` with the `env` provider. Configure it there and nowhere else:
a client secret set in Rahdar's own OIDC settings is an error when the provider is not `env`.

### Rotating the key

Rotation is online. It moves every secret to a new key version without downtime and without ever
reading a secret in the clear.

1. Add a new version to the provider (`kek-2`, `1=…,2=…`, or a new KV version). **Keep the old one.**
2. In **Settings → Encryption keys**, choose **Rotate key**. It needs the `settings:write` permission and
   a recent sign-in. The same is `POST /api/v1/settings/secrets/rotations`.
3. New secrets use the new version at once. A background job re-wraps the rest in batches. Watch
   the rotation until it reaches **Succeeded**: it waits at least 30 seconds after the start so
   that every replica has learned the new version, and it is safe to restart Rahdar meanwhile.
4. Remove the old key from the provider only after that. **Security** counts the rows under each
   version, so you can see when an old version is unused.

If a rotation fails (its error names the store and row), keep the old keys in the provider, fix the
cause and start it again: it re-wraps only what is still under an older version. Removing an old key
too early makes the secrets under it unreadable, and the only way back is to re-enter them.

### Changing provider

1. Put the current key into the new provider under a version number **at least as high** as the current one.
2. Change `rahdar.secrets.provider` (and its settings) and restart. Rahdar starts only if the new provider holds the
   current version.
3. Add a newer version to the new provider and rotate to it, as above. Only then retire the old
   provider.

### Upgrading from an earlier version

Secrets stored by earlier versions are dropped on the first start, because the storage format
changed. Re-enter broker credentials (clusters and bridges), notification channel secrets and
plugin secrets. Governance originals sealed earlier are no longer available. Nothing else is lost.

### Redaction

Passwords, tokens, API keys, bearer values, URL user-info and private keys are masked as
`[redacted]` in logs, audit parameters, error responses and the broker.xml export, even when a
secret arrives by a route Rahdar did not expect. Rahdar logs to the console only.

## Features

Every optional feature can be turned off at startup, and is on unless you do:

```bash
RAHDAR_FEATURES_SQL_ENABLED=false        # rahdar.features.sql.enabled
```

The ids are `queues`, `resources`, `messages`, `routing`, `metrics`, `alerting`,
`events`, `rr`, `sql`, `brokerconfig`, `triage`, `mcp`, `apitokens`, `identity-local`
and `identity-oidc`; in an environment variable a dash becomes an underscore. A disabled
feature has no screens, no API and no assistant tools: its navigation entry is gone, its
address explains that it is off and names this property, and its API answers
`404 feature-disabled`. Its tables are still migrated, so turning it back on is a
restart. The kernel and platform (clusters, brokers, scrape, security, audit, settings,
stream) cannot be turned off, and a feature another one `requires` cannot be turned off
while that one is on — Rahdar refuses to start and says which.

`GET /api/v1/manifest` lists what this installation has enabled.

## The two planes

Configuration is deliberately split in two, and the split is about who changes a
value and when.

**The operator plane** — `rahdar_setting` — holds what an operator tunes while
Rahdar is running: poll cadences, retention windows, the bulk-operation cap, the
SQL Console's cost ceiling. Changes take effect live, are audited, and need no
restart. Every settings-tunable schedule is a re-reading trigger, not a fixed
annotation, which is why a cadence change is immediate.

**The deploy plane** — Spring Cloud bootstrap properties — holds what belongs to
the deployment: the datasource, the keys, the OIDC issuer. Values here can be
stored as `{cipher}` and decrypted with `RAHDAR_CONFIG_ENCRYPT_KEY`.
There is no configuration server.

See [ADR-0047](/reference/adr/0047-two-configuration-planes) for why, and
[ADR-0048](/reference/adr/0048-settings-driven-dynamic-schedules) for how a
schedule picks up a changed setting.

## Sign-in and sessions

Under **Settings → Sessions** and **Settings → Password login**, without a restart:

| Setting | Key | Default | Is |
|---|---|---|---|
| Idle timeout | `security.session.idle-timeout` | `30m` | How long a session may go without the user doing anything. Only requests that change something, and requests the console makes within a minute of a click or key press, count. Polling and the live stream do not, so a tab left open signs out. A script that must stay signed in sends `X-Rahdar-Activity: 1` |
| Absolute session lifetime | `security.session.absolute-lifetime` | `12h` | How long after signing in a session ends, however active it is |
| Password minimum length | `identity-local.password.min-length` | `12` | The fewest characters a new local password may have |
| Breached-password check | `identity-local.password.breach-lookup` | off | A switch. When on, a new local password is also checked against the online breached-password service: only the first five characters of its SHA-1 leave Rahdar, and a failed lookup lets the password through. The offline list of the 100,000 most common passwords is always used |
| Trusted device lifetime | `identity-local.mfa.trusted-device-lifetime` | `30d` | After giving a second factor at sign-in, a user may trust that browser: for this long a password alone signs in from it. `0` turns trusted devices off: the option is not offered and existing ones are ignored. A trusted device still needs the second factor for actions that ask users to confirm it is them again, and it signs its owner in even while the account is locked by failures from elsewhere. Changing a password, resetting an account's factors and disabling it revoke its trusted devices |

A duration is written `30m`, `12h`, `30d` or in ISO-8601 (`PT30M`). A user sees where they are signed
in, and ends any of those sessions, under **Account → Sessions**; an administrator does the same
for any user from **Administration → Users → Sessions**. See
[ADR-0145](/reference/adr/0145-session-lifetimes-and-session-management).

## Database

PostgreSQL, with the schema owned by Liquibase and migrated on startup. Rahdar
validates the mapping against the migrated schema at boot rather than generating
DDL, so a schema that has drifted fails loudly instead of quietly.

Rahdar also runs on a managed or highly available PostgreSQL 18 that it does not administer, in a schema
of your choosing (`currentSchema` in the JDBC URL), as a role that is not a superuser. At boot it checks the
version, the privileges and that the connection keeps its session (so a pooler must be in session mode), and
refuses to start with the fix named when something is missing. The guide
[Running Rahdar on an external PostgreSQL](/reference/external-database) covers privileges, TLS, poolers,
failover URLs (`targetServerType=primary`) and the connection budget.

An optional read replica serves heavy history reads and exports while it is close enough behind the primary,
and the primary serves them otherwise:

| Property | Default | Notes |
|---|---|---|
| `rahdar.database.read.url` | none | The standby's JDBC URL. Unset means every read uses the primary |
| `rahdar.database.read.username` / `.password` | the primary's | The standby's credentials |
| `rahdar.database.read.pool-size` | `8` | Connections each Rahdar replica keeps to the standby |
| `rahdar.database.read.max-lag` | `30s` | The most the standby may lag and still serve a read. A response served from it says it may lag |

Postgres owns configuration, users and the audit trail. The broker-derived
tables — `subscription_snapshot`, `metric_sample` — are a **disposable cache**: losing
them costs you history, never truth.

## Monitoring

`/actuator/prometheus` exports Rahdar's own health and its load on each broker, and OTLP export
is switched on with the standard `OTEL_*` variables. The metrics, the Grafana dashboard and the
alert rules are in [Observability](/guide/observability).

## Broker connections

Registered through the UI, not the environment. A connection stores the seed management
endpoints, the management URL pattern that gives every other node its own, and the management and Core
credentials, encrypted at rest. Edit all of it under **Settings → Connection**: **Check connection** runs
the same per-node check as registering and saves nothing, and **Save connection** is offered once the
check of exactly those values passed. Changing an account asks you to type the cluster's name, and a
password left empty keeps the stored one. Jolokia HTTP is the
primary transport; the Artemis Core client is a second channel used for
notifications and faithful message I/O, and features that need it are gated on
it being reachable — visibly, with the `broker.xml` that would enable it.

See [ADR-0002](/reference/adr/0002-broker-transport-and-capability-model).
