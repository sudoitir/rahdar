## How to run this change
Run it in a fresh Claude session after `19e4-kafka` is merged:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal and its specs, the archived `19e4-kafka` change (its `design.md`, and `qa/findings.md`, `qa/e2e-results.md` and `qa/capture-plan.md`), ADR-0216, ADR-0218 and ADR-0219.
3. Load the skills: `ui-visual-qa-loop`, `operator-console-ux`, `console-page-parts`, `console-data-table`, `rahdar-navigation-and-layout`, `rahdar-safe-interactions`.
4. Bring up the isolated QA stack with the three-broker Kafka cluster next to the Artemis pairs (`just qa-up kafka 18080 demo=1`), on its own project name and ports.
5. Do the tasks with the harness under **Execution**, then one fix round for everything they find.
6. Push, and let CI run the full suite. PR, merge on green CI and an empty Sonar list, `/opsx:archive`, and tick it in `ROADMAP.md`.

## Why
`19e4-kafka` built and verified Kafka in Rahdar end to end on real brokers, with three review passes and three retention and edge-case audits closed. Four checks the change set itself remain: Kafka at a large estate's scale has not been measured, the screenshot sweep's failing checks are not all triaged, the design and user flows have not had an independent review against an enterprise-grade bar, and one web dependency bump was reverted without its root cause. They are separated here so the Kafka support can merge and these finish on a clean branch.

## What Changes
- **Performance at scale.** A Kafka cluster seeded at scale (≈200 topics and 3,000 partitions, ≈300 groups, deep lag, ≈500 ACLs, hanging transactions) is measured with the repository's seed and measurement scripts (`scripts/perf/kafka-load-seed.sh`, `scripts/perf/kafka-measure.sh`): API latency, the scrape's broker rounds per cycle, the scrape cycle against its interval, bounded responses, storage growth per hour (`broker_event`, `metric_sample`) and the SSE bytes of one scrape tick. Every budget is met, or the cause is fixed.
- **The screenshot sweep.** Every failing check of the sweep over the estate, registration, every Kafka screen and the shared screens on Kafka (1440 and 1920 wide, light and dark, every state) is fixed in the product or, where the check is wrong, in the sweep, and the sweep ends with no open failure.
- **Design and UX review.** An independent Opus review of the design, every user flow (registration, offset reset, truncate, ACL change, transaction abort and hanging transactions, browse, bulk purge) and the UI against an enterprise-grade bar (prior art: Confluent Control Center, Redpanda Console, Conduktor, AKHQ, Kafbat UI), from the sweep's captures and the running app. Every finding is fixed.
- **The `@tanstack/react-virtual` bump.** The 3.14.22 bump broke 40 DataTable tests and was pinned back to 3.14.14. Its root cause is found: Rahdar's code is fixed and the pin removed, or an upstream regression is pinned with its issue and a Dependabot ignore.

## Capabilities
### Modified Capabilities
- `performance-sizing`: Kafka at scale has measured budgets.
- `operator-ui`: every Kafka screen passes the sweep and the design review.

## Out of scope
- New Kafka features. Anything found that is a new feature becomes its own roadmap change.

## Depends on
- 19e4-kafka (merged).

## Execution
**Subagent-driven + reviewer**: one implementer for the web fixes, one for the backend performance fixes, the `opus-medium` agent for the design and UX review, and the `reviewer` on any fix that touches a destructive path.

## Impact
- **Web:** fixes from the sweep and the review; the `@tanstack/react-virtual` version.
- **Backend:** fixes the performance run finds.
- **Docs:** measured Kafka sizing in the guide (`site/src/guide/`), per the `performance-sizing` capability.
