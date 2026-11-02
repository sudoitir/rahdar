## ADDED Requirements

### Requirement: Flow maps every system in its own terms

Flow SHALL be offered on Artemis, Kafka and RabbitMQ clusters:

- On a Kafka cluster it SHALL show each consumer group on the topics it consumes, and SHALL state that producers
  are not observable there.
- On a RabbitMQ cluster it SHALL show consumers on their queues, and exchange bindings, shovels and federation
  links as routes.

No Kafka or RabbitMQ view SHALL name Artemis, Jolokia or `broker.xml`.

#### Scenario: A consumer group on a Kafka topic

- **WHEN** an operator opens Flow on a Kafka cluster where a group consumes a topic
- **THEN** the group is shown consuming that topic, and the view says producers are not observable on Kafka
