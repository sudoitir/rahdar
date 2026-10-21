## How to run this change
This change is designed: `design.md` holds the decisions, and `tasks.md` holds the units. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, `design.md`, and the capabilities it names in `openspec/specs/`. Also read ADR-0203 to ADR-0210 and OpenBroker's `broker-sdk-kafka` capability and ADRs (in its repository).
3. Check that the OpenBroker release with `openbroker-kafka` is on Maven Central. Re-check its API and the Kafka image with ctx7. If a fact in `design.md` no longer holds, fix it with `/opsx:update` before applying. Ask the user only what is really theirs to decide.
4. Load the skills for the work: `operator-console-ux`, `console-page-parts`, `console-data-table`, `console-forms`, `rahdar-navigation-and-layout`, `rahdar-safe-interactions`, and `ui-visual-qa-loop` for the screenshots.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify:
   - run the narrow tests per unit;
   - run Rahdar with a Kafka cluster next to an Artemis cluster;
   - take screenshots of the estate view, every Kafka screen and the shared screens on a Kafka cluster, in light and dark, and in the empty, error and unavailable states;
   - push, and let CI run the full suite.
7. PR, merge on green CI and an empty Sonar list, `/opsx:archive`.

## Why
Rahdar's surface is broker-neutral, and its estate view was built to hold more than one system. OpenBroker now has a Kafka driver. With it, Rahdar can manage the most widely run streaming platform next to Artemis in one estate, with the same shared screens, honest about what Kafka can and cannot do, plus the screens only Kafka needs: consumer groups and lag, offset resets, topic configuration, ACLs, transactions and the controller quorum.

## What Changes
- Rahdar builds against the OpenBroker release that has `openbroker-kafka`, and loads the Kafka driver.
- Kafka clusters are registered from bootstrap servers with SASL or mutual TLS. The supported range is Kafka 4.0 and later.
- The estate view lists Kafka clusters next to Artemis, with comparable health and their subscriptions (consumer groups and share groups on topics), with lag as an estimate.
- Shared screens show Kafka's own labels from the SDK's kinds. Actions Kafka cannot perform (move, copy, delete by message) are disabled with the reason. Purge and truncate state their reach, need an empty group where Kafka requires it, and go through the approval gate.
- Browsing a Kafka topic picks a partition and an offset or a time, and never joins a group.
- A `system/kafka` module with Kafka-only screens:
  - consumer groups with per-partition lag and members;
  - offset resets with a preview;
  - topic configuration;
  - ACLs;
  - hanging transactions;
  - brokers and the controller quorum.
- MCP tools for the Kafka operations, held for approval like their shared counterparts.
- Plugins can consume Kafka subscriptions through the existing plugin messaging contract.
- A Kafka cluster in the dev stack and a three-broker cluster in the demo, with traffic.

## Capabilities
### New Capabilities
- `system-kafka`: the Kafka-only screens, API and tools under the cluster's Kafka namespace.

### Modified Capabilities
- `cluster-registration`: a Kafka cluster is registered from bootstrap servers, with its version range.
- `estate-view`: native labels come from the SDK's kinds, and lag is shown as an estimate with its breakdown.
- `message-operations`: browsing by partition, offset and time; purge and truncate state their reach on a partitioned log.
- `mcp-server`: Kafka tools.
- `plugin-messaging`: a plugin can consume a partitioned log, and where a system counts every delivery a release is counted and disclosed.

## Out of scope
- Kafka Streams, Connect and schema registries (schema detection is a later change).
- Partition reassignment, broker configuration and quotas.
- Cloud-specific authentication, for example IAM-signed SASL.
- Clusters before Kafka 4.0.

## Depends on
- 19e-openbroker-kafka (the OpenBroker release with the Kafka driver, on Maven Central)
- 19e3-openbroker-messaging-client (Rahdar on OpenBroker's messaging client)

## Execution
**Subagent-driven + reviewer**:
- Units: registration and the estate view, the shared screens on Kafka, the Kafka-only backend, the Kafka-only screens, and MCP with the demo.
- The backend and web of each unit are one implementer each, against the API contract in `design.md`.
- The reviewer checks the destructive paths and the approval gate before the PR.

## Impact
- **Backend:** `pom.xml`; `platform/broker` (driver loading, kind labels); `platform/clusters` (registration); new `system/kafka` with Liquibase changesets prefixed `kafka_`.
- **Web:** the shared web screens; a new `web/src/system/kafka` (or the repo's equivalent feature folder) with views carrying `system: 'kafka'`.
- **Interfaces:** the MCP catalogue and `McpToolSchemaBudgetTest`; the public API under `/clusters/{c}/kafka/…`.
- **Stacks:** the dev and demo compose stacks; `scripts/demo-seed.sh`.
- **Docs:** docs and ADRs. `Contract.VERSION` is bumped only if japicmp flags the plugin API.
