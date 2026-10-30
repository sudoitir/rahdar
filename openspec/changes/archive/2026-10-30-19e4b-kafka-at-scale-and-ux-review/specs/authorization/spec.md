## MODIFIED Requirements

### Requirement: Every mutating and cluster-scoped operation is permission-checked

The system SHALL check the calling principal's grants against the specific permission an operation
requires before performing it, and SHALL check a read of cluster-scoped data against a read
permission before returning it. An operation on a broker resource SHALL be checked against that
resource, as the team-access capability defines. A principal without the required permission at
the resolved scope SHALL receive a `403` response, except where a cluster or resource it may not
read is addressed, which SHALL receive a not-found response. A build-time test SHALL fail when an
operation that takes a cluster, subscription or destination has no check. A Kafka topic or
subscription the caller may not read SHALL receive a not-found response even when the resource
exists. A caller who may read it but lacks the permission to change it SHALL receive a `403` response.

#### Scenario: A permitted action succeeds

- **WHEN** a user holding the required permission at the target cluster's
  scope performs that action
- **THEN** the action is performed

#### Scenario: An unpermitted action is rejected

- **WHEN** a user lacking the required permission at the target cluster's
  scope attempts that action
- **THEN** the response is `403` and the action is not performed

#### Scenario: A subscription's configuration needs read permission

- **WHEN** a signed-in user without read access to a subscription requests the resource
  with its attributes
- **THEN** the response is not found

#### Scenario: An existing Kafka topic the caller may not read

- **WHEN** a signed-in user who can see the cluster but may not read a topic requests that topic's configuration
- **THEN** the response is not found

#### Scenario: A readable Kafka topic the caller may not change

- **WHEN** a signed-in user who may read a topic but may not update it previews a configuration change
- **THEN** the response is `403` and the configuration is not changed
