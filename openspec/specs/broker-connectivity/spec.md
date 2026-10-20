## Purpose

Defines how Rahdar reaches an Apache ActiveMQ Artemis broker over its
Jolokia HTTP management endpoint: what address it is given, how it batches
reads and operations into one request per node, how broker credentials are
stored, how TLS is configured, and how connection failures are reported.

## Requirements

### Requirement: Seed address is an explicit Jolokia base URL

The system SHALL accept the broker management address as a complete Jolokia
base URL and SHALL NOT derive, guess, or rewrite it. Rahdar SHALL support the
Artemis web console path (`/console/jolokia`) and a standalone agent path
(`/jolokia`) purely as caller-supplied values.

#### Scenario: Console-style URL accepted

- **WHEN** a user supplies `http://broker-1:8161/console/jolokia`
- **THEN** Rahdar issues management requests against exactly that base URL

#### Scenario: Standalone-agent URL accepted

- **WHEN** a user supplies `https://broker-1:8778/jolokia`
- **THEN** Rahdar issues management requests against exactly that base URL

#### Scenario: Bare host is rejected

- **WHEN** a user supplies `broker-1:8161` with no scheme or path
- **THEN** registration fails with a validation error naming the expected form

### Requirement: One batched request per node

For any scrape or probe that needs more than one attribute or operation from a
single node, the system SHALL send exactly one Jolokia bulk POST containing a
JSON array of request objects, mixing `read` and `exec` entries, rather than
one HTTP call per attribute, operation, or queue.

#### Scenario: HA attributes and topology in one call

- **WHEN** a refresh needs `Active`, `Started`, `Backup`, `ReplicaSync`,
  `NodeID`, `Clustered`, `Version` and `listNetworkTopology()` from a node
- **THEN** Rahdar sends a single POST whose body is a JSON array of those requests

#### Scenario: A configuration read is one call per node

- **WHEN** a node's configuration is read for comparison against another node —
  broker attributes, address settings, security settings and acceptors
- **THEN** Rahdar sends a single POST to that node containing all of those requests

#### Scenario: Per-entry errors are isolated

- **WHEN** a batched response array contains one entry with `status` 404 and an
  `error` / `error_type` field, and other entries with `status` 200
- **THEN** Rahdar consumes the successful entries and records the failed entry
  without discarding the batch

#### Scenario: Whole-call HTTP 200 with failed entries

- **WHEN** the batched POST returns HTTP 200 but one array entry has a non-200
  `status`
- **THEN** Rahdar inspects each entry's own `status` and does not treat the
  call as wholly successful

### Requirement: Double-encoded operation results are parsed

The system SHALL treat the `value` returned by `listNetworkTopology()` and
`listQueues(...)` as a JSON-encoded string and parse it a second time to obtain
the structured result.

#### Scenario: Topology value is a JSON string

- **WHEN** an `exec` of `listNetworkTopology()` returns `value` as a quoted
  JSON string
- **THEN** Rahdar parses that string into an array of node objects before use

### Requirement: Broker credentials are encrypted at rest

The system SHALL store broker credentials only as envelope-encrypted sealed values (`secret-management`): the credential encrypted with AES-GCM under its own data key, and the data key wrapped by a versioned key-encryption key from the configured key provider. With no provider configured, the key comes from `RAHDAR_SECRET_KEY`, which MUST decode as base64 to exactly 32 bytes (or list numbered keys that each do); if no valid key is available the application SHALL fail to start. The additional authenticated data SHALL bind each ciphertext to its owning cluster and credential kind so a sealed value cannot be moved to another row.

#### Scenario: Missing key stops startup

- **WHEN** the application starts with the environment provider and no `RAHDAR_SECRET_KEY` set
- **THEN** startup fails with an error that names the missing key

#### Scenario: Wrong-length key stops startup

- **WHEN** `RAHDAR_SECRET_KEY` decodes to 20 bytes
- **THEN** startup fails with an error stating a 32-byte key is required

#### Scenario: Credentials never leave in plaintext responses

- **WHEN** any cluster or node is returned from the API
- **THEN** the response body contains no broker password or secret field

#### Scenario: Ciphertext is bound to its row

- **WHEN** a sealed value for cluster A is opened with cluster B's identity as additional authenticated data
- **THEN** decryption fails rather than returning a value

### Requirement: TLS to brokers is configured by named bundle

When a broker requires TLS, the system SHALL obtain trust material and client
identity from a named Spring Boot SSL bundle referenced by the cluster's TLS
configuration, and SHALL apply that bundle's hostname-verification setting.
Rahdar SHALL NOT require a filesystem path plus password to be entered for a broker.

#### Scenario: HTTPS broker with a configured bundle

- **WHEN** a cluster's TLS reference names an SSL bundle that exists
- **THEN** Rahdar's requests to that broker use the bundle's trust and key material

#### Scenario: Missing bundle is reported

- **WHEN** a cluster's TLS reference names a bundle that is not configured
- **THEN** the connection attempt fails with an error naming the missing bundle

### Requirement: Connection failures map to a stable taxonomy

Every failed broker connection SHALL be reported as one of a fixed set of
classified errors, each carrying a stable machine-readable type and a
human-readable explanation. The set SHALL include: unreachable host, rejected
credentials, reachable Jolokia agent with no Artemis broker registered, wrong
path (no agent), and TLS handshake failure. The classification SHALL be derived
from the broker SDK's canonical status and error reason, one to one, so the
types an operator sees do not change.

#### Scenario: Nothing listening

- **WHEN** the seed host refuses the connection or does not resolve
- **THEN** the error is classified `UNREACHABLE`

#### Scenario: Bad credentials

- **WHEN** the broker responds 401 or 403
- **THEN** the error is classified `UNAUTHORIZED`

#### Scenario: Jolokia present, not Artemis

- **WHEN** the agent responds 200 but no `org.apache.activemq.artemis:broker=*`
  MBean is found
- **THEN** the error is classified `NOT_ARTEMIS`

#### Scenario: No agent at the path

- **WHEN** the base URL returns 404
- **THEN** the error is classified `WRONG_PATH` and the message suggests
  `/console/jolokia`

#### Scenario: TLS cannot be negotiated

- **WHEN** the TLS handshake to an HTTPS broker fails
- **THEN** the error is classified `TLS_FAILED` and names the bundle and whether
  hostname verification is enabled

#### Scenario: Every SDK reason has a classification

- **WHEN** the driver reports any standard error reason
- **THEN** it maps to exactly one classification, and a table test covers every one

### Requirement: Broker MBean name is resolved, not assumed

The system SHALL discover the broker's management MBean object name by issuing
a Jolokia `search` for `org.apache.activemq.artemis:broker=*` rather than
assuming the broker's configured `<name>`.

#### Scenario: Broker name unknown at registration

- **WHEN** a cluster is registered and the broker's `<name>` is not supplied
- **THEN** Rahdar issues a `search` request and uses the returned object name
  for subsequent reads

### Requirement: Each broker's clock offset is measured without extra calls

The system SHALL estimate how far each broker node's clock differs from its own,
using a timestamp the broker already returns on management responses, and SHALL
NOT make any additional broker request to obtain it.

Each estimate SHALL carry an uncertainty covering both the round trip and the
granularity of the source timestamp. An offset within its own uncertainty SHALL be
treated as agreement. A node whose responses carry no usable timestamp SHALL be
reported as unmeasured, never as being in agreement.

#### Scenario: Offset is measured during ordinary polling

- **WHEN** the system polls a broker node
- **THEN** it records an estimate of that node's clock offset without issuing a
  further request

#### Scenario: A sub-uncertainty offset is not skew

- **WHEN** a node's measured offset is smaller than its uncertainty
- **THEN** the node is reported as being in agreement and no correction is applied

#### Scenario: A missing timestamp is unknown

- **WHEN** a node's responses carry no usable timestamp
- **THEN** its clock is reported as unmeasured rather than as agreeing

### Requirement: Broker timestamps are normalised before they are stored

The system SHALL convert a timestamp originating from a broker onto its own
timeline, using that node's measured offset, before comparing or persisting it
alongside its own timestamps.

#### Scenario: A deadline from a skewed clock is corrected

- **WHEN** a message carries an absolute expiry stamped by a clock that the system
  has measured as offset
- **THEN** the deadline the system acts on is expressed on its own timeline

### Requirement: A wrong clock on the system's own host is inferred and reported

The system SHALL distinguish individual nodes disagreeing with it from every
measured node disagreeing with it in the same direction. The latter SHALL be
reported as implicating the system's own host rather than the brokers.

The system SHALL also detect its own wall clock stepping relative to elapsed time,
and SHALL discard its clock estimates when that happens rather than carrying them
forward.

#### Scenario: One node out of step names that node

- **WHEN** one measured node disagrees beyond tolerance and others agree
- **THEN** the verdict names that node

#### Scenario: Every node out of step implicates the host

- **WHEN** every measured node disagrees beyond tolerance in the same direction
- **THEN** the verdict states that the system's own host is the likely cause

#### Scenario: A stepped clock invalidates the estimates

- **WHEN** the system's wall clock moves relative to elapsed time beyond a
  threshold
- **THEN** the existing clock estimates are discarded and rebuilt

### Requirement: Broker HTTP clients are bounded, shared resources

The system SHALL reuse its broker HTTP clients across calls. The clients belong to
the broker SDK's driver registry, which is created once and closed on application
shutdown. The number of clients, and so the threads and file descriptors they hold,
SHALL be bounded by the number of distinct transport configurations (for example TLS
bundles), and SHALL NOT grow with the number of calls, polls, nodes, clusters, stream
subscribers or reconnect attempts.

A client whose configuration is replaced by a timeout change, or by a reload of its TLS
material, SHALL be released. Requests already in flight SHALL be allowed to complete. Every
client SHALL be released on application shutdown.

#### Scenario: Repeated polling does not grow the thread count

- **WHEN** the system issues hundreds of broker calls to the same nodes after warming up
- **THEN** the live thread count and open file descriptor count stay flat rather than growing with the number of calls

#### Scenario: A timeout change releases the old client

- **WHEN** an operator changes a broker timeout in Settings
- **THEN** subsequent calls use a client with the new timeouts and the previous client is released

#### Scenario: A reloaded TLS bundle gets a new client

- **WHEN** the TLS material of a bundle used by a cluster is reloaded
- **THEN** the next call to that cluster uses the reloaded material and the client built from the old material is released

#### Scenario: Redirects are still not followed after a timeout change

- **WHEN** a broker timeout has been changed at runtime and a seed URL answers with a redirect
- **THEN** the redirect is surfaced as a connection error rather than followed

### Requirement: A rejected credential is named for its account

When a broker rejects the management account (HTTP 401 or 403 from Jolokia) or the Core account
(a Core security exception), the cluster's health SHALL name which account was rejected, on
which nodes, and SHALL link to where that account is edited. A rejection SHALL NOT be reported
as an unreachable broker. Credentials changed on the brokers outside the system SHALL therefore
surface as this state within one scrape, and SHALL clear on the next successful call after they
are updated.

#### Scenario: Password changed outside Rahdar
- **WHEN** the management password is changed on the brokers and the next scrape runs
- **THEN** the cluster's health says the management account was rejected on every node and links to the Connection settings

#### Scenario: Only the Core account is rejected
- **WHEN** Jolokia calls succeed and Core connections are refused for bad credentials
- **THEN** health says the Core account was rejected, and management features keep working
