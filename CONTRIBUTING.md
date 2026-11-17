# Contributing to Rahdar

Thanks for looking. The project is pre-alpha; the shape of things can still move.

## Before you write code

1. **Open an issue** describing the change. For anything that alters what the
   product does, we work it through [**OpenSpec**](https://openspec.dev) first
   (`openspec/`, `/opsx:propose`). Bug fixes and refactors don't need a proposal.
- One logical change per PR. Reference the issue and any ADR/[OpenSpec](https://openspec.dev) change.
2. **Check the ADRs** (`docs/adr/`). They're binding. A change that departs from
   one needs a new ADR in the same PR.
3. **Library questions** go through [**ctx7**](https://context7.com), not memory — versions and APIs in
   this space move, and we've been bitten.

## Development

```bash
just            # list tasks
just up         # dev stack (Postgres + Artemis primary/backup + Rahdar)
just verify     # what CI runs
just fmt        # Palantir Java Format (Spotless) + eslint --fix
```

- JDK 25, Node 22, Docker. Or open the dev container (`.devcontainer/`).
- Backend formatting is enforced (Spotless / Palantir) — `just fmt` before you
  commit, or CI will fail on the diff.
- Frontend: no raw colour/spacing literals in components — tokens only
  (`web/src/theme.ts` → `web/src/theme.css` → component). Logical CSS properties
  (`inline-start`, not `left`).
- Migrations: new Liquibase changeset in the owning module's changelog, never edit a
  released one.
- Code goes in its module: `kernel`, `platform` or a feature, on the backend and in
  `web/src` alike. `CLAUDE.md` ("Layout") and the site's
  [plugin guide](https://rahdar.dev/guide/plugins) say where;
  `just verify` fails on a boundary violation.

## Pull requests

- One logical change per PR. Reference the issue and any ADR/OpenSpec change.
- `just verify` green locally. On the PR, CI runs only the checks your change
  touches, in parallel: backend (three shards), frontend, the plugin template end to
  end, the image, the site, OSV for changed manifests and actionlint for changed
  workflows. The one required check is `ci-ok`, and the branch must be up to date
  with `main`. After the merge, `main` publishes `:dev` without re-testing. A stable release is the Release workflow (ADR-0126, ADR-0229).
- Coverage: `./mvnw verify` writes `target/site/jacoco/index.html`, and
  `npm run test:coverage` in `web/` writes `web/coverage/index.html`. CI reports both
  in the job summary (ADR-0128).
- New behaviour comes with a test at the user's altitude (Testing Library by
  role, MSW for the network; integration tests against the dev broker pair for
  backend broker code).
- Commits: imperative subject, explain *why* in the body when it isn't obvious.

## Licence and sign-off

By contributing you agree your work is licensed under
[Apache-2.0](LICENSE). Keep the `NOTICE` file intact.

## Code of conduct

[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) applies to every project space.
