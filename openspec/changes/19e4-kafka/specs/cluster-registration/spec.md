## ADDED Requirements

### Requirement: A Kafka cluster is registered from bootstrap servers
The system SHALL register a Kafka cluster from one or more bootstrap servers with a security protocol (plaintext, TLS, SASL over plaintext, SASL over TLS), a SASL mechanism (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER) and its credentials, sealed like every other cluster secret. A dry run SHALL connect, discover every broker and the controller quorum, and persist nothing. The system SHALL refuse a cluster below Kafka 4.0, naming the version found.

#### Scenario: Registering a SASL cluster
- **WHEN** an operator registers a cluster from one bootstrap server with SCRAM-SHA-512 credentials
- **THEN** every broker is discovered and listed as a node, and the password is stored sealed and never shown again

#### Scenario: An old cluster
- **WHEN** an operator dry-runs registration of a Kafka 3.9 cluster
- **THEN** the dry run fails, naming the version found and the supported range, and nothing is persisted
