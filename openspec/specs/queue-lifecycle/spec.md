# queue-lifecycle Specification

## Purpose
Creating, destroying, pausing, resuming and reconfiguring queues and addresses across a cluster, with a dry run, per-node results, the bulk safety cap, permissions and audit.

## Requirements

### Requirement: Subscriptions and destinations can be created and destroyed from Rahdar

The system SHALL allow an authorized operator to create a subscription, destroy a
subscription, create a destination and destroy a destination on a registered
cluster, without leaving the product for a broker CLI or a JMX console. Each is
addressed by its kind and its canonical resource id, and each is a create or a
delete of a resource, whatever the system calls it.

A subscription creation SHALL carry its kind, its name, the destination it binds
to, and the system's native attributes for that kind (for an Artemis queue, its
routing type and whether it is durable); a filter MAY be supplied at creation.
A destination creation SHALL carry its kind, its name and its native attributes.

Destroying a destination that still has subscriptions bound to it SHALL be
refused, and the refusal SHALL name the bound subscriptions, so that the operator
destroys them explicitly rather than through a single action whose blast radius
is not stated.

#### Scenario: A subscription is created across the cluster

- **WHEN** an operator creates a subscription on a cluster
- **THEN** the subscription exists on every live node of that cluster and the result
  reports the outcome for each node individually

#### Scenario: Creating a subscription that already exists is not a failure

- **WHEN** an operator creates a subscription that already exists on a node with the
  same configuration
- **THEN** that node is reported as already in the requested state, and the command
  is not treated as an error

#### Scenario: Creating a subscription that exists with a different configuration is reported

- **WHEN** an operator creates a subscription whose name already exists on a node with a
  different configuration
- **THEN** that node is reported as failed, and the report names how the existing
  configuration differs from the requested one

#### Scenario: A destination with bound subscriptions is not destroyed

- **WHEN** an operator destroys a destination that still has subscriptions bound to it
- **THEN** the operation is refused and the refusal names the bound subscriptions

#### Scenario: A kind the cluster does not have is refused

- **WHEN** an operator creates a resource of a kind the cluster's system does not offer
- **THEN** the request is refused as unsupported, naming the kind, and no broker is called

### Requirement: A lifecycle command targets the cluster and reports per node

The system SHALL accept a lifecycle command against a cluster rather than a single
node, SHALL determine the target nodes from observed live topology rather than from
stored configuration, and SHALL return an outcome for every node it considered.

Each node outcome SHALL state whether the operation was applied, would be applied,
was already satisfied, was skipped because the node was not live, or failed —
and, on a failure, why.

A node that is not live SHALL be reported as skipped rather than as failed, because
it never received the command.

The system SHALL NOT attempt to undo the nodes on which the operation succeeded
when it fails on another node. It SHALL instead report the resulting divergence.

#### Scenario: A node that is down is skipped, not failed

- **WHEN** a lifecycle command is issued to a cluster in which one node is not live
- **THEN** the live nodes are changed, the node that is not live is reported as
  skipped, and the command is not reported as failed on its account

#### Scenario: A partial failure leaves a reported divergence

- **WHEN** a lifecycle command succeeds on one node and fails on another
- **THEN** the nodes that succeeded are not reverted, the result names which node
  failed and why, and the cluster's divergent state is visible to the operator

#### Scenario: A retry after a partial failure converges

- **WHEN** an operator re-issues a lifecycle command after a partial failure
- **THEN** the nodes already in the requested state report as already satisfied and
  the previously failed node is attempted again

### Requirement: Every lifecycle command has a dry run

The system SHALL support previewing any lifecycle command without acting. A preview
SHALL name the nodes that would be affected and SHALL make no mutating call to any
broker.

For a destructive command, the preview SHALL additionally report how many messages
would be destroyed, per node.

#### Scenario: A preview touches no broker

- **WHEN** an operator previews a lifecycle command
- **THEN** the target nodes are reported and no broker is mutated

#### Scenario: A destructive preview reports what would be lost

- **WHEN** an operator previews destroying a subscription that holds messages
- **THEN** the preview reports the number of messages that would be destroyed on
  each node

### Requirement: Destroying a subscription is governed by the bulk safety cap

Destroying a subscription destroys the messages it holds, and the system SHALL
therefore evaluate it against the same bulk safety cap as any other bulk
destructive operation, using the total message count across the target nodes.

A destroy whose estimate exceeds the cap SHALL be refused unless the caller
explicitly overrides the cap.

#### Scenario: Destroying a large subscription is refused by default

- **WHEN** an operator destroys a subscription holding more messages than the safety cap
- **THEN** the operation is refused and the refusal states the estimate and the cap

#### Scenario: An explicit override proceeds

- **WHEN** the same operator repeats the destroy with an explicit override
- **THEN** the operation proceeds and the override is recorded

### Requirement: A live subscription can be paused, resumed, and reconfigured within limits

The system SHALL allow pausing and resuming a subscription, resetting its message
counters, and changing the subset of its configuration that the broker accepts on
a live subscription. Each is a custom method on the one resource, offered only
where the resource's kind has that verb, and an unsupported verb SHALL be shown
disabled with the reason.

The system SHALL NOT offer to change configuration the broker will not accept on an
existing subscription — in particular, for an Artemis queue, its routing type,
which the broker refuses to change on a live queue. Such fields SHALL be presented
as immutable, with the reason, rather than offered and then refused.

Where the broker's update operation replaces a subscription's whole configuration
rather than merging into it, the system SHALL send the subscription's complete
configuration with the operator's changes applied, so that a field the operator did
not touch is not silently cleared. A partial update SHALL NOT be forwarded to the
broker as-is.

A paused subscription SHALL be identifiable as paused in the cluster's subscriptions
view.

#### Scenario: A paused subscription stops delivering

- **WHEN** an operator pauses a subscription
- **THEN** the subscription stops delivering to its consumers, retains its messages,
  and is shown as paused

#### Scenario: An immutable field is not offered for edit

- **WHEN** an operator opens an Artemis queue for editing
- **THEN** the routing type is shown as immutable with the reason, and cannot be
  submitted as a change

#### Scenario: An untouched field survives an update

- **WHEN** an operator changes one field of a subscription whose configuration also
  sets a filter
- **THEN** the filter is unchanged after the update

#### Scenario: A verb the kind lacks is disabled with its reason

- **WHEN** an operator opens a resource whose kind cannot be paused
- **THEN** the pause action is shown disabled and names why, and a direct request for
  it is refused as unsupported

### Requirement: Lifecycle operations are permission-gated and audited

The system SHALL require a distinct permission for creating, destroying, updating,
and pausing or resuming, resolvable at global, environment, or cluster scope. A
caller without the permission for a cluster SHALL NOT be able to learn whether that
cluster exists.

The system SHALL record one audit event per lifecycle command — not one per node —
capturing the actor, the cluster, the target, the parameters, whether it was a dry
run, and the per-node outcome. A command that failed on any node SHALL be recorded
as failed, with the per-node detail preserved.

#### Scenario: A caller without the grant learns nothing

- **WHEN** a caller without the create permission for a cluster issues a create
- **THEN** the request is rejected in a way that does not reveal whether the cluster
  exists

#### Scenario: A fan-out is one audit event

- **WHEN** a lifecycle command is applied across four nodes
- **THEN** a single audit event records the command and carries the outcome of all
  four nodes

#### Scenario: A partially applied command is recorded as failed

- **WHEN** a lifecycle command succeeds on two nodes and fails on a third
- **THEN** the audit event records the command as failed and its detail names which
  nodes applied and which did not
