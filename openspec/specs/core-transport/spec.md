## Purpose

Defines the Core protocol connection to a broker node — how its address and
credentials are resolved, and how the connection is opened, held, and torn down
— as the second broker transport alongside Jolokia.

## Requirements

### Requirement: A dialable Core URL is resolved per node

The system SHALL determine a Core connection URL for a node from, in order of
precedence, an operator-set manual Core URL, otherwise the Core connector
address learned from topology discovery. A bare `host:port` value SHALL be
normalised to a Core protocol URL. When no Core URL can be resolved for any
serving node of a cluster, the cluster has no Core transport and this SHALL be
reported as the reason its notification capability is unavailable.

#### Scenario: Manual override wins

- **WHEN** a node has both a discovered Core connector and an operator-set manual Core URL
- **THEN** the manual Core URL is used

#### Scenario: Discovered connector is used when there is no override

- **WHEN** a node has a discovered Core connector and no manual override
- **THEN** the discovered connector, normalised to a Core protocol URL, is used

#### Scenario: No resolvable URL disables the transport

- **WHEN** no serving node of a cluster has a resolvable Core URL
- **THEN** the cluster has no Core transport and its notification capability reports that as the reason

### Requirement: The Core credential defaults to the Jolokia credential

The system SHALL use a stored Core-kind credential for a cluster's Core
connections when one exists, and otherwise SHALL use that cluster's stored
Jolokia credential. A Core-kind credential SHALL be stored as a distinct sealed
secret, not derived from the Jolokia credential's stored form.

#### Scenario: Explicit Core credential is used

- **WHEN** a cluster has a stored Core-kind credential
- **THEN** Core connections for that cluster authenticate with it

#### Scenario: Falls back to the Jolokia credential

- **WHEN** a cluster has no stored Core-kind credential
- **THEN** Core connections for that cluster authenticate with the stored Jolokia credential

### Requirement: The Core connection is configured not to be driven by broker topology

The system SHALL open Core connections with client-side topology-driven load
balancing disabled and the client library's automatic reconnect disabled, so
that a connector hostname the broker advertises but Rahdar cannot resolve does
not cause a blocking retry. TLS settings for a Core connection SHALL be resolved
from the same per-cluster TLS reference used for the Jolokia connection, and
SHALL apply to that cluster's connections only: configuring TLS for one cluster
SHALL NOT change the trust material any other cluster's Core connection uses.

#### Scenario: An unresolvable advertised connector does not wedge the connection

- **WHEN** the broker advertises a Core connector hostname that does not resolve from where Rahdar runs
- **THEN** the Core connection attempt fails fast and is retried by Rahdar rather than blocking on a library reconnect loop

#### Scenario: TLS reference is shared

- **WHEN** a cluster has a TLS reference configured
- **THEN** Core connections for that cluster use the same trust material as its Jolokia connection

#### Scenario: Two clusters with different certificate authorities

- **WHEN** two clusters each have a TLS reference whose trust material is a different certificate authority
- **THEN** Core connections to both clusters succeed at the same time, and each trusts only its own cluster's authority

#### Scenario: An undefined TLS reference fails that cluster only

- **WHEN** a cluster's TLS reference names trust material that is not defined
- **THEN** that cluster's Core connection fails with a reason naming the missing reference, and other clusters' Core connections are unaffected

### Requirement: Core connections are released on shutdown and cluster removal

The system SHALL pool Core connections per cluster rather than opening and
closing one per call, and SHALL close every pooled Core connection for a cluster
when that cluster is removed, and SHALL close every pooled Core connection on
application shutdown without letting a hung close delay shutdown beyond a
bounded wait.

#### Scenario: Removing a cluster closes its connections

- **WHEN** a cluster is removed
- **THEN** its Core subscriptions and pooled connections are closed and not reopened

#### Scenario: Shutdown is not blocked by a hung close

- **WHEN** the application shuts down and one Core connection does not close promptly
- **THEN** shutdown proceeds after a bounded wait

#### Scenario: Repeated calls to the same node reuse a pooled connection

- **WHEN** two consecutive Core operations target the same cluster and Core URL
- **THEN** the second operation reuses a pooled connection rather than opening a new one

### Requirement: Long-lived capture sessions cannot starve operator operations

Sessions held open indefinitely to drain capture queues SHALL NOT draw on the same bounded
session capacity as operator browses, sends and request-reply sampling. Adding capture taps
to a node SHALL NOT make a browse or send to that node wait or fail.

Every Core consumer and browser SHALL use a bounded client-side prefetch window, so the
memory held for one consumer is bounded.

#### Scenario: Many capture taps do not block a browse

- **WHEN** a node carries more capture taps than a single connection's session limit
- **THEN** an operator browse of a queue on that node is served without waiting for a capture session to be released

### Requirement: A failed Core connection attempt releases its resources

When establishing a Core connection or subscription fails, the system SHALL release every
resource created for the attempt before it retries. Repeated failures against an unreachable
node SHALL NOT accumulate connection factories, threads or descriptors.

#### Scenario: Retrying an unreachable node does not accumulate resources

- **WHEN** a notification subscription to an unreachable node fails and is retried repeatedly with backoff
- **THEN** the resources created for each failed attempt are released, and the live thread count does not grow with the number of attempts

### Requirement: A transacted relay session moves messages between two nodes

The system SHALL provide, for moving messages from a queue on one node to a queue
on another, a relay whose source receives and target sends are each transacted.
A batch SHALL be committed on the target before it is acknowledged on the source,
and every relayed message SHALL carry a duplicate-detection id derived from the
run and the source message, so that a batch repeated after a failure between the
two commits is dropped by the target broker. Relay sessions SHALL be separate from
operator browse/send sessions and from capture sessions, so a relay cannot starve
either.

#### Scenario: A failure before the target commit loses nothing

- **WHEN** the target send or commit fails for a batch
- **THEN** the source receive is rolled back and the batch's messages remain on the source

#### Scenario: A failure between the two commits does not duplicate

- **WHEN** the target commit succeeds and the source acknowledgement fails, and the batch is relayed again
- **THEN** the target broker drops the repeated messages as duplicates and the target holds each message once

#### Scenario: A relay does not starve operator sessions

- **WHEN** a relay is running against a node
- **THEN** browsing and sending from that node's messages view still obtain a session

### Requirement: Broker links recover under a stated contract
Every broker link Rahdar opens for sending, receiving, processing or relaying SHALL be opened with a declared delivery guarantee and recovered by the broker SDK with the link's backoff, so a lost connection does not stop the work it carries.

#### Scenario: A broker connection is lost under a plugin consumer
- **WHEN** the connection to the node serving a plugin consumer is lost while the consumer holds an unsettled message
- **THEN** the consumer reconnects on its own and the message it had not settled is delivered again

#### Scenario: A link the SDK gives up on is restarted
- **WHEN** the SDK gives up on a plugin consumer's link and reports it failed
- **THEN** the next reconcile pass drops the consumer and starts it again

#### Scenario: A capture carries on through a recovery
- **WHEN** a recovery aborts a capture's commit, or leaves it in doubt
- **THEN** the capture goes on draining, the broker redelivers what it did not take, and no message is stored twice

### Requirement: Broker link state is visible in diagnostics
Each broker link state change (open, recovering, failed, closed) SHALL be recorded with the node the link was opened on, the node serving it, the owner it serves and the cause of a loss. It SHALL be shown in diagnostics (the `brokerLinks` health contributor and the support bundle), counted in metrics and logged. A cause SHALL never carry a credential.

#### Scenario: Diagnostics show a recovery
- **WHEN** a plugin consumer's connection is lost and comes back
- **THEN** diagnostics show the consumer's link recovering, with the cause, and then open again on its node under the consumer's name

### Requirement: Outcomes after a recovery are never overstated
A send whose outcome is unknown, because the broker's confirmation never came back, SHALL be reported to its caller and the audit as in doubt, never as a success or a failure. A settle of a message received before a recovery SHALL leave the message for redelivery. A relay whose target commit is left in doubt SHALL be settled by the run's existing in-doubt record.

#### Scenario: A send in doubt over the API
- **WHEN** an operator sends a message and the connection is lost before the broker confirms it
- **THEN** the API answers 202 with `inDoubt: true` and what to check, the console says the send is not confirmed and keeps what was typed, and the audit records the outcome `IN_DOUBT`

#### Scenario: A send that never left is a failure
- **WHEN** a send fails before the message left Rahdar
- **THEN** it is reported and audited as failed, not in doubt

### Requirement: Plugin consumers keep their contract on the SDK's processors
Plugin consumers SHALL run on the broker SDK's processors with the registration's concurrency as lanes, one unsettled message per lane and nothing read ahead, message groups kept in order, and the broker's own delivery-attempt limit. Acknowledging SHALL remove a message; rejecting, or a handler that throws, SHALL hand it back counted as a delivery attempt; releasing SHALL hand it back uncounted. A plugin that stops its own registration from inside its handler SHALL not wait on itself.

#### Scenario: Concurrency on the SDK
- **WHEN** a consume registration with concurrency 4 receives messages of three groups
- **THEN** up to four handlers run at once, and each group's messages are handled in order, one at a time

#### Scenario: A rejected message is redelivered, not dead-lettered
- **WHEN** a plugin rejects a message it consumed
- **THEN** the broker delivers it again with a higher delivery count, and dead-letters it only after its delivery-attempt limit

#### Scenario: A plugin stops its own registration from its handler
- **WHEN** a plugin's handler unregisters the registration delivering to it
- **THEN** the handler returns at once, and the registration stops without waiting for its stop grace

### Requirement: Telemetry never alters a message
Rahdar SHALL trace broker messaging (sends, receives, processing and settlements) through its own OpenTelemetry export, and SHALL never write trace context or any other property into a message it sends, moves, retries or relays.

#### Scenario: A traced send leaves the message untouched
- **WHEN** tracing is on and Rahdar sends a message
- **THEN** a messaging span is recorded, and the message carries no property Rahdar added for tracing
