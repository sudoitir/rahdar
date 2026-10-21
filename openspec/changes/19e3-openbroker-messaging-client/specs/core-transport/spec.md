## ADDED Requirements

### Requirement: Broker links recover under a stated contract
Every broker link Rahdar opens for sending, receiving or relaying SHALL be opened with a declared delivery guarantee and recovered by the broker SDK with the link's backoff. Each state change (open, recovering, failed, closed) SHALL be recorded with the node that serves the link and shown in diagnostics.

#### Scenario: A broker restarts under a plugin consumer
- **WHEN** a broker serving a plugin consumer restarts
- **THEN** diagnostics show the link recovering and then open again, naming the node, and messages the plugin had not acknowledged are delivered again

### Requirement: Outcomes after a recovery are never overstated
A send whose outcome is unknown after a recovery SHALL be reported to its caller and the audit as in doubt, never as a success. A settle of a message received before a recovery SHALL be reported as stale, and the message SHALL be left for redelivery.

#### Scenario: A send in doubt
- **WHEN** a send through Rahdar is cut off before its confirmation arrives and the link recovers
- **THEN** the caller is told the send is in doubt, and the audit records it as such

### Requirement: Plugin consumers keep their contract on the SDK's processors
Plugin consumers SHALL run on the broker SDK's processors with the plugin's concurrency as lanes, one unsettled message per lane, and message-group ordering as the SDK's group ordering scope. Releasing, rejecting and acknowledging SHALL behave as the plugin messaging contract states, including that a release does not count as a delivery attempt.

#### Scenario: Concurrency on the SDK
- **WHEN** a consume registration with concurrency 4 receives messages of three groups
- **THEN** up to four handlers run at once, and each group's messages are handled in order, one at a time
