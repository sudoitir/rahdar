# message-transfer Specification

## Purpose
Moves or copies messages from a queue on one broker node to a queue on another node
of the same or a different cluster — by id, filter, or whole queue, at any queue
size — without losing or duplicating a message, and refuses up front when the target
would not accept them.

## Requirements

### Requirement: A transfer names a source, a target, a mode and a selection

The system SHALL let an operator transfer messages from a source queue on one node to
a target queue on another node, in the same cluster or in another registered
cluster. The mode SHALL be **move** (messages leave the source) or **copy** (the
source is unchanged). The selection SHALL be a list of message ids, a filter, or the
whole queue. A transfer whose source and target are the same queue on the same node
SHALL be refused. A transfer between two queues on the same node SHALL be carried
out by the broker itself, without leaving it.

#### Scenario: Cross-cluster move by filter

- **WHEN** an operator moves the messages matching `region = 'eu'` from `orders` on node A of cluster P to `orders` on node C of cluster D
- **THEN** exactly the matching messages are on the target and no longer on the source

#### Scenario: Copy leaves the source unchanged

- **WHEN** an operator copies a selection to another node
- **THEN** the source queue's messages and depth are unchanged and the target holds one copy of each selected message

#### Scenario: Same queue on the same node is refused

- **WHEN** the target is the source queue on the source node
- **THEN** the preview refuses the transfer and says why

### Requirement: Forced redistribution targets another node of the same cluster

The system SHALL offer, from a queue on one node, to redistribute selected messages
to the same queue on another live node of the same cluster. The messages SHALL land
on that node's own queue, not be routed onward by the cluster at the moment they
arrive. When the target node has no consumers and the cluster may redistribute them
again, the preview SHALL say so.

#### Scenario: Specific messages land on the chosen node

- **WHEN** an operator redistributes three messages by id from `orders` on node 1 to node 2
- **THEN** those three messages are on node 2's `orders` queue

#### Scenario: Re-redistribution is warned

- **WHEN** the target node's queue has no consumers and redistribution is enabled for its address
- **THEN** the preview warns that the broker may move the messages again

### Requirement: The selection is frozen when the run starts

A filter or whole-queue selection SHALL cover only messages whose timestamp is not
later than the run's start, so that messages produced during the run are not
transferred and a run against a live queue terminates. A filter SHALL be parsed before
the run and SHALL be refused unless it is one complete expression, so it cannot escape
the time bound it is combined with. An id selection SHALL cover exactly the listed ids.
Selected messages that cannot be taken — being delivered to a consumer, or scheduled
for later delivery — SHALL be counted and reported, never silently left.

#### Scenario: A producer sending during the run

- **WHEN** a producer keeps sending matching messages to the source while a move runs
- **THEN** the run ends, having moved only the messages timestamped up to its start

#### Scenario: Messages in delivery are reported

- **WHEN** some selected messages are being delivered to a consumer when the run reaches them
- **THEN** the run finishes as partial and states how many messages were not transferred and why

#### Scenario: A filter that would escape the bound is refused

- **WHEN** a transfer is requested with the filter `a=1) OR (1=1`
- **THEN** it is refused as an invalid filter before any message moves

### Requirement: Transfers work on queues of any size

The system SHALL take the selection from the source in bounded chunks and SHALL never
hold the whole selection in one broker call or in Rahdar's memory. For a move, the
number of messages parked outside the source queue at any moment SHALL be bounded.
Throughput SHALL be limited by a configurable messages-per-second rate and batch
size, and every management call SHALL go through the per-node rate limiter of both
nodes.

#### Scenario: A large queue

- **WHEN** an operator moves a queue of 100,000 messages
- **THEN** the run completes, the target holds all 100,000, and the number of messages parked at any moment never exceeded the bound

### Requirement: No message is lost or duplicated by an interruption

A move SHALL first take the selection off the source queue into a Rahdar-owned
staging queue on the source broker and SHALL relay from there. A message SHALL be
acknowledged on the source only after the target has committed it, so that at every
moment each message exists on a broker. Every transferred message SHALL carry a
duplicate-detection id derived from the run and the source message. A copy SHALL
record which source messages it has copied so that a resumed copy does not copy
them again.

#### Scenario: Rahdar stops between the target commit and the source acknowledgement

- **WHEN** Rahdar stops after a batch is committed on the target and before it is acknowledged on the source, and the run is resumed
- **THEN** the target holds each message of that batch exactly once

#### Scenario: The target becomes unreachable mid-run

- **WHEN** the target node stops answering during a move
- **THEN** the run stops as failed, every message not yet delivered is held in the staging queue, and nothing is lost

#### Scenario: A resumed copy

- **WHEN** a copy is interrupted and resumed
- **THEN** each selected message is on the target once

#### Scenario: Duplicate detection is off on the target

- **WHEN** the target broker's duplicate-id cache is disabled or not persisted
- **THEN** the preview warns that an interruption at the wrong moment may duplicate messages, and the operator must acknowledge it

### Requirement: The target is checked before a transfer and before every batch

The preview SHALL check whether the target can accept the selection and SHALL classify each finding as a refusal, a warning, or unknown. It SHALL refuse when:

- the target node is not live, is a backup, or is in split-brain;
- the target queue does not exist and the broker would not create it;
- the target queue has a filter, since the broker silently drops non-matching messages;
- the target address's full or page-full policy is `DROP`;
- the target is a ring queue smaller than the selection;
- the projected size exceeds the target's remaining capacity under a `FAIL` or `BLOCK` policy;
- the target's disk is at its limit.

It SHALL warn, requiring acknowledgement, when:

- the target is a last-value queue;
- capacity is tight;
- the broker may redistribute the messages again.

A check that could not be made SHALL be shown as unknown and SHALL NOT count as passing. Before every batch the system SHALL re-read the target's capacity. When the target is near full, the run SHALL wait and continue by itself once capacity returns. If capacity does not return within a configurable wait, the run SHALL stop, resumable.

#### Scenario: Silent-drop policy is refused

- **WHEN** the target address's full policy is `DROP`
- **THEN** the preview refuses the transfer and names the policy and the address setting to change

#### Scenario: Not enough room

- **WHEN** the target address uses `FAIL` with 10 MB of headroom and the selection is estimated at 84 MB
- **THEN** the preview refuses the transfer and states both figures

#### Scenario: Target fills during the run

- **WHEN** the target address crosses the capacity threshold mid-run and a consumer later drains it
- **THEN** the run shows that it is waiting for capacity, then continues without operator action

#### Scenario: Capacity never returns

- **WHEN** the target stays full beyond the capacity wait
- **THEN** the run stops, states why, and can be resumed

#### Scenario: A target node that did not answer

- **WHEN** the target node does not answer the preview's checks
- **THEN** the affected checks are shown as unknown and the preview does not claim the target can accept the selection

### Requirement: Messages arrive faithfully and carry their provenance

A transferred message SHALL keep its body byte-for-byte for every body type, including large messages, and SHALL keep its durability, priority, timestamp, message id, group, correlation id, reply-to, last-value key and application properties. A copied message SHALL keep its expiration. A moved message SHALL arrive without an expiration, as the broker's own move leaves it, and the preview of a move SHALL say so. The broker's routing bookkeeping from the source SHALL NOT be carried. Each transferred message SHALL carry provenance naming:

- the run;
- the source cluster, node and queue;
- the source message id.

The preview SHALL state that messages published over a non-Core protocol arrive as their Core form.

#### Scenario: A large binary message

- **WHEN** a 5 MiB bytes message is moved to another cluster
- **THEN** the target message's body is byte-identical to the source's

#### Scenario: Provenance is readable downstream

- **WHEN** a consumer on the target reads a transferred message
- **THEN** its properties name the transfer run, the source queue and the source message id, and do not include the source's original-address bookkeeping

### Requirement: A transfer is previewed, confirmed, audited and authorised on both clusters

The preview SHALL cause no state change on either broker. It SHALL state:

- the mode;
- the number of messages and their estimated size, or that either is unavailable;
- the source and target cluster, node and queue.

A selection over the bulk cap SHALL need an explicit, typed override. A move SHALL be confirmed by typing the source queue name. Starting a run SHALL require the preview's plan, unexpired and unchanged.

A move SHALL require `message:move` on the source cluster, and a copy `message:read` there. Both SHALL require `message:send` on the target cluster. Permissions SHALL be re-checked during the run, and the run SHALL stop with the reason when a grant is withdrawn. A target cluster the operator has no access to SHALL be indistinguishable from one that does not exist.

The run SHALL be audited on the source cluster before the first broker call, with a linked event on the target cluster. Stop, resume and return SHALL be audited as separate events.

#### Scenario: Preview has no side effect

- **WHEN** an operator previews a move
- **THEN** no staging queue is created and no message is moved, sent or acknowledged

#### Scenario: No send grant on the target

- **WHEN** an operator without `message:send` on the target cluster previews a transfer to it
- **THEN** the target cluster is reported as not found

#### Scenario: Grant withdrawn mid-run

- **WHEN** the operator's `message:send` on the target cluster is revoked during a run
- **THEN** the run stops at the next batch and states that the permission was withdrawn

### Requirement: A run is observable, stoppable, resumable and returnable

A run SHALL be persisted and observable live. It SHALL be one of these states:

- previewed;
- running;
- waiting for capacity;
- succeeded;
- partial;
- stopped;
- interrupted;
- failed;
- returned.

It SHALL report the counts selected, held in staging, delivered, and not transferred, together with its rate. Stop SHALL finish the batch in flight and then halt. A stopped, interrupted or failed run SHALL offer resume, and a move SHALL also offer return to source, which puts every message still held back on the source queue.

A run holding messages SHALL NOT be discarded. A Rahdar restart SHALL mark running runs interrupted without losing their messages. Only one active run SHALL exist per source queue. A staging queue that belongs to no run SHALL be listed as orphaned, with a way to return its messages.

#### Scenario: Return to source

- **WHEN** an operator returns a stopped move with 4,000 messages still held
- **THEN** those 4,000 messages are back on the source queue, the staging queue is removed, and the run is returned

#### Scenario: Restart mid-run

- **WHEN** Rahdar restarts while a move is running
- **THEN** the run is interrupted, its held messages are intact, and resuming completes it

#### Scenario: A second run on the same source queue

- **WHEN** a run is active on a source queue and another is started on it
- **THEN** the second is refused with a conflict

#### Scenario: Orphaned staging

- **WHEN** a staging queue exists on a broker with no run recorded for it
- **THEN** the transfers list shows it as orphaned with its depth and offers to return its messages to a queue

### Requirement: Unavailable transfers explain themselves

When a transfer is impossible because a node lacks Core connectivity, management write
access, or broker rights on the staging queue namespace, the control SHALL remain
visible and disabled with the reason and the `broker.xml` snippet that enables it. A
capability not yet established SHALL leave the control enabled with the uncertainty
stated.

#### Scenario: No Core connection to the target

- **WHEN** the target cluster has no Core transport
- **THEN** the transfer is disabled with the reason and the acceptor snippet that enables Core

#### Scenario: Staging rights missing

- **WHEN** the source broker refuses to create the staging queue
- **THEN** the run fails before moving anything and shows the security-setting snippet for the staging namespace
