## ADDED Requirements

### Requirement: The system is chosen from the drivers Rahdar loads
Registration SHALL start from a choice of system, listed from the broker drivers Rahdar loads with each one's name and supported versions, and SHALL then ask only for that system's connection. Registration SHALL be a page of its own that can be linked to, with the chosen system in its address. A request for a system Rahdar does not load, or whose connection is shaped for another system, SHALL be refused naming the systems it supports.

#### Scenario: Choosing Kafka
- **WHEN** an operator opens the registration page and chooses Apache Kafka
- **THEN** the page asks for bootstrap servers and security settings, shows no Jolokia field, and its address names the system

#### Scenario: An unknown system
- **WHEN** a client registers a cluster with a system Rahdar does not load
- **THEN** the request is refused as an unsupported system, naming the systems it supports, and nothing is persisted

### Requirement: A Kafka cluster is registered from bootstrap servers
The system SHALL register a Kafka cluster from one or more bootstrap servers, written as `host:port` in one field separated by commas, spaces or new lines, with the port defaulting to 9092, and SHALL refuse a server written with a scheme or an account. Kafka 3.0 and later SHALL be supported, in ZooKeeper and KRaft mode; an older cluster SHALL be refused, naming what was found and the supported range.

#### Scenario: Registering a SASL cluster
- **WHEN** an operator registers a cluster from one bootstrap server with SCRAM-SHA-512 credentials
- **THEN** every broker is discovered and listed as a node, and the password is stored sealed and never shown again

#### Scenario: Two servers in one field
- **WHEN** an operator enters `k1:9092, k2` in the bootstrap field
- **THEN** both servers are kept, the second on port 9092, and both are shown as separate entries before the check

### Requirement: A Kafka connection's security settings are checked as a whole
Registration SHALL take a security protocol (plaintext, TLS, SASL over plaintext, SASL over TLS), a SASL mechanism exactly when the protocol uses SASL (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER), credentials that match the mechanism, and an optional TLS bundle for the trust store and a client certificate. Secrets SHALL be sealed and never returned. TLS hostname verification SHALL always be on.

#### Scenario: A password without a username
- **WHEN** an operator enters a SASL password and leaves the username empty
- **THEN** the message is on the username, and it clears as soon as the username is entered

#### Scenario: SASL over plaintext
- **WHEN** an operator chooses SASL over plaintext
- **THEN** the form warns that the credentials travel unencrypted, and registration is still allowed

#### Scenario: An old cluster
- **WHEN** an operator checks a Kafka 2.8 cluster
- **THEN** the check fails, naming the version found and the supported range, and nothing is persisted

#### Scenario: A ZooKeeper-mode cluster
- **WHEN** an operator registers a Kafka 3.5 cluster that runs ZooKeeper
- **THEN** it is registered, its version is shown as a lower bound with its mode, and the controller quorum is shown as unavailable with the reason

#### Scenario: A secret is never returned
- **WHEN** any client reads a registered Kafka cluster, its connection, its audit entries or a check's result
- **THEN** no password or token appears in the answer

### Requirement: The connection check reaches every node and names each cause
Checking a connection SHALL persist nothing and SHALL report the version found, every discovered node with its advertised address and whether Rahdar reached it there, the controller or quorum, what the account may do, and the capabilities the cluster lacks, each with its reason. A cluster reached on some but not all nodes MAY be registered after a warning.

#### Scenario: An advertised address Rahdar cannot reach
- **WHEN** the bootstrap server answers but a broker advertises an address Rahdar cannot reach
- **THEN** the check names that broker and its advertised address as unreachable and says the broker's advertised listeners must be reachable from Rahdar

### Requirement: A failed check names its cause and the next action
A failed check SHALL name its cause and the next action, telling apart a name that does not resolve, a refused or timed-out connection, an untrusted certificate or a host name mismatch, failed authentication, and an account that authenticated but may not describe the cluster. Registration SHALL stay disabled, with its reason, until a check has passed on exactly the entered connection.

#### Scenario: Authenticated but not authorised
- **WHEN** the credentials are accepted but the account may not describe the cluster
- **THEN** the check says the account was accepted and lacks the permission to describe the cluster, distinct from a rejected password

#### Scenario: Register waits for a passing check
- **WHEN** an operator changes the bootstrap servers after a passing check
- **THEN** Register is disabled with the reason that the connection must be checked again

### Requirement: A partly reached cluster may be registered after a warning
A cluster whose check reached some but not all of its nodes SHALL be registrable after a warning that it will be shown degraded.

#### Scenario: Partial reach
- **WHEN** two of three brokers are reached
- **THEN** the check names the third, registering warns that the cluster will be shown degraded, and the operator may still register it

### Requirement: A Kafka cluster is registered only once
When a checked Kafka cluster's cluster id matches a registered cluster, the check SHALL say so with a link to it, and registration SHALL be refused.

#### Scenario: Registering the same cluster again
- **WHEN** an operator checks a Kafka cluster that is already registered under another name
- **THEN** the check names the registered cluster with a link, and Register is disabled with that reason

### Requirement: A Kafka cluster's connection can be edited after registration
An operator with the right to edit the cluster SHALL be able to change its bootstrap servers, security protocol, mechanism, credentials and TLS bundle, check the change without storing it, and save it audited with secrets redacted. An omitted secret SHALL keep the stored one, and a stored secret SHALL never be sent to a server the cluster does not already use: a change of the bootstrap servers, the protocol or the username SHALL need the secret again.

#### Scenario: Rotating a password
- **WHEN** an operator enters a new SCRAM password, checks it and saves
- **THEN** the cluster reconnects with it, and the audit records the change without the password

#### Scenario: A new server needs the secret again
- **WHEN** an operator adds a bootstrap server the cluster does not use and leaves the password empty
- **THEN** the check and the save are refused with a request to enter the password again, and nothing is sent to that server
