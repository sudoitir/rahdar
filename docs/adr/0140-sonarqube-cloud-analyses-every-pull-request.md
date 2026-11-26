# ADR-0140: SonarQube Cloud analyses every pull request

- **Status**: accepted (amended by [ADR-0155](0155-dependabot-pull-requests-skip-the-sonar-analysis.md))
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

Formatting, ESLint, ArchUnit, Spring Modulith and the tests catch a lot, but nothing tracked code
smells, likely bugs and coverage over time. A one-off SonarQube Cloud scan of the Java found 1,158
open issues, including a transaction mismatch, a possible NPE and a swallowed interrupt. It never
looked at the web code. Issues nobody watches pile up again, so the check has to run on every change
and be able to block a merge.

## Decision

**We will analyse every pull request and every push to `main` with SonarQube Cloud from CI, and a
failing quality gate fails `ci-ok`.**

- CI's `sonar` job runs the SonarScanner for Maven (`sonar:sonar`, pinned in `pluginManagement`) with
  `sonar.qualitygate.wait=true`. It analyses `src/main` and `web/src`, with the merged JaCoCo XML
  from the `coverage` job and the Vitest lcov from the `frontend` job.
- `main` does not re-test (ADR-0126), so its analysis reuses the coverage artifacts of the PR run the
  merge commit came from. That keeps the dashboard and README badges current.
- Automatic Analysis is off in SonarQube Cloud; the project is analysed only from CI. The token is the
  `SONAR_TOKEN` repository secret, so fork PRs, which get no secrets, skip the job.
- The project uses SonarQube Cloud's default quality gate ("Sonar way").
- Each change resolves its own issues before its PR merges. An issue is fixed in code, or marked
  false positive in SonarQube Cloud with a comment that says why. A rule that conflicts with a
  recorded convention is scoped out in `pom.xml` (`sonar.issue.ignore.multicriteria`, rule and path)
  and listed below with its reason.

Project-level scoping:

- `sonar.plsql.file.suffixes=.plsql`: the Liquibase changesets are PostgreSQL. The PL/SQL analyser
  is Oracle's and reports Oracle rules against them (VARCHAR2, CHAR).
- `typescript:S6819`, `typescript:S6852` and `typescript:S1082` in `web/src/ui/table/GridTable.tsx`
  only: the data grid is an ARIA grid of divs on CSS-grid tracks (ADR-0020), because native table
  elements cannot be virtualised. It is one tab stop with roving cell focus set at runtime, and the
  grid, not each cell or row, handles Enter, Space and the menu keys (ADR-0108, ADR-0160). The rules
  ask for `<td>`, `<tr>` and `<th>` in place of the grid roles, for a tab stop on every role, and for
  a key handler beside every click handler.
- `typescript:S6845` in `web/src/features/kafka/SubjectView.tsx` only: the schema text is read-only
  and it scrolls once it is longer than `24rem`. A scroll region with no tab stop cannot be scrolled
  by keyboard and fails axe's `scrollable-region-focusable`, so this one takes a tab stop. The rule
  wants a tab stop only on interactive elements; here the tab stop is the accessibility fix rather
  than a violation of it, and every other scroll region in the console has the same shape.

## Consequences

- Smells, bugs and new-code coverage are visible on every PR and gate the merge.
- The `sonar` job adds a few minutes to PRs that touch backend or web code.
- The quality gate's new-code coverage condition means new code needs tests to merge. Coverage
  (ADR-0128) stays informational in the build itself; the gate is where it now counts.
- The project depends on a hosted service. If it is down, `ci-ok` fails until it is back.

## Alternatives considered

- **Automatic Analysis.** Rejected: it has no coverage and cannot gate `ci-ok`.
- **SonarQube Server, self-hosted.** Rejected: a server to run for a public project that SonarQube
  Cloud analyses for free.
- **Error Prone / PMD / SpotBugs in the build.** Rejected for now: several tools to configure and
  keep quiet, no web coverage, and no dashboard across PRs.
