## ADDED Requirements

### Requirement: A failure and a remedy name the cluster's system

A broker failure SHALL name the system of the cluster it happened on, even when the driver's error does not.
A capability remedy SHALL be headed as a `broker.xml` change only when it is an Artemis snippet; a Kafka or
RabbitMQ remedy SHALL be presented as a change on the broker.

#### Scenario: A Kafka failure without native details

- **WHEN** a request on a Kafka cluster fails with an unsupported version and no Kafka details
- **THEN** the problem names Kafka, and its title does not name Artemis
