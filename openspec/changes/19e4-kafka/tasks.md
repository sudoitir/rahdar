Rules:
- Heavy commands run in `systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- …`, at most two at once across agents.
- Read narrowly. Run only the tests a unit touches; CI runs the rest.
- Agents commit and push their own work and never open a PR; the lead opens one PR.

Order: unit 1 first; units 2 and 3 in parallel (one backend and one web implementer, against D4); unit 4 after 3; unit 5 last.

## 1. Driver, registration and labels
- [ ] 1.1 Raise `openbroker.version` to the release with `openbroker-kafka` and add the driver. Check: `BrokerDrivers` lists `kafka` in a unit test
- [ ] 1.2 Per-system registration request (D1), the Kafka form, and a dry run through `verify`. Check: registration tests against a Kafka Testcontainer, including the 3.9 refusal
- [ ] 1.3 Kind labels from the SDK replace `ResourceIds.label` in the API and the web (D2). Check: cluster view tests and the web unit tests for kind names
- [ ] 1.4 Estate health and lag estimates for Kafka clusters. Check: estate view tests with an Artemis and a Kafka cluster

## 2. Kafka backend (`system/kafka`)
- [ ] 2.1 Groups, lag and members reads, batched per cycle. Check: tests against a Kafka Testcontainer with a lagging group
- [ ] 2.2 Offset reset preview and run, gated, approval-held and audited. Check: tests for empty, active and stale cases
- [ ] 2.3 Topic configuration, ACLs, transactions and brokers with quorum, with their previews. Check: one test class per resource
- [ ] 2.4 Namespace routing and the not-found answer for other systems. Check: `SystemNamespaceTest` extended for Kafka

## 3. Kafka screens and shared screens
- [ ] 3.1 Groups list and group page with the per-partition table (`console-data-table`), empty and error states. Check: Vitest browser tests
- [ ] 3.2 Offset reset flow with preview, held confirmation and disabled-with-reason for active groups (`rahdar-safe-interactions`). Check: browser tests
- [ ] 3.3 Topic configuration, ACLs, transactions and brokers screens. Check: browser tests per screen
- [ ] 3.4 Shared screens: the browse partition and start picker, purge and truncate reach previews, disabled per-message actions, and the lag breakdown drawer. Check: browser tests on a Kafka fixture

## 4. MCP, dev stack and demo
- [ ] 4.1 Kafka MCP tools with previews and approval holding; raise the tool budget with a comment. Check: `McpSystemToolsTest`, `McpToolSchemaBudgetTest`
- [ ] 4.2 Kafka in the dev and demo compose, and in `scripts/demo-seed.sh` traffic (groups, lag, a share group). Check: `just demo` brings up both systems and the estate shows both

## 5. Docs, screenshots and finish
- [ ] 5.1 ADRs (D9), the docs pages for Kafka clusters, and the glossary terms. Check: the docs build
- [ ] 5.2 Screenshot sweep (`ui-visual-qa-loop`): estate, each Kafka screen and the shared screens on Kafka, light and dark, empty, error and unavailable states; fix and recapture. Check: captures reviewed, no open finding
- [ ] 5.3 `reviewer` pass on the destructive paths and the approval gate; fix findings. Check: the reviewer's list is closed
- [ ] 5.4 Push; CI green and the Sonar list empty. Check: the PR's checks

## Workflow follow-up
- Merge, `/opsx:archive`, stop the stacks the session started.
