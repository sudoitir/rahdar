# Screenshot sweep plan for 19e4-kafka (task 6.2)

Follows the `ui-visual-qa-loop` skill. Findings go in `qa/<area>.md` beside this file.

## Stack

```bash
# The demo's estate: six Artemis nodes and three Kafka brokers. Its own project and ports; never rahdar-dev.
just qa-up kafka 18080 demo=1
RAHDAR=http://127.0.0.1:18080 npm --prefix web run sweep -- --label before --only kafka,kafka-shared,onboarding --widths 1440,1920
just qa-down kafka
```

`qa-up` registers the Artemis cluster `demo` and the Kafka cluster `events` (`scripts/demo-seed.sh`, then
`scripts/qa-seed.sh`, both through `scripts/lib/kafka-seed.sh`) and saves the sessions. Without `demo=1` the stack has one
Kafka node (`kafka`, also reachable as `kafka-1`) and the same seed, with replication factor 1. `:kafka` in a route is
the id of `events`. The sweep widths for this change are 1440 and 1920, light and dark; add `1280` and `zoom` for the
final pass.

## Seeded states (live)

| State | Where it comes from |
| --- | --- |
| A group that keeps up, with members | `billing` on `orders` (a detached consumer) |
| An empty classic group far behind | `analytics` (read 200 records and left) |
| An empty group a little behind | `reporting` on `payments` |
| A share group | `fulfilment` (needs `share.version` finalised) |
| A group on the compacted topic | `state-reader` on `customer-state` |
| A compacted topic, a tombstone | `customer-state` (keys written three times, `c4` deleted) |
| ACLs: allow, allow, deny | `kafka-acls.sh` against the StandardAuthorizer |
| A hanging transaction | `payments-settlement`, the perf test killed mid-transaction |
| 200-character group and topic, 12 partitions | `kafka_seed_long` |
| A read-only account | `qa-reader` (the forbidden captures) |
| Registration refused as already registered; DNS and refused causes | real, against the stack |

## Stubbed answers (`-stub` in the capture id)

A live stack cannot hold these, so the sweep puts the API's own answer in place of the live one with `page.route` on
the real page: a check that timed out, was refused by TLS, authentication or authorisation, an unsupported version,
partial reach and a ZooKeeper cluster; a cluster with no authoriser (ACLs); a ZooKeeper quorum and a broker down
(brokers). Stopping `kafka-3` instead (`docker compose -p rahdar-qa-kafka stop kafka-3`) gives the live broker-down.

## Captures

Each route is captured in default, loading, error, empty, filtered-empty and forbidden where it has them, at 1440 and
1920, light and dark. Scenes are captured in the same widths and schemes.

| Area | Route and scenes |
| --- | --- |
| Estate | `home`: both systems in the clusters table and the subscriptions grid (Backlog column with estimates) |
| Registration | `register-choose` (system cards; empty systems); `register-kafka` scenes: servers-as-entries, server-with-scheme, sasl-fields, sasl-plaintext-warning, password-without-username, advanced-client-id, already-registered, cause-dns, cause-refused, checked-stub, partial-reach-stub, zookeeper-stub, cause-timeout-stub, cause-tls-stub, cause-authentication-stub, cause-authorization-stub, cause-unsupported-version-stub; `register-unknown-system`; `register` (Artemis) and `register-again` |
| Groups | `groups` (all six states), `groups-share-filter`, `groups-empty-state-filter` |
| Group | `group-active` (scene reset-blocked-by-members), `group-lagging` (scenes reset-dialog, reset-preview, reset-offset-needs-value, reset-offset-past-end), `group-empty`, `group-share` (reset disabled with its reason), `group-long-name`, `group-unknown` (404) |
| Topic configuration | `topic-config` (states; scenes change-dialog, review, review-rejected), `topic-config-compacted` |
| ACLs | `acls` (states; scenes add-dialog, add-invalid, review-removal, no-authorizer-stub) |
| Transactions | `transactions` (states; scene abort-dialog on the hanging one) |
| Brokers | `brokers` (states; scenes zookeeper-stub, broker-down-stub) |
| Subscriptions on Kafka | `subscriptions` (states; scenes backlog-reason, backlog-drawer, bulk-purge-preview: the active group refused naming its members) |
| Truncate, purge | `destinations` (scenes truncate-dialog, truncate-preview, truncate-compacted-refused); `messages-orders` scene purge-refused-members |
| Browse | `messages-orders` (default, loading, error, forbidden; scenes from-offset, from-time, offset-past-end, selector-disabled-actions, record-open), `messages-tombstone`, `messages-long-name` |

## Not covered by a live capture

- The run-time refusal of a purge by a group that began rebalancing, and a held (approval) offset reset or truncate:
  both need the approval provider or a timing the seed cannot hold; check them in the browser tests.
- A second Kafka cluster that is unreachable: the unreachable state is the broker-down stub.
- The scenes were written against the screens' roles and labels and have not been run: the stack was not started
  (disk), so the first run will find the labels and waits that need fixing.
