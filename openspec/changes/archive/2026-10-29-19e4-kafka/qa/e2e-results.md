# 6.2c: the Kafka end-to-end run

Run 2026-10-29T08:08:03.864Z against http://127.0.0.1:18080 (the isolated QA stack, three Kafka brokers, six Artemis nodes).
Each effect was read back from the broker with the Kafka image's own tools.

| Flow | Result |
| --- | --- |
| prepare: the seeded states the flows act on: records to skip, an active group billing, a hanging transaction | pass |
| estate: the estate shows both systems, Kafka health and a backlog column with estimates | pass |
| register: registering a Kafka cluster: each real failure cause, then a successful registration of a fourth broker | pass |
| groups: the groups list and a group page agree with the broker | pass |
| reset: an offset reset on an empty group: preview, hold, run; the committed offsets move on the broker and the audit row records them | pass |
| reset-refused: an offset reset is refused on an active group, naming its members, and nothing moves | pass |
| topic-config: a topic configuration change reaches the broker; an invalid value is refused by the broker's own validation; the override can be removed | pass |
| acls: an ACL is added and removed through the review; the broker's ACL list changes each time | pass |
| truncate: truncating a topic moves its log start on the broker and the groups that lose unread records are named | pass |
| purge: purging an empty group moves its committed offsets to the end on the broker | FAIL |
| bulk-purge: a bulk purge of a mixed selection refuses the active group naming its members and acts on the empty ones | FAIL |
| backlog-drawer: the backlog of a group is marked as an estimate, says why, and its partition breakdown matches the broker | pass |
| held-reset: with the approval gate on and two approvers, an offset reset by the requester is held, runs only once another approver approves, and then moves the offsets on the broker | pass |
| held-truncate: a truncate by the requester is held, and cuts the log only once the second approver approves | pass |
| browse: browsing a group's records by partition, offset and time shows what the broker holds, with the group's own position | FAIL |
| tombstone: a tombstone on a compacted topic is shown as a tombstone, and every per-record action is disabled with the broker kind's reason | pass |
| hanging-find: the hanging transactions section looks on demand, and says what it found: the coordinator knows the open transaction, so none is hanging | pass |
| abort: aborting the hanging transaction releases the partitions it held: a read-committed consumer sees what was written after it | FAIL |

## prepare: the seeded states the flows act on: records to skip, an active group billing, a hanging transaction

- pass: billing has 1 member on the broker
- pass: payments-settlement is hanging
- pass: reporting is placed at offset 100 of payments-0

## estate: the estate shows both systems, Kafka health and a backlog column with estimates

- pass: the clusters table lists demo and events: "Name System Environment Health Nodes Updated demo Apache ActiveMQ Artemis None Degraded 6 2026-10-29 08:04:19 +01:00 events Apache Kafka None Healthy 3 2026-10-"
- pass: the System column names Apache Kafka and Apache ActiveMQ Artemis
- pass: events health is OK per the API (OK)
- pass: the subscriptions grid lists the Kafka group's subscription beside Artemis ones: "Backlog Cons. State Figures Actions demo ORDERS.events.billing Queue O RDERS.events ORDERS.events 708 1 current events billing Group on topic orders ≈ 0 , estimate, why 1 partial"
- pass: the subscriptions grid has a Backlog column
- pass: a Kafka backlog is marked as an estimate (a button naming it)
- note: API health: demo=DEGRADED, events=OK

## register: registering a Kafka cluster: each real failure cause, then a successful registration of a fourth broker

- pass: an unknown host is reported as a DNS cause
- pass: Register stays disabled after a failed check
- pass: a closed port is reported as refused
- pass: the running cluster is reported as already registered
- pass: Register is disabled for an already registered cluster
- pass: the check of the fourth broker passes and names it:  Version Apache Kafka at least 4.4 A lower bound: the cluster reports no exact version. Mode KRaft Cluster id 5L6g3nShT-eMCtK--X86sw Controller quorum 1 voters, leader is broker 1 Columns Node Address Reach Broker 1 broker, controller kafka-solo:9092 Reached What this account cannot do management.write (not established): Not tried: the first real call settles it. messages.browse (not established): Not tried: the first real call settles it. messages.copy (unavailable): Kafka's log is append-only:
- pass: Rahdar lists "solo" as Kafka with one node ({"s":"kafka","n":1,"h":"OK"})
- pass: the broker itself answers on kafka-solo:9092 (kafka-broker-api-versions)
- pass: the registration is removed again (204)
- pass: the check names the advertised address it could not reach (localhost:9092)
- pass: the broker that cannot be reached on its advertised address is not shown as reached
- note: DNS:  The name does not resolve no-such-host.invalid:9092 does not resolve. Next: Check the bootstrap server's host name resolves from Rahdar. Version Not read: no node answered Mode Not reported Cluster id Not reported Controller quorum Not read Columns Node Address Reach No node was found The check reached no node. 
- note: refused:  The connection was refused kafka-1:9999 refuses connections. Next: Check the host and port, that the broker listens there and that no firewall blocks it. Version Not read: no node answered Mode Not reported Cluster id Not reported Controller quorum Not read Columns Node Address Reach No node was found The check reached no node. 
- note: already:  This cluster is already registered It is the same cluster as events, so registering it again would show the same brokers twice. Open events Version Apache Kafka at least 4.4 A lower bound: the cluster reports no exact version. Mode KRaft Cluster id rahdar-demo-kafka-001 Controller quorum 3 voters, leader is broker 2 Columns Node Address Reach Broker 1 broker, controller kafka-1:9092 Reached Broker 2 broker, controller kafka-2:9092 Reached Broker 3 broker, controller kafka-3:9092 Reached What this account cannot do management.write (not established): Not tried: the first real call settles it. messages.browse (not established): Not tried: the first real call settles it. messages.copy (unavail
- note: unreachable advertised listener:  The brokers advertise addresses Rahdar cannot reach Brokers advertise addresses Rahdar cannot reach. The call's deadline passed Next: The bootstrap server answered, but the brokers advertise addresses Rahdar cannot reach. Set advertised.listeners to an address reachable from Rahdar (not localhost inside a container). Version Not read: no node answered Mode Not reported Cluster id Not reported Controller quorum Not read Columns Node Address Reach No node was found The check reached no node. 

## groups: the groups list and a group page agree with the broker

- pass: the page lists analytics
- pass: the page lists billing
- pass: the page lists reporting
- pass: the page lists fulfilment
- pass: the page lists state-reader
- pass: the page tells share from classic groups
- pass: the list shows the lag 5,600 the broker computes
- pass: partition 0 shows the committed offset 0 the broker reports
- pass: partition 1 shows the committed offset 0 the broker reports
- pass: partition 2 shows the committed offset 200 the broker reports
- pass: partition 3 shows the committed offset 0 the broker reports
- pass: partition 4 shows the committed offset 0 the broker reports
- pass: partition 5 shows the committed offset 0 the broker reports
- note: broker lists 5 groups; page: Group Type State Members Lag Changes analytics Classic Empty 0 ≈ 5,600 , estimate, why Allowed billing Classic Stable 1 ≈ 0 , estimate, why Allowed fulfilment Share Stable 1 ≈ 0 , estimate, why Allowed qa.consumer-group-segment-01.consumer-group-segment-02.consumer-group-segment-03.consumer-group-se
- note: broker lag of analytics on orders: 5600

## reset: an offset reset on an empty group: preview, hold, run; the committed offsets move on the broker and the audit row records them

- pass: the preview states how many records the group will skip
- pass: nothing moved on the broker before the hold (the preview is a dry run)
- pass: the confirmation is armed by the preview
- pass: the broker now holds every partition at its end offset
- pass: the committed offsets changed on the broker
- pass: an audit row records the reset (RESET_GROUP_OFFSETS, SUCCESS)
- pass: the audit row carries the before and after offsets
- pass: the group page shows no lag on any partition
- note: preview: Moves where analytics will read next. Records it skips are not read by this group; records it reads again are processed twice. Only an empty group can be reset. Topics orders Move to Preview the reset Moves 6 partitions: skips 5,600 records and reads 0 records again. Columns Topic Partition Now at M
- note: before {"0":0,"1":0,"2":200,"3":0,"4":0,"5":0}; after {"0":541,"1":748,"2":1207,"3":777,"4":618,"5":1909}; log ends {"0":541,"1":748,"2":1207,"3":777,"4":618,"5":1909}
- note: audit: {"id":456,"parentId":null,"ts":"2026-10-29T07:35:03.035809Z","username":"admin","sourceIp":"172.22.0.1","requestId":"5356152e-53e3-4452-82ab-2c0f807748b9","action":"RESET_GROUP_OFFSETS","targetType":"SUBSCRIPTION","targetName":"analytics","affectedCount":6,"outcome":"SUCCESS","dryRun":false,"params":"{\"to\": \"latest\", \"value\": \"\", \"before\": {\"orders/0\": 0, \"orders/1\": 0, \"orders/2\": 200, \"orders/3\": 0, \"orders/4\": 0, \"orders/5\": 0}, \"planned\": {\"orders/0\": 541, \"orders/1\": 748, \"orders/2\": 1207, \"orders/3\": 777, \"orders/4\": 618, \"orders/5\": 1909}, \"approval\": {\"policy\": \"four-eyes@1\", \"provider\": \"qa-approvals\"}}","error":null,"clusterName":"events","nodeId":null}

## reset-refused: an offset reset is refused on an active group, naming its members, and nothing moves

- pass: the dialog says the group cannot be reset right now
- pass: the reset confirmation stays disabled
- pass: nothing moved on the broker
- note: dialog: Preview the reset This group cannot be reset right now The group has 1 active member: console-consumer@/172.22.0.7 (console-consumer-2e6c4b95-4050-4e81-9d4f-55bbf1e2ca93). Stop them first. The group has 1 active member: console-consumer@/172.22.0.7 (console-consumer-2e6c4b95-4050-4e81-9d4f-55bbf1e2ca93). Stop them first. Reset 6 partitions Press and hold to confirm. Cancel

## topic-config: a topic configuration change reaches the broker; an invalid value is refused by the broker's own validation; the override can be removed

- pass: inventory has no retention.ms override to begin with
- pass: the review shows the new value as accepted
- pass: nothing changed on the broker at the review
- pass: the broker shows the override:   retention.ms=86400000 sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:retention.ms=86400000}
- pass: the review states the broker refused the value
- pass: Apply stays disabled for a refused value
- pass: the broker did not take the invalid value
- pass: the broker no longer lists the override after it is removed
- note: review: Apply 1 change to inventory Columns Setting Before After Check retention.ms 604800000 86400000 Accepted Cancel Apply 1 change
- note: invalid review: Apply 1 change to inventory Columns Setting Before After Check cl eanup.policy cleanup.policy delete bogus The cluster refused a setting: Invalid value bogus for configuration cleanup.policy: String must be one of: compact, delete The system rejects at least one change. Fix it, then review again. Ca

## acls: an ACL is added and removed through the review; the broker's ACL list changes each time

- pass: no ACL for User:e2e to begin with
- pass: the review names the principal and the access it gains
- pass: nothing changed on the broker at the review
- pass: the broker lists the new ACL: ACLs for principal `User:e2e` Current ACLs for resource `ResourcePattern(resourceType=TOPIC, name=inventory, patternType=LITERAL)`: (principal=User:e2e, host=*, operation=READ, permissionType=ALLOW) 
- pass: the broker no longer lists the ACL after its removal
- note: review: Apply 1 ACL change Columns Principal Resource Before After User:e2e top ic inventory topic inventory None DESCRIBE, READ Cancel Apply 1 change
- note: removal review: Apply 1 ACL change Access that would be lost User:e2e loses DESCRIBE on topic inventory User:e2e loses READ on topic inventory Columns Principal Resource Before After User:e2e top ic inventory topic inventory DESCRIBE, READ None Apply 1 change Apply 1 change Press and hold to confirm. Cancel

## truncate: truncating a topic moves its log start on the broker and the groups that lose unread records are named

- pass: the preview states how many records go
- pass: the preview names the group that would lose unread records (reporting)
- pass: the log start has not moved before the hold
- pass: the broker's log start of partition 0 moved from 0 to 177
- pass: the preview stated 77 unread records lost for reporting, the cut (1150) minus its committed offset (1073)
- pass: an audit row records the truncate (TRUNCATE_DESTINATION, SUCCESS)
- note: truncate preview (cut 0:177): This removes 177 records. Columns Group Lost reporting 77 Truncate 177 records Truncate 177 records Press and hold to confirm. Cancel
- note: log start before {"0":0,"1":0,"2":0}, after {"0":177,"1":0,"2":0}; reporting lag 2698 -> 2698

## purge: purging an empty group moves its committed offsets to the end on the broker

- pass: reporting lags 2698 records on the broker before
- **FAIL**: the dialog states NaN records to skip, of the 2698 the group lags
- pass: the dialog does not call the count unavailable or unknown while it states it (it says: "How many messages that destroys is unavailable ... a figure Rahdar does not know")
- pass: nothing moved before the hold
- **FAIL**: the dialog says no record is deleted, and never destroy or cannot be undone
- pass: no safety cap applies to a purge that deletes nothing
- **FAIL**: the broker's lag of reporting fell from 2698 by the NaN the dialog stated and the 77 a truncate had removed, to 2125
- pass: an audit row records the purge (PURGE_SUBSCRIPTION, SUCCESS)
- note: purge dialog: Purge the selected subscriptions Reading what these subscriptions hold
- note: audit: {"id":464,"parentId":463,"ts":"2026-10-29T07:41:22.475460Z","username":"admin","sourceIp":"172.22.0.1","requestId":"102db889-7bab-4f62-90be-256c614ffe87","action":"PURGE_SUBSCRIPTION","targetType":"SUBSCRIPTION","targetName":"kafka.group-topic/reporting/payments","affectedCount":573,"outcome":"SUCCESS","dryRun":false,"params":null,"error":null,"clusterName":"events","nodeId":null}

## bulk-purge: a bulk purge of a mixed selection refuses the active group naming its members and acts on the empty ones

- pass: analytics (300) and reporting (2425) have new records to skip
- pass: the preview refuses billing and names its active member
- pass: the preview lists the empty groups as acting
- **FAIL**: the broker shows analytics at 0 and reporting at 2425 (from 2425; the records above the open transaction stay to read)
- note: bulk dialog: Purge the selected subscriptions Moves 4 groups to the end of the log, skipping 5,250 records. No record is deleted; the groups will not read the records they skip. 2 subscriptions are refused and will not be touched. The reason is beside each one below. Continue past failures The run stops at the first subscription that fails, and the subscriptions after it are skipped. Skip 5,250 records for 4 groups Skip 5,250 records for 4 groups Press and hold to confirm. The subscriptions in this run Only subscriptions with a refusal or warning Columns Subscription Kind Messages Nodes Plan Reason or warning anal ytics/orders analytics/orders Group on topic 300 skipped 1 will act Purging moves the position to the end of the log, skipping 300 records. The records themselves are not deleted and others can still read them. bi lling/orders billing/orders Group on topic unknown 1 refused 1 members are at
- note: result page: http://127.0.0.1:18080/clusters/01a18675-949f-7d8f-95a0-b7f63809a868/bulk/01a1869c-270b-7877-9b54-5a94f942ff6f events › Activity › Bulk runs 3 nodes · standalone · reachable Purge 6 subscriptions Started by admin at 2026-10-29 08:42:26 +01:00, stopping at the first failure Audit trail for this run Outcome Partial: some subscriptions succeeded and some did not This run finished. 4 succeeded, 0 failed, 2 skipped, 6 of 6 subscriptions settled. Subscriptions Columns Subscription Kind Outcome Messages Reason anal ytics/orders analytics/orders Group on topic succeeded 300 skipped Purging moves the position to the end of the log, skipping 300 records. The records themselves are not deleted and others can still read them. bi lling/orders billing/orders Group on topic refused unknown 1 members are attached. A

## backlog-drawer: the backlog of a group is marked as an estimate, says why, and its partition breakdown matches the broker

- pass: analytics lags 500 records on the broker
- pass: the backlog is written with an approximation sign
- pass: the popover says why it is an estimate
- pass: partition 0 shows the lag 0 the broker computes
- pass: partition 1 shows the lag 0 the broker computes
- pass: partition 2 shows the lag 235 the broker computes
- pass: partition 3 shows the lag 29 the broker computes
- pass: partition 4 shows the lag 0 the broker computes
- pass: partition 5 shows the lag 236 the broker computes
- note: backlog button: null / ≈ 500 , estimate, why
- note: reason: The end offset moves while it is read, and transaction markers and compaction leave offsets that hold no record, so the lag can overcount. Show partitions
- note: drawer: Backlog of analytics Sampled 0s ago (2026-10-29 08:43:29 +01:00) . The slowest partitions come first. Columns Partition Lag Committed End 5 236 1,909 2,145 2 235 1,300 1,535 3 29 777 806 0 0 541 541 1 0 955 955 4 0 618 618

## held-reset: with the approval gate on and two approvers, an offset reset by the requester is held, runs only once another approver approves, and then moves the offsets on the broker

- pass: the gate is enforcing with 2 approvers
- pass: the requester is told the reset awaits approval
- pass: nothing moved on the broker while the request is held
- pass: the held request is listed with the group named
- pass: the committed offsets moved on the broker after the approval
- note: requester sees: ted An empty group has no consumer attached. Its offsets can be reset. Sent for approval Reset offsets of group analytics to latest on events waits for a second person. View request
- note: held request: {"id":"01a1869d-da82-7d44-b932-b0b0180ab845","type":"subscription.seek","state":"HELD","summary":"Reset offsets of group analytics to latest on events","requesterId":"01a18691-0f0d-74b1-ab94-cb8764153a89","requesterUsername":"qa-requester","authKind":"SESSION","clusterId":"01a18675-949f-7d8f-95a0-b7f63809a868","requestedAt":"2026-10-29T07:44:14.666665Z","expiresAt":"2026-10-30T07:44:14.666665Z","approverUsername":null,"decidedAt":null,"finishedAt":null}
- note: approval page (qa-approver): Open a cluster › Approvals Loading the request
- note: approve dialog: Approve this request? Reset offsets of group analytics to latest on events Effect 6 partitions Cluster events Group analytics Topics all Reset to latest Your reason None given Rahdar runs it once, as qa-requester asked, as soon as you confirm. Approve Approve Press and hold to confirm. Cancel
- note: before {"0":541,"1":955,"2":1300,"3":777,"4":618,"5":1909} after {"0":541,"1":955,"2":1535,"3":1051,"4":773,"5":2145}

## held-truncate: a truncate by the requester is held, and cuts the log only once the second approver approves

- pass: the requester is told the truncate awaits approval
- pass: the log start has not moved while held
- pass: the held truncate is listed with the topic named
- pass: the broker log start of partition 0 is 60 after the approval
- note: held request: {"id":"01a1869e-d1c9-79af-b4ab-71500a11bc4e","type":"destination.truncate","state":"HELD","summary":"Truncate inventory on events","requesterId":"01a18691-0f0d-74b1-ab94-cb8764153a89","requesterUsername":"qa-requester","authKind":"SESSION","clusterId":"01a18675-949f-7d8f-95a0-b7f63809a868","requestedAt":"2026-10-29T07:45:17.791250Z","expiresAt":"2026-10-30T07:45:17.791250Z","approverUsername":null,"decidedAt":null,"finishedAt":null}
- note: log start before {"0":0,"1":0,"2":0} after {"0":60,"1":0,"2":0}

## browse: browsing a group's records by partition, offset and time shows what the broker holds, with the group's own position

- **FAIL**: the page says where the group stands on the partition
- pass: the broker holds a record at offset 40 of partition 0
- pass: the browse starts at offset 40
- pass: the first row is the offset the broker resolves that time to
- pass: an offset past the end is stated
- note: group position on the page: undefined
- note: broker offset 40: {"off":40,"ts":1793257278350}
- note: a time of 2026-10-29T07:01:18.351Z: broker resolves offset 41; page first row 41

## tombstone: a tombstone on a compacted topic is shown as a tombstone, and every per-record action is disabled with the broker kind's reason

- pass: the broker holds a tombstone for key c4 (null value)
- pass: a record is marked as a tombstone
- pass: every move, retry, delete and expire is disabled
- pass: each says why in the system's own words
- note: tombstone on partition 2: ":3} 8 2026-10-29 08:02:00 +01:00 c4 25 {"tier":"gold","round":3} 9 2026-10-29 08:02:07 +01:00 c4 0 Tombstone (no value)
- note: record menu: [{"text":"Open details","disabled":false},{"text":"Copy message id","disabled":false},{"text":"Copy link to this message","disabled":false},{"text":"Move… Messages cannot be moved out of Apache Kafka groups on topics.","disabled":true},{"text":"Retry… Messages cannot be retried from Apache Kafka groups on topics.","disabled":true},{"text":"Delete… Messages cannot be deleted from Apache Kafka groups on topics.","disabled":true}]

## hanging-find: the hanging transactions section looks on demand, and says what it found: the coordinator knows the open transaction, so none is hanging

- pass: before Find the section says nothing has been looked for, and has not asked the broker
- pass: Find answers 200 on OpenBroker 0.7.0
- pass: it says there are no hanging transactions
- note: A transaction the coordinator does not know (KIP-664) could not be created on this stack: the transaction log cannot be truncated (delete-records on __transaction_state is refused as an invalid topic), so only the empty answer was verified live; the found, abort and stale paths are covered by the MSW tests and the sweep stubs.

## abort: aborting the hanging transaction releases the partitions it held: a read-committed consumer sees what was written after it

- pass: payments-settlement is hanging per the API ({"id":"payments-settlement","state":"ongoing","startedAt":"2026-10-29T07:03:07.679Z","timeoutMs":60000,"hanging":true,"blocked":[{"topic":"payments","partition":0},{"topic":"payments","partition":1},{"topic":"payments","partition":2}]})
- pass: a read-committed consumer does not see 9 of 9 records written after the open transaction began
- note: broker: 2            	payments-settlement	1         	0            	Ongoing         	60000               	1793257387679                	2741960              	payments-0,payments-2,payments-1
- **ERROR**: page.goto: Page crashed

## Console errors and failed requests

None.
