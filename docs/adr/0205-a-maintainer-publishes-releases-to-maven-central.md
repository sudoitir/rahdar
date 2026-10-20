# ADR-0205: A maintainer publishes releases to Maven Central

- **Status**: accepted; amends [ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md) (Central
  gets a release when its inputs changed) and [ADR-0151](0151-published-generated-clients.md)
  (every release publishes the Java client)
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

Every push to `main` that changes the image cuts a release, which is dozens a month. Each one published
the Java client to Maven Central, and the plugin API too whenever the backend changed, which is nearly
every release. Central limits how much a namespace publishes, and `io.github.sudoitir` is shared with
other projects, so these uploads used up the allowance. A plugin author or a Java client user needs a
release on Central now and then, not every one.

## Decision

Maven Central gets a release only when a maintainer publishes it.

- The two Central jobs leave the release in `ci.yml` and become `publish-central.yml`, a
  `workflow_dispatch` workflow. A maintainer runs it with a release's version (Actions → Publish to Maven
  Central → Run workflow, or `gh workflow run publish-central.yml -f version=YYYY.MM.N`).
- One run publishes that release's plugin API (`io.github.sudoitir:rahdar`) and its Java client
  (`io.github.sudoitir:rahdar-client`). The API still publishes only when the backend changed since the
  newest version on Central, and japicmp still gates it on `Contract.VERSION` (ADR-0102, ADR-0112).
- The run refuses a version that is not a release tag. Runs do not overlap. Only people with write
  access to the repository can start one.
- Docker Hub, the GitHub release, its attached jar and OpenAPI document, and npm (the plugin SDK and the
  TypeScript client) still come from every release.

## Consequences

- Central holds the releases a maintainer chose, not every release. The plugin API's japicmp baseline
  is the newest version on Central, so a break is still caught against what plugin authors build on.
- A plugin built against a Rahdar release needs that release on Central first: its maintainer publishes
  it, or the plugin's author builds the release from its tag into `~/.m2`.
- The Java client on Central can lag the server. Its version still names the release it was generated
  from, and every release attaches the OpenAPI document to generate one locally.

## Alternatives considered

- **Publish on a schedule** (weekly). Bounded, but it publishes when nobody needs it and makes someone
  who does wait.
- **Publish only when `Contract.VERSION` changes.** Fewer uploads, but a plugin author who needs a fix
  that keeps the contract would get nothing.
- **Keep publishing every release.** It exceeds the allowance.
