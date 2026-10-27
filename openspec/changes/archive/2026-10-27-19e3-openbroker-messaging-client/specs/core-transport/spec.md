## ADDED Requirements

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
