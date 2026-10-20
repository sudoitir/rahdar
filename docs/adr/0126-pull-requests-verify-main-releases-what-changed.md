# ADR-0126: Pull requests carry the verification; main releases only what changed

- **Status**: accepted; supersedes in part [ADR-0088](0088-path-filtered-ci-and-releases.md)
  (the site's pull-request build and the release trigger's path list) and
  [ADR-0124](0124-codeql-and-osv-scanner-run-in-ci.md) (OSV on every pull request); decision 5 superseded by [ADR-0129](0129-releases-tag-the-merge-commit-and-commit-nothing.md); the cost of the up-to-date rule of decisions 1 and 2 is cut by [ADR-0170](0170-auto-update-keeps-pull-requests-up-to-date.md); Maven Central publishing is a maintainer's step since [ADR-0205](0205-a-maintainer-publishes-releases-to-maven-central.md)
- **Date**: 2026-09-29
- **Deciders**: Mahdi Amirabdollahi

## Context

A source pull request took about fifteen minutes. The backend suite ran as one serial Surefire
JVM (about ten minutes of test time), and the image build waited for it before starting its own
four. After the merge, `main` ran the same backend and frontend suites again before it released.
Then the release published the image, the plugin API to Maven Central and the SDK to npm, all
three, even when a change touched only the UI, only `ci.yml`, or only the Docker Hub description.
OSV scanned every pull request, documentation ones included, and nothing on `main` required a
check to pass before a merge.

## Decision

**We will verify each change once, on its pull request, and publish from `main` only the
artifacts whose inputs changed.**

1. **One required check.** Every pull-request job in `ci.yml` runs in parallel, gated by its own
   path filter. That covers the backend (three Surefire shards, round-robin over the sorted test
   classes), the frontend, the plugin template end to end, the image build, the site build, OSV
   when a manifest changed, and actionlint when a workflow changed. `ci-ok` depends on all of them
   and fails when any failed or was cancelled. The `main` ruleset requires only `ci-ok`, on a
   branch that is up to date with `main`, so a job skipped by its path filter never blocks a merge.
2. **No re-test on `main`.** Because the ruleset is strict, the merge commit is the tree CI
   verified. A push to `main` goes straight to `release` when what the image is built from
   changed. A change to `ci.yml` alone tests everything on its PR and releases nothing.
3. **Per-artifact publish gates.** `publish-api` compares the release tag with the newest version
   on Maven Central, over `src/main` and `pom.xml`. `publish-sdk` compares it with the newest
   version on npm, over the SDK's inputs (`web/packages`, `web/src/sdk`, `web/src/kernel`, the web
   manifests). Each publishes only on a difference. Comparing against the registry rather than the
   previous commit means a failed publish is retried by the next release.
4. **The Docker Hub description** is its own job, run when `docs/dockerhub.md` changes. It no
   longer cuts a release.
5. **The release pushes through a deploy key.** The release commit and tag go to `main` over SSH
   with a deploy key that is the ruleset's only bypass besides the repository admin. The workflow
   token cannot bypass a required status check.
6. **Pinned actions.** Every third-party action is pinned to a commit SHA with its version in a
   comment. Dependabot updates them weekly.

## Consequences

- A source PR takes about as long as its slowest shard, not the sum of backend and image. A
  merge releases in about five minutes instead of sixteen.
- Strict up-to-date branches mean that after each release commit, an open PR must be updated
  and re-run before it can merge. With one maintainer and few open PRs, that is cheap. A merge
  queue would remove it, but merge queues are not available to user-owned repositories.
- A version on Docker Hub may have no matching Maven Central or npm version. That is expected: a
  plugin builds against the newest API and SDK that exist, and `studio.since` states the oldest
  Studio it runs on.
- The shards are balanced by class count, not by time. If one lags, weigh them by the Surefire
  report times.
- Losing the deploy key blocks releases until a new key is added to the repository, the secret
  and the ruleset.

## Alternatives considered

- **Keep re-testing on `main`.** Rejected: it repeats a run that verified the same tree and
  delays every release by about eleven minutes.
- **A merge queue.** Not available for a repository owned by a user account.
- **Parallel Surefire forks in one job.** Rejected: timing-sensitive broker tests share the CPU
  and get flakier, and local runs would change too. Separate runners isolate them.
- **Path filters on the publish jobs, against the previous commit.** Rejected: a failed publish
  would never be retried, because the next release's diff no longer contains the change.
