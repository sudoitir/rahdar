# Rule: versioning, changelog, releases

See [ADR-0042](../../docs/adr/0042-calver-releases-on-docker-hub.md) for why.

## CalVer — `YYYY.MM.PATCH`

- `2026.09.0`, `2026.09.1`, … `2026.10.0`. `PATCH` is a counter that resets to `0`
  the first release of each calendar month (UTC), not a day-of-month.
- The version is **derived from git tags by CI** (`date +%Y.%m` + one past the
  highest existing `PATCH` for that month). It is never written into `pom.xml` —
  that stays `0.1.0-SNAPSHOT` in git and is set only in the CI working tree.
- Only tags matching `^[0-9]{4}\.[0-9]{2}\.[0-9]+$` are candidates when CI reads the
  highest `PATCH`, and the computed version is re-checked against that same pattern
  before anything is published. A tag of any other shape is ignored, not parsed.
- A version tag is **immutable on Docker Hub**, so it is never reused: CI refuses to
  release when the computed tag already exists on `origin`, and it tags the merge commit
  and pushes the tag *before* pushing the image. A failure after that burns the
  version number and the next push to `main` takes the following one. A gap in the
  sequence is expected and fine; a republished tag is not.
- Do not tag manually and do not add a version-bump commit.

## A push to `main` publishes `:dev`

Pull requests carry the whole verification; `main` does not re-test ([ADR-0126](../../docs/adr/0126-pull-requests-verify-main-releases-what-changed.md)).
The ruleset requires the one `ci-ok` check on a branch that is up to date with `main`,
so the merge commit is the tree CI verified. A ready PR gets `gh pr merge <n> --merge --auto`; the
`pr-auto-update` workflow brings it up to date in its turn (one PR at a time, oldest auto-merge first), and it merges when CI is green
([ADR-0170](../../docs/adr/0170-auto-update-keeps-pull-requests-up-to-date.md)).

A push to `main` publishes `sudoit1/rahdar:dev` when it changes what the image is built from
(`src/`, `web/`, `clients/`, `pom.xml`, the Maven wrapper, `Dockerfile`, `.dockerignore`). A docs-,
site- or CI-only push publishes no image; its commits appear in the next stable release's
notes. The path lists live in the `changes` job of `ci.yml`
([ADR-0088](../../docs/adr/0088-path-filtered-ci-and-releases.md)).

The `dev` job in `ci.yml` does that and nothing else ([ADR-0229](../../docs/adr/0229-stable-releases-are-cut-by-hand.md)):

- sets the project version to `dev` in the working tree and commits nothing;
- pushes `sudoit1/rahdar:dev` (`linux/amd64` + `linux/arm64`) and writes the build cache
  `sudoit1/rahdar:buildcache`;
- attests the image keylessly with `actions/attest` (ADR-0139). The signer workflow is `ci.yml`.

No git tag, no GitHub Release, and no npm publish.

## A stable release is a maintainer's step

Actions → Release → Run workflow, with a commit on `main` (the default is `main`), or
`gh workflow run release.yml -f ref=main`. One run of `release.yml`:

- refuses a commit that is not on `main`;
- computes the CalVer, tags that commit and pushes only the tag, before the image. It commits
  nothing, so `main` never moves and no open pull request falls behind
  ([ADR-0129](../../docs/adr/0129-releases-tag-the-merge-commit-and-commit-nothing.md));
- pushes one image as `:<version>` (immutable), `:<YYYY.MM>` (the month's stable), `:latest` and
  `:stable` (the same digest). It does not move `:dev` and does not write the build cache;
- attests the image and the jar (signer workflow `release.yml`), opens the GitHub Release
  (`prerelease: false`) with the notes and the same assets as before, verifies the attestations,
  and dispatches `pages.yml`;
- publishes npm from this file: `@rahdar/plugin-sdk` when `web/packages`, `web/src/sdk`,
  `web/src/kernel` or the web manifests changed since the newest version on npm, and
  `@rahdar/client` on every stable release, from the release's `web/openapi.json` (ADR-0151).

npm trusted publishing is bound to the workflow file name. `@rahdar/plugin-sdk` and `@rahdar/client`
trust `release.yml` (repository `sudoitir/rahdar`, no environment). A release whose npm jobs fail
for that reason is re-run after the publisher is saved; the image and the GitHub Release are
already published.

The job summary prints `gh workflow run publish-central.yml -f version=YYYY.MM.N`.

`hub-description` in `ci.yml` pushes `docs/dockerhub.md` as the Docker Hub repository description
whenever that file changes on `main`. It does not wait for a stable release, and `release.yml` does
not update the page. That file is the Hub's landing page and is **not** `README.md`.
`DOCKERHUB_TOKEN` must be a PAT with **read, write and delete** scope: the description endpoint
rejects a repo-scoped token with `Forbidden`.

A pull request that changes the image inputs scans the built image with Grype and fails on a
critical or high finding that has a fix; bump the base-image digest or the dependency.

The compose fallback and `.env.example` stay on `:dev`, because `:latest` exists only after the
first run of `release.yml`. `just setup` pins `RAHDAR_IMAGE` to the newest CalVer tag, which is a
stable release.

## Maven Central is a maintainer's step

Central limits how much a namespace publishes, so no release goes there on its own (ADR-0205). A
maintainer publishes a release when a plugin author or a Java client user needs it: Actions → Publish
to Maven Central → Run workflow with the release's version, or
`gh workflow run publish-central.yml -f version=YYYY.MM.N`. One run of `publish-central.yml` publishes:

- the plugin API, `io.github.sudoitir:rahdar`, when `src/main` or `pom.xml` changed since the newest
  version on Central, japicmp-gated as before, and attests the files it deployed;
- the Java client, `io.github.sudoitir:rahdar-client`, generated from the release's `web/openapi.json`.

It refuses a version that is not a release tag. Runs do not overlap.

## Changelog

Each release's notes are its GitHub release body, generated from the commit messages
in that release. `changelog/` keeps the files of the releases up to 2026.09.60 as the
historical record, and the site's changelog lists every release: it fetches the notes
of those without a file from GitHub Releases when it builds. There is no
`CHANGELOG.md` and no `## [Unreleased]` section.

- The obligation moved to the **commit message**: see
  [`05-commits.md`](05-commits.md) for the Conventional Commits format, which
  types produce an entry, and how a breaking change announces itself.
- `just changelog` renders what the next release will say.
- `release.yml` renders the notes with `git-cliff` (pinned in `cliff.toml` and
  `release.yml`) and splices `changelog/unreleased.md` when it changed
  since the previous release. Never hand-edit a released file or a release body — each
  is a historical record.

