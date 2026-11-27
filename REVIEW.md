# Review instructions

Rahdar is an operations console for Apache Kafka, RabbitMQ and Artemis. The operator
reading the screen is tired, on call, and may be about to do something irreversible.
That is the lens: a finding matters when it would mislead that person, corrupt data,
or leak something.

`CLAUDE.md`, `.claude/rules/` and `docs/adr/` are binding here. A change that
contradicts one is a finding; these instructions only recalibrate how it is reported.

## What Important means here

Reserve 🔴 Important for findings that would:

- break or silently corrupt broker state, or run a destructive operation without
  `?dryRun=true` and without an `audit_event` committed before the broker call
- put a broker client type outside `platform.broker` / `system.<id>`, or make Rahdar
  behave like anything but a polite client (per-queue Jolokia reads, unbounded fan-out)
- break a module boundary: the kernel importing a platform or feature type, a feature
  reaching into another's internals, a new eslint edge added to justify it
- make the UI state something untrue: a hidden control that is merely unavailable, an
  absent estimate read as zero, a filtered-empty view shown as empty, an unreachable
  node's absence presented as a fact
- leak a credential, a message body, or a payload into a log, an error, or an audit field
- depart from an accepted ADR without a new ADR superseding it
- edit an already released Liquibase changeset, or hard-code the product name outside
  `Branding.java` and `web/src/branding.ts`
- mention a private repository, a plugin id from one, or a consumer this repo must not
  know about — in code, comments, docs, or the diff at all

Style, naming, file placement preferences, and "consider extracting this" suggestions are
🟡 Nit at most.

## Cap the nits

Report at most five Nits per review. Rank them. If more exist, keep the ones nearest
the changed code and drop the rest.

## Do not report

CI already enforces these, and a comment about them costs a round for nothing:

- Java formatting (Palantir/Spotless) and Prettier formatting
- ESLint or TypeScript errors, module-boundary violations `eslint-plugin-boundaries`
  already catches, ArchUnit and Spring Modulith results
- a stale `web/src/kernel/api/schema.d.ts`, `web/openapi.json` or
  `web/manifest.snapshot.json`, a stale `docs/modules/`, or a stale coverage figure
- test coverage percentages, and missing tests for pure wiring
- image vulnerabilities, dependency advisories, OSV results
- anything under `changelog/` (historical release notes, never edited) or
  `web/node_modules/`

## Always check

- **Generated files are not hand-edited.** `schema.d.ts`, `openapi.json`,
  `manifest.snapshot.json` and `docs/modules/` change only through their generators; a
  manual edit there is Important.
- **Imports, never inline qualified names.** `io.github.…Foo` or
  `java.util.List.of(…)` written inline in a field, parameter, `new`, cast or call is a
  finding. A real simple-name clash is the only exception.
- **A mutating endpoint** takes `dryRun` and returns the affected count without acting,
  and its audit row is written before the broker call, in its own transaction.
- **A destructive control** confirms by hold (`ui/HoldToConfirm.tsx` via a
  `tone="danger"` `ConfirmDialog`), names the resource and the count, and states the
  blast radius before it can be armed. Never a typed name, a checkbox, or a countdown.
- **Gating** goes through `kernel/auth/useCan.ts`, disables with a stated reason, and is
  keyboard-reachable. Unknown capability leaves the control enabled and says so.
- **Liquibase**: a schema change edits that module's `changes/0001-baseline.sql`.
  Columns follow the padding order (8-byte, then 4-byte, then uuid and boolean), the PK
  is not first, every uuid defaults to `uuidv7()`, every foreign key leads an index.
- **Frontend reuse**: tables come from `ui/table/DataTable` with a `columns.ts` factory
  (`kind` and `priority`, never pixel widths); pages use the `ui/` page parts; toasts go
  through `ui/notify.ts`. A hand-rolled empty state, red `Alert`, `Title order`, or
  `notifications.show` is a finding.
- **Tokens only**: no colour literal in `.tsx`/`.css` under `web/src/features` or
  `web/src/ui`; logical CSS properties, never `left`/`right`.
- **A new screen has a test at the user's altitude**, queried by role and accessible
  name rather than class or test id.
- **The commit subject and body are the release note.** A behaviour change marked
  neither `!` nor `BREAKING CHANGE:` while breaking something is Important.
- **Evidence, not inference.** A claim about behaviour cites the `file:line` it comes
  from. Speculation about what a dependency does is not a finding.
