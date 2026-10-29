# README screenshot set (19e4-kafka)

The README, `README.zh.md`, `README.fa.md` and `docs/dockerhub.md` show seven screens that tell the
multi-broker story. Each is captured in light and dark from the live demo estate and shown on GitHub with
`<picture>` and `prefers-color-scheme`; Docker Hub, which does not honour `<picture>`, shows the light one.

## Capture

- Stack: `just qa-up kafka 18080 demo=1` (the demo's Artemis cluster `demo` and the three-broker Kafka
  cluster `events`, seeded by `scripts/demo-seed.sh` and `scripts/qa-seed.sh`). Let the demo traffic run a few
  minutes first, so lag, depth and rates are non-zero and the hanging transaction exists.
- Viewport **1440 × 900**, device scale factor **2**, light and dark, the default density, no zoom.
- Crop: the viewport (not the full page), unless a row says otherwise. No toast, no hover, no focus ring
  left on screen.
- Output: `openspec/changes/19e4-kafka/qa/captures/readme/<id>-<light|dark>.png`. The site story converts
  them to WebP at 1600 wide into `docs/img/readme/<id>-<light|dark>.webp`.
- Live data only, except where a row says stub. A row that could only be captured as a stub says so in the
  hand-back, and the caption is checked again.

## The set

| # | id | Route (sweep id) and state | What must be visible |
| --- | --- | --- | --- |
| 1 | `estate` | `/` (`home`), default | The clusters table with `demo` (Apache Artemis) and `events` (Apache Kafka) and their health; the subscriptions grid sorted by Backlog descending, with at least one Kafka group showing **≈ lag** marked as an estimate and at least one Artemis queue. |
| 2 | `subscriptions` | `/clusters/:kafka/subscriptions` on `events` (`subscriptions`), scene `backlog-drawer` | Rows labelled "Group on topic" (and "Share group on topic" if seeded) with the Backlog column, and the open breakdown: every partition slowest first, committed offset, end offset, lag and the sample's age. |
| 3 | `kafka-group` | `/clusters/:kafka/kafka/groups/analytics` (`group-lagging`), default | The group's type and state, its lag per partition topic by topic, and the members section (empty for `analytics`, which is the group the reset needs). |
| 4 | `offset-reset` | same route, scene `reset-preview` (to a time or to latest) | The preview table: each partition's current and new offset, records skipped, and the hold-to-confirm button naming the partition count. |
| 5 | `transactions` | `/clusters/:kafka/kafka/transactions` (`transactions`), scene `abort-dialog` on `payments-settlement` | The hanging transaction flagged at the top, and the abort preview naming the partitions it unblocks and that the producer is fenced, with the hold button. |
| 6 | `register-kafka` | `/clusters/new?system=kafka` (`register-kafka`), bootstrap `kafka-1:9092`, plaintext, **Check connection** passed live | The check result: version and mode (KRaft), each of `kafka-1`..`kafka-3` with its advertised address and "reached", the controller quorum, and what the account may do. Use the live check; `checked-stub` only if the live one cannot pass, and say so. Crop to the page content if the result is taller than the viewport (full page, max 1800 px tall at scale 1). |
| 7 | `artemis-topology` | `/clusters/:cluster/topology` on `demo` (`topology`), default | The live/backup pairs with HA roles polled from every node, the replication state, and one stopped node as the demo has it. |

## Captions and alt text

| id | Caption (one line) | Alt text |
| --- | --- | --- |
| `estate` | Every cluster in one estate: Artemis and Kafka side by side, the worst backlog first. | The estate with an Apache Artemis cluster and an Apache Kafka cluster and their health, above one table of subscriptions sorted by backlog, where Kafka lag is marked as an estimate. |
| `subscriptions` | Kafka lag is an estimate, and opens to every partition, slowest first. | The subscriptions table on a Kafka cluster with a group's backlog breakdown open: each partition's committed offset, end offset and lag, slowest first, with the sample's age. |
| `kafka-group` | A consumer group's lag per partition and its members. | A Kafka consumer group's page: its type and state, lag per partition topic by topic, and its members. |
| `offset-reset` | An offset reset is previewed per partition before it is held to confirm. | The reset-offsets preview: each partition's current and new offset and the records skipped, with a press-and-hold button that names the partition count. |
| `transactions` | A hanging transaction, found and aborted with its reach stated first. | The transactions page with a hanging transaction flagged, and its abort preview naming the partitions it unblocks and that the producer is fenced. |
| `register-kafka` | Registration checks every broker on the address it advertises. | The Kafka registration page after a passed check: the version and mode, every broker with its advertised address reached, and the controller quorum. |
| `artemis-topology` | Artemis topology, with HA roles polled from every node. | The Artemis topology: live and backup pairs with their replication state, one node stopped. |
