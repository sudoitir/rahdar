# Rahdar — project instructions

Cluster-wide management and observability for Apache ActiveMQ Artemis. One instance,
many clusters: topology, cross-node resource views, safe message operations, and
first-class request-reply tracing. Open source, Apache-2.0.

The full design rationale is in `docs/architecture.md` and the ADRs. Planned work is
queued as numbered changes in `openspec/changes/` (`NN-<slug>`), each run in its own
session, in number order.

## Layout: kernel, platform, features (ADR-0069)

Rahdar is a modular monolith with the same module ids on both sides:

- **Backend** `io.github.sudoitir.rahdar`: `kernel.*` (core, plugin, security,
  audit, settings, jobs, stream) defines contracts and never imports a platform or
  feature type; `platform.*` (broker, clusters, scrape, mcp) is what features build on;
  `feature.*` is one module per capability. `app/RahdarFeatures` is the one list of
  modules. Boundaries are checked by `ModularityTest` (Spring Modulith) and
  `BoundaryRulesTest` (ArchUnit); `docs/modules/` is generated from the same model.
- **Frontend** `web/src`: `kernel/` (contract, manifest, slots, nav groups, routing
  roots, api, stream, auth, shell), `ui/` (shared presentational components),
  `features/<id>/`, `app/` (the composition root: `features.ts`, `router.ts`).
  eslint-plugin-boundaries enforces the edges (ADR-0074).

**Where a new feature goes.** A backend module `feature/<id>/` with `package-info.java`
(`@ApplicationModule(allowedDependencies)`), an `<Id>Module` holding its
`FeatureDescriptor`, an `<Id>Feature` carrying `@FeatureModule("<id>")`, its tables in
`db/changelog/feature/<id>/` (included from the master changelog), and an
`@ApplicationModuleTest`; registered in `app/RahdarFeatures`. A frontend folder
`web/src/features/<id>/` with `feature.ts` (`defineFeature`: routes, nav, palette,
topics, slots) and `api.ts`, listed in `web/src/app/features.ts`; its id added to
`FEATURE_IDS`. Cross-feature needs are a slot or a named public export on an allowed
edge, never a reach into another module's internals.

**Runtime plugins are a different thing** (ADR-0099..0104): a third party's jar, uploaded
in Admin → Plugins and run in its own child context, class loader, schema and pool.
Their host is `kernel.plugin.internal.{host,runtime,validation,store}`, their admin API
`feature/plugins`, their UI loader `web/src/kernel/plugins/`, the SDK surface
`web/src/sdk/` (published as `@rahdar/plugin-sdk` from `web/packages/plugin-sdk`),
and the author's starting point `examples/plugin-template/`. Only `@PluginApi` types
are the supported Java API. A class that JVM-wide caches may key by a plugin type gets
an eviction in `PluginClassloaderCaches`; `PluginUnloadIntegrationTest` fails with a heap dump when
one is missing.

## Stack (fixed — changing any of these needs an ADR)

- **Backend**: Java 25, Spring Boot 4.1.1, Maven. Package root
  `io.github.sudoitir.rahdar`. groupId `io.github.sudoitir`.
- **Persistence**: JPA (Hibernate) entities mapped to the Liquibase-owned schema,
  `spring.jpa.hibernate.ddl-auto=validate` (ADR-0011). **Lombok** for entity /
  component boilerplate and **MapStruct** for entity↔domain↔DTO mapping
  (ADR-0014) — needs the Lombok IDE plugin.
- **Database**: PostgreSQL. Schema via **Liquibase** — XML master changelog
  (`src/main/resources/db/changelog/db.changelog-master.xml`) including one changelog
  per module, with one SQL changeset file per concern under that module's `changes/`. Boot runs migrations on startup; the
  `liquibase-maven-plugin` (`just db-*`) is for humans. Postgres owns config,
  users, and audit; broker-derived tables (`subscription_snapshot`, `metric_sample`) are
  a disposable cache.
- **Java formatting**: Palantir Java Format via Spotless, applied at
  `process-sources` (every `mvn compile`) and checked at `verify`. Never
  hand-fight it; run `just fmt`.
- **Web formatting**: Prettier (`web/.prettierrc.json`, 120 columns like the Java),
  checked by CI's frontend job (`npm run format:check`); `just fmt` applies it
  (ADR-0131).
- **Frontend**: React 19 + Vite + TypeScript + **Mantine 9** (no Tailwind, no shadcn).
  TanStack Router/Query/Table, `@mantine/charts`, `@xyflow/react` for topology.
  `typescript` is pinned to `5.9.3` for tool compatibility; bump to 7.x once the
  ESLint/Vite/TanStack toolchain all declare support.
- **Broker access**: the OpenBroker SDK (`io.github.sudoitir:openbroker-*`, released versions
  only), one `ClusterConnection` per cluster through `platform.broker`'s gateway (ADR-0203). No
  broker client type anywhere in Rahdar; only `system.artemis` knows the Artemis driver, whose
  channels are Jolokia for management and Core for messages and notifications. No JMX.
- **Realtime**: SSE only. `GET /api/v1/stream`. Commands are ordinary POSTs.
- **Packaging**: one container image, `compose.yaml` first. Helm in v1.0.

## Non-negotiables

1. **Broker-friendly by construction.** Batched Jolokia reads (one POST per node,
   not per queue), tiered polling, a per-node rate limiter. Rahdar must never be
   the reason a broker falls over.
2. **Safe by default.** Every destructive operation takes `?dryRun=true` and returns
   the affected count without acting. Purge/delete are confirmed in the UI by pressing and holding a button (ADR-0186).
3. **Audit everything.** Every mutating call commits an `audit_event` before the broker
   call and updates it with the outcome, each in its own transaction (ADR-0078).
4. **Never trust config for HA state.** Poll `Active` on every node to learn who is
   live. Two live in a pair = split-brain = critical alert.
5. **Honest capability gating.** When a feature is unavailable because the connection
   lacks a capability, the UI says so and shows the exact `broker.xml` snippet.
   No silently missing buttons.
6. **Tokens, three layers.** `web/src/theme.ts` (primitive) → `web/src/theme.css`
   semantic vars (`--as-*`) → components. No raw hex or colour literals in components.
7. **Schema conventions.** Columns ordered to minimise Postgres row padding:
   8-byte-aligned types (timestamptz, bigint, double precision) first, then 4-byte
   (integer, text, jsonb, bytea, inet), then uuid and boolean last — the PK is not
   first, on purpose. High-churn tables carry per-table `autovacuum` / `fillfactor`
   storage parameters in the same changeset. Server-level tuning lives in
   `deploy/postgres/postgresql.conf`. Every table has a primary key, every uuid key
   defaults to `uuidv7()` (bearer handles excepted), every foreign key leads an index, and
   `SchemaLintTest` checks the migrated catalog (ADR-0192). Until the first stable release
   each module keeps one `changes/0001-baseline.sql` and a schema change edits it; operators
   recreate the database (ADR-0192, amending ADR-0072). After the stable release a released
   changeset is never edited: add a new one. Each module owns its tables and
   its changelog under `db/changelog/<layer>/<id>/`; a cross-module foreign key follows
   an allowed dependency.
8. **Logical CSS properties** (`inline-start`, `block-end`), never `left`/`right`.
9. **State has one owner.** Server state via TanStack Query; navigable state in the
   URL; local state stays local. No global store for what a URL can hold.
10. **The product name lives in two files.** `Branding.java` and `web/src/branding.ts`.
   Never hard-code "Rahdar" anywhere else. See ADR-0001 and ADR-0201 for why.

## Process

- **ADRs are binding.** `docs/adr/`, English, Nygard style (`docs/adr/000-template.md`).
  Adding a technology, changing a pattern, or departing from a convention needs a new
  sequential ADR before or with the change. Never edit an accepted ADR's decision —
  supersede it.
- **Library/API facts come from `ctx7`, not memory.** Any question about a library,
  framework, SDK, or CLI — including ones you think you know — goes through the `ctx7`
  CLI (`npx ctx7@latest library "<name>" "<query>"` then `docs "<id>" "<query>"`).
  Max 3 calls per question. Training data on versions and signatures is stale.
- **Read before you write.** Open the token file and the nearest existing screen
  before adding UI. Grep for an existing helper before writing one.
- **Smallest correct change.** No drive-by refactors. Fix the thing, file the rest.
- **Portable shell.** Scripts and recipes run on bash 3.2 (macOS) and Linux bash, with BSD and GNU tools:
  no `mapfile`, `${v,,}`, `declare -A`, `timeout`, `sed -i` without a suffix, `grep -P` or `date -d`.
  `scripts/lib/signin.sh` also sources cleanly from zsh, so it avoids `path`, `status` and `options`.
- **Don't fake the receipt.** If you didn't run it, say so. If a test fails, show the
  output. A confident wrong answer costs more than an honest "let me check".

## Commands

```bash
just                 # menu of all tasks, grouped
just up / just down  # HA stack from the published image: 2 Rahdar replicas + HAProxy + Postgres (runs `just setup`: .env + version pin)
just up-single / down-single  # the one-replica compose.prod.yaml stack
just dev-up / dev-down  # full dev stack: postgres + artemis primary/backup + rahdar, built locally
just dev             # backend :8080 + vite :5173 (proxied), together
just verify          # everything CI runs: verify-api + verify-web
just fmt             # Palantir (Spotless) + Prettier + eslint --fix
just db-status / db-sql / db-rollback [n] / db-shell

./mvnw verify                  # backend: format-check, Liquibase vs Testcontainers PG, tests
./mvnw -Pfrontend package      # full jar with the SPA baked in
```

`./mvnw verify` needs Docker (Testcontainers). The frontend build is behind the
`frontend` Maven profile so day-to-day `./mvnw test` stays fast. Compose files
live in `deploy/compose/`: `compose.prod.yaml` and `compose.ha.yaml` for operators, everything for
development, demos and tests in `deploy/compose/dev/`.

Pull requests carry the verification: parallel, path-filtered jobs behind the one required
`ci-ok` check, on a branch up to date with `main`; `gh pr merge <n> --merge --auto` merges a ready PR, and
the `pr-auto-update` workflow updates the waiting PRs from `main` one at a time, oldest auto-merge first (ADR-0170). `main` does not re-test. Every push to `main`
that changes a build input cuts a CalVer release to Docker Hub (image, git tag, GitHub
pre-release), and Central and npm get it only when their inputs changed; docs-, site- and
CI-only pushes release nothing (ADR-0088, ADR-0126). See `.claude/rules/10-release.md` for the versioning. There is no
`CHANGELOG.md`: each release's notes are its GitHub release body (tag only, nothing is
committed; ADR-0129), and the commit message is the release note — `.claude/rules/05-commits.md` (Conventional Commits,
ADR-0051). `just changelog` previews the pending release.

## Trademark

"Artemis" is an Apache Software Foundation mark. The product is Rahdar (ADR-0201, which
superseded ADR-0001), so "Artemis" names only the broker, as nominative use. Keep the
disclaimer (`NOTICE`, README, About dialog) intact.
