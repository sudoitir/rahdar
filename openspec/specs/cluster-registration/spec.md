## Purpose

Defines the lifecycle of a cluster registration in Rahdar: creating one
from a set of reachable seed URLs, listing and inspecting registrations,
removing one, the dry-run contract that lets an operator probe before
committing, and the audit trail every mutation leaves.

## Requirements

### Requirement: A cluster is registered from one or more seed URLs

The system SHALL accept a list of seed Jolokia base URLs when registering a cluster, and one
seed SHALL be enough to register a whole cluster. A seed whose host name resolves to more than
one address SHALL be expanded into one seed per address, up to 16, keeping the seed's scheme, port
and path. The system SHALL probe each reachable seed, run the capability probe, discover the rest of
the cluster's topology, derive and prove a management URL for each discovered node, and persist
the cluster, its nodes, its credentials, its management URL pattern and any TLS reference.

A cluster has two cluster-wide accounts: a management account, used for every Jolokia call to
every node, and an optional Core account, stored as a distinct sealed secret. When the Core
account is omitted, the cluster's Core connections SHALL use the management account.

#### Scenario: One seed registers the whole cluster
- **WHEN** a cluster of two HA pairs is registered with one seed URL, and every broker serves management on the seed's scheme, port and path
- **THEN** all four brokers are persisted as manageable, matched to their `NodeID`s, with no other URL supplied

#### Scenario: Single reachable seed
- **WHEN** a cluster is registered with one seed URL that resolves to a live broker
- **THEN** the cluster is persisted with that node manageable, and every pair member found from topology is manageable when its derived URL answers with its `NodeID`

#### Scenario: A seed host with several addresses
- **WHEN** a cluster is registered with one seed whose host name resolves to three addresses
- **THEN** each address is probed as a seed with the seed's scheme, port and path

#### Scenario: Multiple seeds for an internal-hostname cluster
- **WHEN** a cluster is registered with two seed URLs, each reachable, whose brokers advertise internal connector hostnames to each other
- **THEN** both nodes are persisted as manageable, matched to their `NodeID`s

#### Scenario: No seed reachable
- **WHEN** every supplied seed URL fails to connect
- **THEN** registration fails with the classified connection error and nothing is persisted

#### Scenario: Separate Core credential is stored
- **WHEN** a cluster is registered with both a management and a Core account
- **THEN** both are stored as distinct sealed secrets and Core connections use the Core account

#### Scenario: Core credential defaults to the Jolokia credential
- **WHEN** a cluster is registered with only a management account
- **THEN** Core connections for that cluster authenticate with the management account

### Requirement: Dry-run registration persists nothing

When registration is requested with `dryRun` true, the system SHALL perform the probe, topology
discovery and management-URL derivation, and SHALL NOT create a cluster, node, credential, TLS,
revision or any other row. The registration form states what the check found as a table with one
row per node, and does not draw the topology: the graph appears on the cluster's Topology page
once it is registered. The discovered topology SHALL be returned in the same structural shape
used to render a registered cluster's topology graph.

Each node's row SHALL report: the node's name, HA role, `NodeID` and broker version; its
management URL and where that URL came from (a seed, derived from the pattern, or not found,
with the reason); whether the management account was accepted; and whether the Core account was
accepted on that node's Core address. Each account SHALL be reported on its own, so a rejected
Core account never hides an accepted management account or the reverse. The preview SHALL also
carry what adopting the running configuration would declare (see "Registration adopts the
running configuration as its first revision").

#### Scenario: Dry run returns a preview
- **WHEN** registration is called with `dryRun=true` against a live broker
- **THEN** the response contains capabilities, the discovered topology and one row per node
- **AND** no cluster row exists afterward

#### Scenario: Dry run still audited
- **WHEN** a dry-run registration is called
- **THEN** an audit event is written recording the attempt with its dry-run flag set

#### Scenario: Core account rejected while management works
- **WHEN** a dry-run uses a management account the brokers accept and a Core account they reject
- **THEN** each node reports management accepted and Core rejected, as two separate results

#### Scenario: A discovered node without a working URL
- **WHEN** a dry-run discovers a node whose derived management URL does not answer
- **THEN** that node is listed as found but not manageable, with the reason

#### Scenario: The check shows what could be configured after registering
- **WHEN** a dry-run registration reaches a broker with a capability gap Rahdar could close over the management API
- **THEN** the preview carries the same recommendations the registered cluster would show, seeded from the node the check reached, presented as a preview that cannot yet be declared
- **AND** registering then lands the operator on that cluster's recommended configuration, where the same panel can be declared and applied

#### Scenario: Preview topology renders like a saved cluster's
- **WHEN** the registration UI receives a dry-run preview
- **THEN** it lists the nodes it found in a table, without drawing a graph
- **AND** once the cluster is registered, its Topology page draws the discovered nodes with the same graph presentation as every registered cluster

### Requirement: Every mutating call is audited within its transaction

For each mutating endpoint the system SHALL write an audit event in the same
database transaction as the command. The event SHALL be recorded as pending
before the broker is contacted and updated to a success or failure outcome
afterward, and SHALL capture the action, target, affected count where
applicable, and dry-run flag.

#### Scenario: Successful registration

- **WHEN** a cluster is registered successfully
- **THEN** exactly one audit event exists for it, transitioning from pending to success

#### Scenario: Failed registration

- **WHEN** a registration fails after the audit row is created
- **THEN** the audit event is present with a failure outcome and an error detail

### Requirement: Clusters can be listed, inspected, and removed

The system SHALL expose reading the list of registered clusters with a
rolled-up health indication, reading one cluster with its nodes, and removing a
cluster. Removal SHALL delete Rahdar's registration and stored credentials for
that cluster and SHALL NOT attempt any change on the broker itself. Removal
SHALL also close that cluster's Core connections and discard its in-memory
subscription state.

In the frontend, removal SHALL be offered only from the cluster's settings, as the last section of the
cluster's own settings group, and SHALL NOT be offered from the header shown above the cluster's views.
The section SHALL state what removal deletes and that the broker is not touched before it can be armed.
A failed removal SHALL state its cause and leave the operator on the section; a successful removal SHALL
take the operator away from the removed cluster.

#### Scenario: List shows health

- **WHEN** the cluster list is requested
- **THEN** each entry carries a health summary derived from its nodes

#### Scenario: Removal is local only

- **WHEN** a cluster is removed
- **THEN** its rows and credentials are deleted and no broker operation is invoked

#### Scenario: Removal releases Core connections

- **WHEN** a cluster with an active Core subscription is removed
- **THEN** its Core connections are closed and not reopened

#### Scenario: Removal is guarded in the UI

- **WHEN** a user removes a cluster from the frontend
- **THEN** the UI confirms it by pressing and holding the remove button, never by a click or a typed name

#### Scenario: Removal lives in settings

- **WHEN** an operator views any page of a cluster
- **THEN** the cluster header offers no remove action, and the cluster's settings contain a Remove
  cluster section

#### Scenario: A failed removal says why

- **WHEN** the removal request fails
- **THEN** the section states the cause and the next action, and the cluster remains registered

### Requirement: Cluster registration and listing require the matching permission

Registering a cluster SHALL require a global write permission. Listing,
inspecting, or removing a cluster SHALL require the corresponding read or
write permission resolved at that cluster's scope, as defined by the
authorization capability.

#### Scenario: Registration requires global write

- **WHEN** a user without global cluster-write permission attempts to
  register a cluster
- **THEN** the request is rejected

#### Scenario: Listing is scoped to granted clusters

- **WHEN** a user holding a grant on only some registered clusters lists
  clusters
- **THEN** only the clusters they hold a read grant on are returned

### Requirement: The broker version is detected on registration
Rahdar SHALL read the broker version of each seed when a cluster is registered, store each node's version, and
show it with where it sits against the supported range.

#### Scenario: Registration
- **WHEN** a cluster is registered
- **THEN** each node's version is recorded and visible

#### Scenario: A broker is upgraded
- **WHEN** a node's broker is upgraded
- **THEN** its recorded version is updated by the next poll, and its range status and any version gate follow the new version

#### Scenario: A node discovered later
- **WHEN** topology discovery adds a node running a version below the minimum
- **THEN** the node is shown with a warning that its release is unsupported, and the cluster stays registered

#### Scenario: No management URL
- **WHEN** a node is known only through the Core protocol and has no management URL
- **THEN** its version is unknown and it is never refused on that account

### Requirement: A broker outside the supported range is warned about or refused
When a seed's version is below the minimum, registration SHALL be refused with the reason, naming the minimum
and the seed's version, and nothing SHALL be stored. When it is above the latest tested version, registration
SHALL succeed with a visible warning.

#### Scenario: Too old
- **WHEN** a seed's version is below the minimum
- **THEN** registration is refused naming the minimum, and the refusal is audited

#### Scenario: Newer than tested
- **WHEN** a node's version is above the latest tested
- **THEN** registration succeeds, the connection check warns that the release is untested, and the node is marked as newer than tested

#### Scenario: Dry run
- **WHEN** registration is a dry run
- **THEN** the same verdict is reported and nothing is stored

### Requirement: A cluster's brokers are registered only once

The system SHALL refuse a registration, and its dry run, when any broker it reaches or discovers already
belongs to a registered cluster. Two registrations are of the same brokers when a node of one reports a
NodeID a node of the other carries, whatever URL reached it; a seed whose broker reports no NodeID is
compared by its management URL in normal form (scheme and host in lower case, the port explicit, no
trailing slash). The refusal SHALL be a 409 problem of type `cluster-already-registered` whose detail names
the registered cluster and its overlapping nodes, with the cluster's id and name as `existingClusterId`
and `existingClusterName` and the nodes as `overlappingNodes`; when the caller may not read that cluster,
the refusal SHALL NOT name it, and neither SHALL its audit record. The refused attempt SHALL be audited as
a failure with the message the caller received, and nothing of it SHALL be stored. The refusal SHALL say
that a cloned or restored broker carries the same NodeID and needs a fresh journal; there is no override.
The database SHALL hold each broker's identity once, so that of two registrations of the same brokers
made at the same time exactly one succeeds and the other receives the same refusal. A registered
cluster's held identities SHALL follow its nodes: discovery, a changed NodeID and a management URL
override SHALL update them in the same transaction, and a held identity no node carries any more SHALL be
released rather than refuse a registration. The registration form SHALL show the refusal with a link to
the registered cluster and keep Register disabled.

#### Scenario: The same management URL

- **WHEN** a cluster is registered from a broker's management URL that a registered cluster already uses
- **THEN** the registration is refused with a 409 naming that cluster and its nodes
- **AND** no second cluster exists, and the refused attempt is audited as a failure

#### Scenario: Another URL of the same broker

- **WHEN** a registration reaches a registered broker through a different URL, such as its IP address
  instead of its host name, another port mapping, or a trailing slash
- **THEN** the broker's NodeID identifies it and the registration is refused naming the registered cluster

#### Scenario: A partial overlap

- **WHEN** a registration's seeds or discovered nodes include one broker of a registered cluster alongside
  brokers that are not registered
- **THEN** the registration is refused naming only the registered cluster's nodes that overlap
- **AND** none of the brokers is registered

#### Scenario: The dry run warns

- **WHEN** the connection check is run for brokers a registered cluster already holds
- **THEN** it answers the same 409 before anything is registered, the dry-run attempt is audited as a failure
- **AND** the form shows which cluster holds the brokers with a link to open it, and Register stays disabled

#### Scenario: Concurrent registrations

- **WHEN** two registrations of the same brokers run at the same time
- **THEN** exactly one cluster is registered
- **AND** the other is refused with the same 409 naming that cluster, not a server error

#### Scenario: A cluster the caller cannot see

- **WHEN** the brokers belong to a cluster the caller has no grant to read
- **THEN** the registration is refused with a 409 that names no cluster, no id and no nodes
- **AND** the audit record of the attempt names no cluster and no node either

#### Scenario: A management URL moved by an override is released

- **WHEN** a registered node known only by its management URL is given another URL by an override
- **THEN** its cluster holds the new URL and no longer holds the old one
- **AND** a broker that later answers at the old URL can be registered

#### Scenario: A held identity no node carries any more

- **WHEN** a cluster still holds a broker's identity but none of its nodes carries it, its node having
  moved or its broker's journal having been replaced
- **THEN** registering that broker succeeds, and the identity passes to the new cluster

#### Scenario: Nodes found after registration are held

- **WHEN** discovery finds a node that joined the cluster after it was registered, or a node reports a
  NodeID other than the stored one
- **THEN** the cluster holds that node's identity from then on

#### Scenario: A cloned broker

- **WHEN** a broker cloned or restored from a registered broker's data directory is registered
- **THEN** it is refused as the same broker, and the refusal says to give it a fresh journal

### Requirement: Registration adopts the running configuration as its first revision

Registration SHALL offer to adopt the cluster's running configuration as its first declared
revision. The dry-run SHALL show what it would declare, as counts per section, and which nodes
disagree. The offer SHALL be on by default when the live nodes agree, and off by default when
they disagree, listing each disagreement. When registration is confirmed with the offer on, the
cluster and the adopted revision SHALL be saved together, attributed to the registering
operator, with source `ADOPTED`, and audited with the registration. Adoption SHALL write nothing
to a broker. After registration the system SHALL NOT adopt on its own.

#### Scenario: Agreeing nodes are adopted at registration
- **WHEN** an operator registers a cluster whose live nodes agree, leaving the adoption on
- **THEN** the cluster is saved with revision 1, source `ADOPTED`, attributed to that operator, and the cluster shows no drift

#### Scenario: Disagreeing nodes are not adopted by default
- **WHEN** the dry-run finds two nodes reporting different values for one key
- **THEN** the adoption is off by default and the disagreement is listed with both nodes and both values

#### Scenario: Declining the adoption
- **WHEN** an operator turns the adoption off and registers
- **THEN** no revision is saved and the configuration view offers adoption as before

### Requirement: A cluster's connection can be edited after registration

An operator with the right to edit the cluster SHALL be able to change its name, description,
seeds, management URL pattern, TLS bundle, management account and Core account
after registration, in one request. Every change SHALL be checkable as a dry-run that runs the
same per-node probes as a registration dry-run and persists nothing. A confirmed change SHALL be
stored and audited in one transaction with secrets redacted, SHALL never return a secret, and
SHALL trigger discovery at once. An omitted secret SHALL keep the stored one, but a stored secret
SHALL never be sent to a host the cluster does not already use: a change to the seeds, the management
URL pattern, the TLS bundle or an account's username SHALL be refused unless that account's password is
entered again. A management URL pattern SHALL have the shape `http(s)://{host}[:port][/path]`, with
`{host}` as the whole host, and an account written into a seed URL SHALL be refused.

#### Scenario: New credentials are checked before they are saved
- **WHEN** an operator enters a new management password and checks it
- **THEN** each node reports whether it accepts the password, and nothing is stored

#### Scenario: Saving a changed pattern rediscovers
- **WHEN** an operator saves a new management URL pattern
- **THEN** the change is audited, and every node whose URL was derived is probed on the new pattern at once

#### Scenario: A new host needs the password again
- **WHEN** an operator changes a seed to a host the cluster does not use and leaves the password empty
- **THEN** the check and the save are refused with "Enter the management password again to check new hosts", and nothing is sent to that host

#### Scenario: A pattern that moves the host is refused
- **WHEN** an operator enters the pattern `http://other.example/?h={host}`
- **THEN** it is refused, because `{host}` must be the whole host of the URL

#### Scenario: A secret left empty is kept
- **WHEN** an operator changes the name and leaves both password fields empty
- **THEN** both stored credentials are unchanged

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
