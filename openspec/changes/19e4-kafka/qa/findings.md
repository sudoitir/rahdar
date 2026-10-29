# 19e4-kafka findings ledger

Every finding from the reviews, the audits and the live run, to closure. Nothing is skipped: a line closes only
with the commit that fixes it and its test. Owner names are the agents of this session.

| # | Source | Finding | Owner | Status |
|---|---|---|---|---|
| R1 | review 1 | Multi-topic offset reset failing part-way leaves no audit of moved topics | backend | fixed 41308015e |
| R2 | review 1 | Topic-config read returns a sensitive value if the broker sends it | backend | fixed cb7004da9 |
| R3 | review 1 | Bootstrap probe unbounded in count and time | backend | fixed 68051a558 |
| R4 | review 1 | Inline qualified names (Matcher etc.) | backend | fixed 68051a558 |
| R5 | review 2 | Sensitive new value and default unmasked in config preview, MCP and audit | backend | fixed cb7004da9 |
| R6 | review 2 | Truncate not pinned to its preview's etag | backend | fixed ab3d2bc78 |
| R7 | review 2 | Abort of a not-open or live transaction reaches the broker | backend | fixed 91942f2b5 |
| R8 | review 2 | ACL preview under-reports losses for prefixed and wildcard rules | backend | fixed 894921131 |
| R9 | review 2 | ACL audit has no real after-state | backend | fixed 3bd576f67 |
| R10 | review 2 | Truncate audit row left open on a non-broker failure; Cut not validated | backend | fixed ab3d2bc78 |
| R11 | review 3 | Plugin reservations checked on the first segment only; Kafka internal topics | backend | fixed a418db56d |
| R12 | review 3 | Bulk log purge reports the whole-cluster outcome on one node | backend | fixed a2431a3b1 |
| R13 | review 3 | Credential records print secrets in toString | backend | fixed 2540f02c8 |
| R14 | self-check | MCP message_action bypassed the log refusal | backend | fixed a2431a3b1 |
| E1 | live run | Unknown host reported as timeout | OpenBroker 0.7.0 + backend | open: OpenBroker PR 18 |
| E2 | live run | Closed port reported as timeout after 10 s, log flood | OpenBroker 0.7.0 | open: OpenBroker PR 18 |
| E3 | live run | Browse of a group's subscription answers 400 | backend | fixed 45e34f19c |
| E4 | live run | Destinations 502 on Kafka; other shared routes 5xx | backend | fixed b4ffc79f5 |
| E5 | live run | Purge dialog says destroy for a log purge; cap applied | backend + web | fixed 45e34f19c, cb23d46c6 |
| E6 | live run | Failed query retried in a loop | web | fixed |
| E7 | live run | "Open past timeout" is not Kafka's hanging transaction (KIP-664) | OpenBroker 0.7.0 + backend + web | open: web section, live check on 0.7.0 |
| E8 | live run | Advertised-listener failure titled "timed out" | backend | fixed 9a46f38f4 |
| E9 | live run | Old "Artemis Studio" product screenshots | web + site-story | fixed 34db7cd54, 21abc295f, 98637af38, cafe1729c |
| E10 | live run | Stale approval-provider fixture | web | fixed |
| S1 | storage | broker_event stores every delivered message | backend | open: ADR-0218 in test |
| S2 | storage | broker_event bounded in time but not rows: enforce a row cap | backend | moved to 19e4b |
| S3 | storage | metric_sample ≈45 GB/day at 4000 Kafka subscriptions | backend | moved to 19e4b |
| A1 | audit 1 | Cluster re-provisioned at the same bootstrap not detected | kafka-hardening | fixed d99cf67a7, 76ab9251d |
| A2 | audit 1 | Unavailable lag stored and shown as 0 | backend | open |
| A3 | audit 1 | Replaced broker's node row never retired | kafka-hardening | fixed 2a3a4fc9e |
| A4 | audit 1 | Health pages every partition on the caller's thread | kafka-hardening | fixed 315172918, 4fd49bfa6 |
| A5 | audit 1 | Plugin registration node rows orphaned on node delete | kafka-hardening | fixed b9a90596c |
| A6 | audit 1 | Health and partition readings never evicted | kafka-hardening | fixed a40cefb33 |
| A7 | audit 1 | Per-cluster meters never removed | kafka-hardening | fixed a40cefb33 |
| A8 | audit 1 | Whole ACL set loaded per preview | kafka-hardening | fixed 4d00b5f0a |
| A9 | audit 1 | Inline qualified names in NodeStateRecorder | kafka-hardening | fixed 2215dfc07 |
| A10 | audit 1 | Scrape reads subscriptions twice | backend | moved to 19e4b |
| B1 | audit 2 | One blackholed Kafka cluster stalls the scrape of all others | backend | fixed df042283b; the blackhole test stays with 19e4b |
| B2 | audit 2 | MCP describe-group and reset-preview unbounded (3000 partitions) | kafka-hardening | fixed 0aadb35f1 |
| B3 | audit 2 | Offset-reset audit row grows with partition count | kafka-hardening | fixed 5c797e316 |
| B4 | audit 2 | kafka_topic_config returns every entry | kafka-hardening | fixed 0aadb35f1 |
| P1 | OpenBroker | Driver per-topic and per-query reads (N+1) | OpenBroker 0.6.1 | fixed |
| P2 | OpenBroker | verify throws when a broker is cut | OpenBroker 0.6.1 | fixed |
| P3 | OpenBroker | At-most-once sender writes a message twice on a broker kill | OpenBroker 0.6.1 | fixed |
| C1 | audit 3 | Browse page has no total byte cap (500 × 1 MB values ≈ 1.2 GB heap) | kafka-hardening | fixed 925585cd5 |
| C2 | audit 3 | A message key has no encoding flag (text vs base64 indistinguishable) | kafka-hardening | fixed 925585cd5 |
| C3 | audit 3 | A streamed value is read whole before truncation | kafka-hardening | fixed 925585cd5 |
| C4 | audit 3 | A future start time or −1 timestamps give a silent empty page | kafka-hardening | fixed 925585cd5 |
| C5 | audit 3 | Inline java.time.Instant in MessageService | kafka-hardening | fixed 925585cd5 |
| C6 | audit 3 | An absent produce time shows as 1970-01-01 | kafka-hardening | fixed 925585cd5 |
| C7 | audit 3 | Bulk over 4000 subscriptions: item cap and bulk_run retention unverified | kafka-hardening | verified: cap 200 (BulkService, BulkPreviewTest) and 90-day retention (BulkRunStore, BulkRunStoreTest); no change needed |
| C8 | audit 3 | Held operations / approval requests: retention and orphans on cluster delete unverified | kafka-hardening | fixed 65359360d |
| C9 | audit 3 | Alert/notification flapping (a broker flapping every minute): dedup and cooldown unverified | kafka-hardening | fixed 15a090b03 |
| D1 | Dependabot | react-virtual 3.14.22 broke 40 DataTable tests (jsdom setup gave no clientHeight) | web | fixed dae5e7301 |
| D2 | Dependabot | Mantine 9.7.4 Menu.Item overwrote aria-disabled on unavailable actions | web | fixed dae5e7301 |
| E11 | live run | A reader without destination:read gets 404 for an existing topic's configuration, not 403 naming the permission | backend | moved to 19e4b |
| C10 | audit 3 | SSE payload bytes of one scrape tick over 4000 subscriptions unmeasured | backend (perf run) | open: measure in 6.2d |
| D3 | tests | McpApprovalGateTest failed on the branch: the purge tool asks the cluster's kinds, and the test registers no cluster | kafka-hardening | fixed 197aa3aff |
