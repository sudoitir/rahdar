---
title: Message transfer
description: Move or copy messages from a queue to a queue on another node or another cluster — by id, by selector or the whole queue — with the target checked before anything moves, and a run that can be stopped, resumed or returned.
---

# Message transfer

Artemis can move messages between queues on one broker. It cannot move them to a queue on
*another* broker: that is a bridge you declare in `broker.xml`, which keeps running, or a
consumer you write. Neither is what an operator wants at 3am, when the answer is
"these 12,000 messages belong on the other cluster, once".

Message transfer is that once. It moves or copies a selection of messages from a queue to a
queue on another node of the same cluster or on a different registered cluster, as a run you
can watch, stop, resume and undo.

## What it can do

| | |
|---|---|
| **Move** | The messages leave the source queue and arrive on the target. |
| **Copy** | The source queue is untouched; the target gets a faithful copy. |
| **Redistribute to a node** | A move to the *same* queue name on another live node of the same cluster — for when a clustered queue's backlog has collected on one node. |

The selection is whatever the Messages screen has in hand: the rows you ticked, every message
matching the selector in the filter box, or the whole queue.

## How a move keeps the messages safe

A move is not a read-then-send. Rahdar never holds the only copy of a message:

1. **Staging.** The source broker moves the selection, in chunks, into a durable queue it owns:
   `rahdar.transfer.<run id>`. That move is the broker's own atomic operation. The messages have
   left the source queue and are still on the source broker.
2. **Relay.** Rahdar takes a batch out of staging over the Core protocol and sends it to the
   target inside a transaction. The target transaction commits first, then the source
   acknowledgement. A crash between the two redelivers the batch — and the target's
   duplicate-id cache drops what it already has, because every message carries
   `_AMQ_DUPL_ID = rahdar:<run id>:<source message id>`.
3. **Cleanup.** When staging is empty, Rahdar removes the staging queue and the address
   settings it added.

So an interrupted run loses nothing. It comes back as **Interrupted**, with the messages still
on the source broker, and offers **Resume** or **Return to source**.

A copy needs no staging: Rahdar browses the source, which leaves it untouched, and records the
ids it has already copied so a resume does not copy them twice.

A move between two queues on the *same* node skips all of it — that is the broker's own
`moveMessages`, in chunks.

### Frozen selection

A run fixes its selection at the moment you preview it (`AMQTimestamp <= t0`). Messages that
arrive while it runs are not part of it, so a transfer from a queue with a live producer ends.

### Large queues

Nothing loads a whole queue. Staging is refilled only while it holds less than twice the batch
size, so a move of a million messages parks a few hundred at a time. Every broker call goes
through the same per-node rate limiter as the rest of Rahdar, and a run is capped at a
configurable number of messages a second.

## What the preview checks

Nothing touches a broker until you confirm. The preview reads the target and says, in words,
what it found. Findings come in three kinds:

- **Refused** — the transfer will not run. A target that drops messages when full
  (`address-full-policy=DROP`), a target queue with a filter that would silently discard what
  arrives, a ring queue too small for the selection, a backup node, a target that is out of room
  or disk, a missing queue on a broker with auto-create off, or the source queue itself.
- **Needs your acknowledgement** — it can run, and you should know: a last-value queue collapses
  messages by key, a target whose duplicate-id cache is off or not persisted, a target with no
  consumer and redistribution on, a target that is tight on room.
- **Could not be checked** — a node did not answer. It is stated as unknown, never as a pass.

Each finding that a `broker.xml` setting would fix shows the exact snippet.

A move, and anything over the safety cap, is armed by typing the source queue's name.

## Watching a run

`Clusters → Messaging → Transfers` lists every run where the cluster is the source or the
target. A run's own page shows the pipeline — **Selected → Held in staging → Delivered** — the
rate and an estimate of the time left, the per-node outcome for both ends, and links to the
audit trail on each cluster.

| State | Means |
|---|---|
| **Running** | Relaying batches. |
| **Waiting for the target to have room** | The target address or its disk crossed the capacity threshold. The run polls and continues by itself, or stops after the capacity wait. |
| **Succeeded** | Every selected message was transferred. |
| **Partial** | Some selected messages could not be taken — being delivered to a consumer, scheduled, or already gone. The number is shown, never swallowed. |
| **Stopped** / **Interrupted** / **Failed** | Resumable. Anything held in staging is still on the source broker. |
| **Returned** | The held messages went back to the source queue and the staging queue is gone. |

**Stop** lets the batch in flight finish. **Resume** carries on from where it stopped.
**Return to source** moves everything still held back to the source queue — messages already
delivered to the target stay there, which the dialog says before you type the queue name.

If Rahdar ever loses a run — restored from a backup, for instance — the Transfers screen lists
any `rahdar.transfer.*` queue that has no run as **staging with no run**, with a return action.
Nothing strands silently.

## What the target receives

The body is copied verbatim, whatever its type, and a large message streams through disk rather
than memory. Priority, expiration, timestamp, user id, group id, correlation id, reply-to and every
application property come across. Broker bookkeeping (`_AMQ_ORIG_*`, routing and delivery-count
headers) does not. Every message arrives durable, a non-durable one included: it reaches the target in
a transaction, and a transaction's messages survive a broker restart.

Five properties are added, so a message can be traced back:
`_rahdar_transfer_run`, `_rahdar_orig_message_id`, `_rahdar_orig_cluster`,
`_rahdar_orig_node`, `_rahdar_orig_queue`.

A move clears the expiry the broker itself would clear on a move; a copy keeps it.
A message published over AMQP, MQTT or STOMP arrives as its Core conversion — the preview says so.

## What the broker needs

Both ends need a **Core connection** (`tcp://…`), not only Jolokia, because the relay is a Core
client. The source broker also needs Rahdar's management user to be allowed to create and use
the staging namespace:

```xml
<security-setting match="rahdar.transfer.#">
  <permission type="createAddress"      roles="amq"/>
  <permission type="deleteAddress"      roles="amq"/>
  <permission type="createDurableQueue" roles="amq"/>
  <permission type="deleteDurableQueue" roles="amq"/>
  <permission type="send"               roles="amq"/>
  <permission type="consume"            roles="amq"/>
  <permission type="browse"             roles="amq"/>
</security-setting>
```

Rahdar shows this snippet itself the moment a staging queue is refused, so you do not have to
find this page first.

## Permissions

| Action | Needs |
|---|---|
| Move | `message:move` on the source cluster **and** `message:send` on the target |
| Copy | `message:read` on the source cluster **and** `message:send` on the target |
| Return to source | `message:move` on the source cluster |

A cluster you may not send to is listed in the dialog, disabled, saying so. A target cluster you
may not see at all answers as if it did not exist. Permissions are re-checked before every
batch, so a grant revoked mid-run stops the run and says why.

## Settings

Under **Settings → Message transfer**:

| Setting | Is |
|---|---|
| Batch size | Messages per relay transaction. A move parks at most twice this many outside the source queue. |
| Messages per second | The ceiling on one run's rate. |
| Concurrent runs | How many runs may execute at once across every cluster. |
| Capacity threshold (%) | How full the target may get before a run waits. |
| Capacity wait | How long a run waits for a full target before it stops. |

## See also

- [ADR-0097](/reference/adr/0097-cross-broker-transfer-is-a-staged-deduplicated-relay) — why a transfer is a staged, de-duplicated relay.
- [ADR-0098](/reference/adr/0098-per-cluster-core-tls-through-an-sslcontextfactory) — how two clusters with different certificate authorities are connected at once.
