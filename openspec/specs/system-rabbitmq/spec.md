# system-rabbitmq Specification

## Purpose
Registering and managing RabbitMQ clusters: the connection and its check, the RabbitMQ-only screens and their gated changes, and how RabbitMQ's queues behave on the shared screens (browse only on streams, sends routed nowhere, the strength of a change's guard).

## Requirements

### Requirement: A RabbitMQ cluster is registered from its management API
The system SHALL register a RabbitMQ cluster from one or more management URLs and, optionally, AMQP endpoints,
with one account, and SHALL check every management URL and every AMQP endpoint before saving, naming each failure
and its cause. RabbitMQ 3.13 and later SHALL be supported; an older cluster SHALL be refused, naming what was
found and the supported range.

#### Scenario: An AMQP endpoint that does not answer
- **WHEN** an operator checks a connection whose management URLs answer and one AMQP endpoint does not
- **THEN** the check names that endpoint and says it is unreachable, and the others pass

### Requirement: Browse never consumes
The system SHALL offer browsing only on stream queues, and SHALL refuse browsing a classic or quorum queue with
the driver's reason without reading from the broker.

#### Scenario: Opening the messages of a classic queue
- **WHEN** an operator opens the messages of a classic queue
- **THEN** browse is shown as unavailable with the reason, and no broker read is made

### Requirement: A change says how strongly it is guarded
When a preview's etag cannot tell a recreated resource apart, the confirmation and the held approval request
SHALL say that a resource deleted and recreated with the same configuration is not told apart.

#### Scenario: Deleting a quorum queue
- **WHEN** an operator previews deleting a quorum queue
- **THEN** the confirmation states the guard's limit beside the estimate

### Requirement: A send no queue receives fails
A message sent to a RabbitMQ exchange that routes it to no queue SHALL fail with `not-routed`, naming the
exchange and routing key.

#### Scenario: Publishing to an unbound exchange
- **WHEN** an operator publishes to an exchange with no binding for the routing key
- **THEN** the publish fails with `not-routed` and nothing is reported as sent
