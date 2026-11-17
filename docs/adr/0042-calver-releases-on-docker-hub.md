# ADR-0042: CalVer releases published to Docker Hub on every push to main

- **Status**: accepted; the changelog mechanics below are superseded by
  [ADR-0051](0051-changelog-generated-from-commits.md), and the release trigger
  (every push) by [ADR-0084](0088-path-filtered-ci-and-releases.md); amended by [ADR-0102](0102-the-plugin-api-is-published-to-central-and-npm.md) (a release also publishes the plugin API), and by [ADR-0139](0139-release-attestations-and-image-scan.md) (releases are attested); a stable release is cut by hand and main publishes `:dev` since [ADR-0229](0229-stable-releases-are-cut-by-hand.md)
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

CI built and tested the code but published nothing. `pom.xml` was frozen at
`0.1.0-SNAPSHOT`, the `image` job built `ghcr.io/sudoitir/artemis-studio:ci` and
discarded it, and there were no git tags, no `CHANGELOG.md`, and no GitHub
Releases. Running the product meant cloning and building it.

[ADR-0007](0007-packaging-single-image-compose-first.md) committed to a single
container image and Compose-first delivery. That only pays off if the image is
actually somewhere a user can `docker pull` it.

Artemis Studio is a continuously-delivered application, not a library. Nothing
downstream links against it or imports its API, so a SemVer `MAJOR.MINOR.PATCH`
contract has no consumer to serve — the numbers would be assigned by judgement
call with no rule behind them. What a user of an ops tool actually wants to know
from a version string is *how old is this build*.

The project is also pre-stable (alpha, Phases 0–8), so nothing published yet
should present itself as production-ready.

## Decision

**We will release on every push to `main`, versioned with CalVer
`YYYY.MM.PATCH`.**

- `PATCH` resets to `0` on the first release of each calendar month (UTC).
- The version is computed in CI from `date` and the existing git tags — one past
  the highest `PATCH` for the current month. It is never committed to `pom.xml`
  (no bump commit, no drift); CI sets it in the working tree with
  `versions:set` before packaging.
- **We will publish to Docker Hub only**, as `sudoit1/artemis-studio`, dropping
  the `ghcr.io` image. Docker Hub is the default registry for `docker pull`, is
  discoverable, and serves anonymous pulls without a login — the right default
  for an open-source operations tool.
- Images are multi-arch (`linux/amd64` + `linux/arm64`) and carry three tags:
  `:<version>` (immutable), `:<YYYY.MM>` (moving month pointer), `:dev` (moving
  channel pointer).
- Each release also cuts an annotated git tag and a GitHub Release carrying the
  promoted `CHANGELOG.md` section, the runnable jar, and its SHA-256 checksum.
- **Until the first stable release**, no `:latest` tag is published and GitHub
  Releases are marked `prerelease: true`.
  [`.claude/rules/10-release.md`](../../.claude/rules/10-release.md) names the
  exact edits that flip this.
- `CHANGELOG.md` follows Keep a Changelog. Adding an entry under `## [Unreleased]`
  for any user-visible change is a merge-time obligation; the release job promotes
  that section in place. *(Superseded by
  [ADR-0051](0051-changelog-generated-from-commits.md): the changelog is generated
  from commit messages into one file per release under `changelog/`. Versioning,
  tagging and publishing are unchanged.)*

## Consequences

- A user runs the product with one `docker pull` / `docker compose up`. `just up`
  becomes the prod stack against the published image; the local-build dev stack
  moves to `just dev-up`.
- No release ceremony exists to forget or get wrong — merging to `main` *is* the
  release. The cost is that `main` must always be releasable, which it already had
  to be.
- **CalVer carries no compatibility signal.** A breaking change looks like any
  other version bump. Mitigation is procedural and mandatory: a `### Breaking`
  block at the top of the version's changelog section with the migration step.
  This ADR is the record that we accepted that trade knowingly.
- The release job pushes a commit (the changelog promotion) and a tag back to
  `main`. It is guarded with `[skip ci]` to avoid triggering itself. If branch
  protection is enabled on `main`, the job's identity needs a bypass or a PAT.
- Dropping `ghcr.io` means anyone who pinned that image path must repoint to
  Docker Hub. Acceptable per the project's no-backward-compatibility principle,
  and there are no known consumers.
- Monthly `PATCH` reset means version ordering is only correct when compared
  within the same month by numeric `PATCH`; across months the `YYYY.MM` prefix
  orders them. `sort -V` handles both.

## Alternatives considered

- **SemVer** — no downstream consumer to signal API compatibility to, so version
  numbers would be arbitrary. CalVer answers the question users actually ask.
- **Keep GHCR, or publish to both** — two registries means two defaults to keep
  in sync in compose files and docs, for no user benefit. One place to look.
- **`YYYY.0M.0D` date-exact CalVer** — precise but needs an ugly second-level
  `.N` suffix for same-day releases and is awkward as a Maven version.
  `YYYY.MM.PATCH` is a valid Maven version and reads cleanly.
- **Tag-triggered releases (manual `git tag` or a release workflow_dispatch)** —
  a ceremony that can be skipped or fumbled. Continuous release from `main`
  removes the step.
- **A version file bumped by a bot (release-please / git-cliff)** — needs
  Conventional Commits adopted repo-wide, a separate and larger process change.
  The commit history here is prose.
