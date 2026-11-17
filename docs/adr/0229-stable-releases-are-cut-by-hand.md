# ADR-0229: Stable releases are cut by hand; main publishes :dev

- **Status**: accepted; amends [ADR-0042](0042-calver-releases-on-docker-hub.md) (a release on every
  push), [ADR-0088](0088-path-filtered-ci-and-releases.md) and
  [ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md) (main releases what changed),
  and [ADR-0205](0205-a-maintainer-publishes-releases-to-maven-central.md) (Docker Hub, the GitHub
  Release and npm still come from every release)
- **Date**: 2026-11-17
- **Deciders**: Mahdi Amirabdollahi

## Context

Every merge that changed the image cut a CalVer tag, pushed `:<version>`, `:<YYYY.MM>` and `:dev`,
and opened a GitHub prerelease. The releases page was a list of prereleases, the docs called the
product alpha, and there was no `:latest`. Maven Central had already been taken off that path
(ADR-0205): a maintainer runs `publish-central.yml` with a version that is already a tag, because
Central limits how much a namespace publishes and because not every build is one a plugin author
should depend on.

The same shape fits the product release. `:dev` is how someone tracks `main`. A stable release is
the one a person chose, with a version tag, `:latest`, `:stable` and a GitHub Release that is not
a prerelease. A compatibility promise is a separate decision and is not made here. CalVer still
carries no compatibility signal; breaking changes are still announced in the commit and listed at
the top of the release notes (ADR-0051).

## Decision

A push to `main` that changes the image inputs publishes `sudoit1/rahdar:dev` and the build cache,
and nothing else. No git tag, no GitHub Release, no npm publish. The image's version is `dev`.

A stable release is `release.yml`, started by hand with a commit that is on `main`. The version is
still computed, never typed. The run tags that commit, pushes one image as the immutable version,
the month, `:latest` and `:stable`, opens the GitHub Release with `prerelease: false`, and publishes
npm (`@rahdar/plugin-sdk` when it changed, `@rahdar/client` every time). It does not move `:dev`
and does not write the build cache. Maven Central stays `publish-central.yml`.

The Docker Hub landing page stays `docs/dockerhub.md`, pushed by `ci.yml` when that file changes.
It is not part of the release run.

npm trusted publishing and the attestation signer follow the workflow file. `:dev` is signed by
`ci.yml`. A stable release is signed by `release.yml`. Releases already published stay signed by
`ci.yml`. Central artifacts stay signed by `publish-central.yml`.

The compose fallback stays `:dev` until `:latest` exists. `just setup` keeps pinning to the newest
CalVer tag.

## Consequences

- The GitHub releases page lists the versions a maintainer cut. `:dev` can move many times between
  two of them. Pin a version tag for an install that must not move.
- The first `:latest` appears when someone runs Release. Until then the tag does not exist, so the
  documented default image stays `:dev`.
- `@rahdar/plugin-sdk` and `@rahdar/client` must trust workflow `release.yml` on npmjs.com before
  the npm jobs of a release succeed. The image and the GitHub Release do not wait on that.
- A failed step after the tag is pushed burns that version number, as before.
- Someone verifying an old release still passes `--signer-workflow` for `ci.yml`. The verify guide
  says which file signed which artifact.

## Alternatives considered

- **Keep cutting a prerelease on every merge, and add a workflow that only moves `:latest`.** The
  releases page stays a firehose of prereleases, which is the part that reads as unfinished.
- **Put the manual run on `ci.yml`.** npm already trusts that file, but one missed `if` runs the
  pull-request matrix on a click, or skips it. A separate file matches `publish-central.yml`.
- **Retag the `:dev` digest as `:latest`.** `:dev` is built as version `dev`. The stable image has
  to contain the CalVer, so the release builds that commit again.
