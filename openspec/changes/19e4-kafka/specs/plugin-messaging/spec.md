## ADDED Requirements

### Requirement: A plugin can consume a partitioned log
A consume registration SHALL accept a subscription of a partitioned log, such as a Kafka consumer group on a topic. Acknowledging SHALL settle the message. Rejecting SHALL hand it to the system's retry and dead-letter handling as the broker SDK declares it. Releasing SHALL deliver it again in place, without counting an attempt. Message-group ordering SHALL follow the message key, and messages of one partition with different keys MAY run concurrently.

#### Scenario: Release on a consumer group
- **WHEN** a plugin releases a message from a Kafka consumer-group subscription
- **THEN** the message is delivered again without counting an attempt, and later messages of its key wait for it

#### Scenario: Reject on a consumer group
- **WHEN** a plugin rejects a message from a Kafka consumer-group subscription
- **THEN** it is retried and dead-lettered as the subscription's retry handling declares, and the group's offset moves past it only after that

### Requirement: Where the system counts every delivery, a release is counted and disclosed
On a subscription whose system counts every delivery, such as a Kafka share group, a release SHALL return the message for redelivery and SHALL count as a delivery attempt. Registering a consumer on such a subscription SHALL state this in the registration's state, so that the plugin author can see it.

#### Scenario: Release on a share group
- **WHEN** a plugin releases a message from a share-group subscription
- **THEN** the message is redelivered with its delivery count raised, and the registration's state says releases count on this subscription
