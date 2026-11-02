## ADDED Requirements

### Requirement: The console queries Kafka topics and RabbitMQ streams live

On a Kafka or RabbitMQ cluster, the SQL console SHALL read the matching topics or streams live:

- It SHALL evaluate the WHERE clause in Rahdar, within the existing scan, row and time limits.
- A Kafka row SHALL carry its partition, its offset and the record time.
- A classic or quorum queue SHALL be refused with the reason that browsing it would consume it.
- The message index SHALL be offered, and its settings shown, only on Artemis clusters.

#### Scenario: A WHERE on a Kafka topic

- **WHEN** an operator queries a Kafka topic with a WHERE on a property
- **THEN** only the matching records come back, each with its partition and offset

#### Scenario: A classic RabbitMQ queue

- **WHEN** an operator queries a classic queue by name
- **THEN** the query is refused, saying that RabbitMQ can browse only streams
