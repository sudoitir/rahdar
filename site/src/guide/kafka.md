---
title: Apache Kafka
description: What Rahdar shows and does for a Kafka cluster - consumer groups and lag, offset reset, topic configuration, ACLs, transactions and brokers - and how the shared screens behave on a partitioned log.
---

# Apache Kafka

Register a Kafka cluster as described in the [Quickstart](/guide/quickstart#register-a-cluster) and it joins the
estate beside any Artemis clusters, with health judged on the same scale. Its own screens are in the cluster's
navigation, and appear only for a Kafka cluster. The screens every system shares work on Kafka too, with
the differences below. Which Kafka releases and modes are supported is on
[Supported broker versions](/guide/supported-versions).

Rahdar's words for Kafka follow the same vocabulary as everywhere else: a **topic** is a destination, and a
**consumer group reading one topic** is a subscription. Every kind carries its native name, so a row says
"Topic" or "Group on topic" rather than a neutral word.

## Consumer groups

**Messaging → Consumer groups** lists every group with its type (classic, consumer or share), its state
(stable, empty or rebalancing), its members and its lag, read a page at a time. Filter by name, type or state.

A group's page shows, topic by topic, every partition: the offset the group committed, the end of the
partition and the lag between them, then the members with their client ids, hosts and assigned partitions.

**Lag is an estimate, and says so.** A group's lag is the sum over its partitions, and a partition the group has
not committed to, or one the account may not read, has no lag to add. The figure is then shown as
**≈ 12 400** with "estimate" in its accessible name; open it to read the reason and, where the system has a
breakdown, **Show partitions**, which lists the partitions slowest first with the age of the sample. A lag that
cannot be read at all is shown as **Unavailable**, with its reason, and never as 0. A topic that was deleted
while the group still holds offsets for it is called gone.

### Reset offsets

**Reset offsets…** moves where a group reads next. It is offered to people with `subscription:seek`, and is
disabled, with the reason, for a group whose offsets cannot be changed (a share group has none).

1. Choose the topics (all by default) and where to move: the earliest offset, the latest, a time, or an offset.
2. **Preview the reset.** Each partition shows where it is now, where it would move to, how many records the
   group would skip (moving forward) and how many it would read again (moving back).
3. Confirm by pressing and holding the button, which names the number of partitions.

Only an **empty** group can be reset. A group that still has members, or is rebalancing, shows that in the
preview, names the members, and leaves the confirmation disabled with the reason. The reset runs against the
state the preview read: if the group changed since (a member joined, an offset moved), it is refused as stale
and you preview again. An offset out of range for a partition is refused, naming the partition. Where an
approval gate is on, the reset is held until it is approved, and re-checks the group when it runs. Every reset
is audited with the offsets before and after, so an operator can reset back.

## Topic configuration

From a topic's row menu, **Configuration** opens its settings with the value in force, where it comes from
(set on this topic, a broker default, the broker's file, or Kafka's default), and the default. A **sensitive**
value is never sent to the browser. A read-only setting says so.

Change as many settings as you need (**Change**, or remove a topic's override to fall back to the default),
then **Review changes**. The review shows each setting before and after, what a removed setting falls back to,
and the broker's own validation of every value; a value it rejects blocks the apply. **Apply** runs against the
state the review read and needs `destination:update`.

## ACLs

**Configuration → ACLs** lists the cluster's access control entries: principal, permission (allow or deny),
operation, resource type, name and how the name matches. A cluster without an authoriser says so, with the
broker's reason, instead of showing an empty list.

Add entries (**Add an ACL**) and remove them (**Remove**), then **Review changes**: the review shows the
effective access of each affected principal before and after, says in words what would be lost ("User:app loses
READ on topic orders"), and notes that a DENY overrides an ALLOW. The apply is confirmed by pressing and
holding when it removes access, and needs `access-rule:write`; reading needs `access-rule:read`.

## Transactions

**Activity → Transactions** lists the transactional producers with their state, timeout and the partitions a
transaction holds back. One that is past its timeout is flagged at the top: the coordinator aborts it by
itself, and until it does it blocks those partitions. **Abort…** previews what the abort does, names the
partitions it unblocks and that the producer is fenced, and is confirmed by holding. It needs `transaction:abort`.

Below that list, **Hanging transactions** looks only when you ask, and only for an open transaction the
coordinator does not know. No timeout ends that one, so it holds back every consumer that reads committed
records. The page header says which of the two a sentence is about.

## Brokers and the quorum

**Observe → Brokers** lists each broker with its address, rack, whether Rahdar reached it, the partitions it
leads, and its under-replicated and offline partitions. In KRaft mode the **controller quorum** shows the
leader, the voters, their lag and the majority. In ZooKeeper mode there is no quorum to read: the page says it
is unknown, and the cluster's health states that instead of counting it as healthy.

## The shared screens on Kafka

### Browsing messages

A destination or group on a topic is read from **one partition**, from a start you choose: the earliest record,
the latest, an **offset**, or a **time**. The address holds the browse, so it can be shared. The page says when
the offset is past the end of the log (and where the log ends), and when retention removed the start of the
partition, so the browse began later than you asked. Records of a transaction that is still open or was
aborted are not shown. A record with a key and no value is a **tombstone** and says so. The message selector is
disabled with its reason: Kafka cannot select records on the broker.

### Purge and truncate

Kafka keeps records by retention, not by consumption, so the two ways to remove them differ:

- **Purge** (`subscription:purge`) on a group on a topic empties what that one group has not read. The preview
  names the members or the rebalance that make the system refuse it, and the confirmation stays disabled with
  that reason.
- **Truncate** (`destination:truncate`) on a topic deletes records before a cut for **every** reader: an offset
  per partition, or a time. The preview says how many records go and lists every group that would lose records
  it has not read, with the count. A compacted topic refuses it, with the broker's reason. It is confirmed by
  holding a button that names the count, is held for approval where the gate is on, and is audited with the
  log start offsets before and after.

### Per-record actions

A Kafka record is immutable and shared, so moving, copying, retrying and deleting one record cannot be done.
Those actions stay visible and are disabled with the system's reason on a row, on a selection and by selector,
and the API refuses them with the same reason. The same holds for any action the system's kind does not have.

## Permissions

| Permission | Allows |
| --- | --- |
| `subscription:seek` | Reset a group's offsets. |
| `destination:update` | Change a topic's configuration. |
| `destination:truncate` | Truncate a topic. |
| `access-rule:read` | List ACLs. |
| `access-rule:write` | Add and remove ACLs. |
| `transaction:read` | List transactions. |
| `transaction:abort` | Abort a transaction. |

See [Teams and permissions](/guide/teams-and-permissions) for how roles carry them.

## How large a cluster this covers

Measured on 2026-10-30 with Rahdar `2026.10.65` (`506a77d0`), one Kafka broker, 208 topics, 3,079 partitions
and 311 consumer groups (350 group-on-topic series). The machine, the method and every raw figure are in the
sizing guide.

At that size the Kafka lists, a group on 40 topics, an offset-reset preview, a topic's configuration, the ACL
list, transactions, brokers, a browse of 50 records and a backlog breakdown all answer within half a second
at the 95th percentile. A list asked for more rows than it will return stops at 500, and the body stays under
1 MiB. The scrape reads each cluster's nodes once a cycle and its subscriptions once a cycle, and reads
partitions as one batch at most every 30 seconds. A pass finishes inside its interval.

Metric history for a group on a topic is one sample a minute for each of six series, whether or not records
are being produced. An hour at 1,000 records a second stored 123,510 of those rows; a quiet hour stored
123,900. Both are under the ceiling of one row per metric per series per minute (126,000). The broker-event
store gained no Kafka rows in that hour. The live update for the subscription list is a signal about the
cluster once the names would pass 100. The steady read's longest frame was 18 bytes, and the longest frame
observed on this build was 549 bytes, both under the 8,192 byte budget.
