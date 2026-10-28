Rules:
- Heavy commands run in `systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- …`, at most two at once across agents.
- Read narrowly. Run only the tests a unit touches; CI runs the rest.
- Agents commit and push their own work and never open a PR; the lead opens one PR.
- Builds against the released OpenBroker `0.6.0`.

Order: 1, then 2 to 4 (backend and web in parallel against the contract in `design.md`), then 5 to 6.

## 1. Driver, system per cluster, labels
- [x] 1.1 Add `openbroker-kafka`; `BrokerGateway` builds options per `cluster.system` through a `ConnectionOptions` extension; remove `BrokerGateway.SYSTEM` (callers name `ArtemisModule.SYSTEM` or `KafkaModule.SYSTEM`); `system/kafka` module skeleton with `KafkaModule`. Check: `BrokerDrivers` lists `kafka` in a unit test, `ModularityTest`, `BoundaryRulesTest`
- [x] 1.2 `GET /api/v1/systems` (D1). Check: controller test
- [x] 1.3 (backend) Kind labels from the SDK replace `ResourceIds.label` and `kindLabelOf` in the API and the web, `plural()` removed (D3). Check: cluster view and mapper tests, web unit tests for kind names

## 2. Registration and estate
- [x] 2.1 (backend) Per-system registration and edit API with the `connection` union, the `ConnectionCheck` view, per-node reach, failure causes, already-registered by cluster id, sealed secrets (D2). Check: registration tests against Kafka Testcontainers (4.4 SASL SCRAM, 3.5 ZooKeeper, 2.8 refused, an unreachable advertised listener), Artemis registration tests unchanged in behaviour
- [ ] 2.2 Registration page `/clusters/new` with the system choice, the Kafka and Artemis forms (multi-token seeds and bootstrap servers, protocol and mechanism, credential pairs, plaintext SASL warning), the check panel and Register; the entry points link to it; the modal removed; the connection edit form per system. Check: Vitest browser tests for both systems, every failure cause, pairs both directions, keyboard pass
- [x] 2.3 (backend) Health per system (D4) and the backlog accuracy model with the breakdown endpoint (D5). Check: health evaluator unit tests, estate tests with an Artemis and a Kafka cluster
- [ ] 2.4 Estate and subscriptions tables with Backlog, estimate marking and the breakdown drawer. Check: browser tests

## 3. Shared screens on Kafka
- [x] 3.1 Browse by partition, offset and time; filter refused with the reason (D6). Check: API tests against a Kafka Testcontainer (time, past end, retention start, tombstone), browser tests
- [x] 3.2 Purge preview naming members or rebalancing; shared truncate with `destination:truncate`, compacted refusal, held confirmation, gate and audit. Check: gated operation tests, browser tests with `holdButton`
- [x] 3.3 Per-record move, copy and delete disabled with the driver's reason, API refusal; Artemis-only actions gated by kind verbs; no system literal in shared components; stale "typed name" comments fixed. Check: browser tests on a Kafka fixture, grep for `'artemis'` in shared web code

## 4. Kafka backend and screens (`system/kafka`, `features/kafka`)
- [ ] 4.1 Groups list and group detail reads, batched per cycle; namespace 404 for other systems. Check: tests against a Kafka Testcontainer with a lagging group, `SystemNamespaceTest` extended
- [ ] 4.2 Offset reset preview and run, gated, approval-held, audited, etag re-checked on approval. Check: tests for empty, active, rebalancing, stale, out of range, past end, classic and share groups
- [ ] 4.3 Topic configuration, ACLs, transactions and brokers with quorum, with their previews; new permissions in the catalogue and built-in roles. Check: one test class per resource, permission tests
- [ ] 4.4 Groups and group screens. Check: Vitest browser tests for every state
- [ ] 4.5 Offset reset flow with preview, held confirmation and disabled-with-reason. Check: browser tests, keyboard pass
- [ ] 4.6 Topic configuration, ACLs, transactions and brokers screens. Check: browser tests per screen, every state

## 5. Plugin messaging, MCP, stacks
- [x] 5.1 Consume source by subscription reference; Kafka consumer and share groups with release, reject, key ordering and the counted-release disclosure; taps refused on Kafka. Check: plugin messaging tests against a Kafka Testcontainer; japicmp, `Contract.VERSION` if flagged
- [x] 5.2 Kafka MCP tools with previews and approval holding; raise the tool budget with a comment. Check: `McpSystemToolsTest`, `McpToolSchemaBudgetTest`
- [ ] 5.3 Kafka in the dev compose and a three-broker KRaft cluster in the demo compose and `scripts/demo-seed.sh` (groups, lag, a share group, a hanging transaction). Check: `just demo` brings up both systems and the estate shows both

## 6. Docs, sweep, review, finish
- [ ] 6.1 ADRs 0213 to 0216; docs pages (supported versions, registering a Kafka cluster, the Kafka screens, MCP); glossary terms. Check: the site builds
- [ ] 6.2 Screenshot sweep (`ui-visual-qa-loop`): estate, registration, every Kafka screen and the shared screens on Kafka, light and dark, every state; fix and recapture. Check: captures reviewed, no open finding
- [ ] 6.2b Design and UX review by an Opus agent (`opus-medium`): the design, every user flow (registration, offset reset, truncate, ACL change, transaction abort, browse) and the UI against an enterprise-grade bar (prior art: Confluent Control Center, Redpanda Console, Conduktor, AKHQ), from the sweep's captures and the running app; findings merged into the one fix round. Check: its list is closed
- [ ] 6.3 `reviewer` pass on the destructive paths, the approval gate, secrets and the registration API; fix findings. Check: the reviewer's list is closed
- [ ] 6.4 Raise `openbroker.version` to `0.6.0` (done, 3188d46ab); push; CI green and the Sonar list empty. Check: the PR's checks

## Workflow follow-up
- Merge, `/opsx:archive`, stop the stacks the session started.
