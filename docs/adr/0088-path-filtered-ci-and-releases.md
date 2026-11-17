# ADR-0088: CI jobs and releases run only for the paths a change touches

- **Status**: accepted, superseded in part by [ADR-0126](0126-pull-requests-verify-main-releases-what-changed.md);
  supersedes the release trigger of
  [ADR-0042](0042-calver-releases-on-docker-hub.md); main publishes `:dev` and a stable release is cut by hand since [ADR-0229](0229-stable-releases-are-cut-by-hand.md)
- **Date**: 2026-09-19
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0042 released on every push to `main`. Every push therefore ran the full
backend suite (Testcontainers, a few minutes), the frontend build, a multi-arch
image build, and burned a CalVer number, even when the change was a typo in an ADR
or a page of the documentation site. The published image of such a "release" is
byte-for-byte the previous one under a new immutable tag, which tells an operator
something changed when nothing did.

The site already deploys on its own path filter (`pages.yml`, ADR-0061 D5), but a
pull request that broke the site build was only discovered after merge.

## Decision

**We will run each CI job only when the paths it depends on changed, and cut a
release only when a push to `main` changes what the image is built from.**

- A `changes` job in `ci.yml` classifies the diff with `dorny/paths-filter`
  (a push against the commit before it, a PR against its base) into `backend`
  (`src/**`, `pom.xml`, `mvnw`, `.mvn/**`), `web` (`web/**`) and `image` (both,
  plus `Dockerfile`, `.dockerignore`, `docs/dockerhub.md`). An edit to `ci.yml`
  counts as every kind.
- `backend` and `frontend` both run when either side changed: they are coupled
  through the OpenAPI snapshot a backend test writes and the schema generated
  from it.
- The PR image build and the `release` job run only on an `image` change. A
  skipped upstream job is not a failure.
- A docs- or site-only push releases nothing. Its commits are not lost: the next
  release's changelog is generated from every commit since the previous tag.
- `pages.yml` also runs on pull requests that touch the site's paths, building
  without deploying.

Versioning, tagging, the publish order and the channel rules of ADR-0042 are
unchanged.

## Consequences

- Documentation work costs one short job instead of a full release, and the
  Docker Hub tag list only moves when the image can differ.
- A path list is now something to maintain: a new build input outside the listed
  paths would not trigger a release. The filters sit in one place, next to the
  jobs that use them.
- Branch protection must not require a job that can be skipped by a path filter
  as a *specific* status only it produces; skipped jobs report success, so the
  current checks keep working.
- A release can contain several merged PRs' worth of documentation commits in its
  notes, grouped under the types that produce entries.

## Alternatives considered

- **Workflow-level `on.push.paths`.** Rejected: a required check whose workflow
  never starts stays pending forever and blocks the PR, and the release job needs
  the check results of the same run.
- **Keep releasing on every push.** Rejected: identical images under new
  immutable tags, and minutes of CI per typo.
- **Hand-written `git diff --name-only` step.** Rejected: re-implements the base
  selection for push vs pull request that the action already handles.
