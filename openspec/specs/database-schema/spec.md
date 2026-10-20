# database-schema Specification

## Purpose
How Rahdar's PostgreSQL schema is laid out and kept honest: UUIDv7 keys so B-tree inserts stay append-only, one baseline changeset per module until the stable release, a primary key on every table, an index under every foreign key, and a catalog lint over the migrated database that enforces it (ADR-0192).

## Requirements

### Requirement: Keys are time-ordered UUIDs unless they are bearer handles
Every table keyed by a UUID SHALL generate the key as UUIDv7 by default, in the database and in the application, so that inserts append to the key index. A key that acts as a bearer handle, such as a query ticket or a diagnostics snapshot id, SHALL stay random (UUIDv4), because a time-ordered value is partly guessable. A bigint identity SHALL be used only for cursors and ordering, such as the audit id and event sequence numbers.

#### Scenario: A row is inserted without an id
- **WHEN** a row is inserted into a UUID-keyed table without a key
- **THEN** the database assigns a UUIDv7

#### Scenario: The application creates an id first
- **WHEN** the application needs a row's id before inserting it
- **THEN** it takes a UUIDv7 from one shared source, and not from a database round trip

#### Scenario: A bearer handle is issued
- **WHEN** Rahdar issues a ticket or snapshot id that grants access to a result
- **THEN** the value is a random UUIDv4

### Requirement: Every table has a primary key and every foreign key is indexed
Every table SHALL have a primary key. Every foreign key SHALL have an index whose leading columns are the key's columns. A partitioned table's primary key SHALL include its partition column.

#### Scenario: A table has no primary key
- **WHEN** a migration creates a table without a primary key
- **THEN** the schema lint fails the build

#### Scenario: A foreign key has no index
- **WHEN** a migration adds a foreign key whose columns no index leads with
- **THEN** the schema lint fails the build

### Requirement: Column types follow one set of rules
Timestamps SHALL be `timestamptz`. Structured data SHALL be `jsonb`, not `json`. Generated numbers SHALL use identity columns, not `serial`. Text SHALL be `text`, not `varchar(n)`. Random UUID generation SHALL NOT be used as a default for a key.

#### Scenario: A banned type is used
- **WHEN** a migration uses `timestamp` without a time zone, `json`, `serial` or `varchar(n)`
- **THEN** the schema lint fails the build

#### Scenario: A table is exempt
- **WHEN** a table must break a rule, such as a table owned by a library
- **THEN** it is named in one allow-list with the reason, and the lint passes only for that table

### Requirement: A schema lint fails the build on a violation
The build SHALL run a lint over the migrated database catalog that checks every rule of this capability, including that no table has a default partition. The lint SHALL run in the standard test suite, and a violation SHALL fail it with the table and rule named.

#### Scenario: A violation is introduced
- **WHEN** a change adds a table that breaks a rule
- **THEN** the standard test run fails and names the table and the rule

#### Scenario: The schema is clean
- **WHEN** every table follows the rules or is on the allow-list
- **THEN** the lint passes

### Requirement: Migrations run under a session-scoped lock and fail fast
Schema migrations SHALL take a lock scoped to the database session, so that a replica that crashes mid-migration releases it with its connection. Migrations SHALL set a lock timeout, so that a migration that waits on another session's lock fails with a clear error instead of waiting without end.

#### Scenario: Two replicas start together
- **WHEN** two replicas start against an empty database
- **THEN** one migrates and the other waits, then finds the schema current

#### Scenario: The migrating replica crashes
- **WHEN** the replica holding the migration lock dies
- **THEN** the lock is released with its connection and another replica can migrate

#### Scenario: A migration meets a long lock
- **WHEN** a migration needs a lock that another session holds beyond the lock timeout
- **THEN** the migration fails with an error naming the wait, and does not block

### Requirement: One Postgres configuration is shipped and used by every deployment
Rahdar SHALL ship one Postgres configuration file, and the production, HA and development compose files SHALL all mount it. It SHALL set at least statement statistics, lock-wait logging, I/O timing, UTC time zone, autovacuum, an idle-in-transaction timeout and the connection limit. The documented Postgres version SHALL be the one the file is written for.

#### Scenario: Two deployments, one configuration
- **WHEN** the production and development compose files are rendered
- **THEN** both mount the same configuration file and neither carries its own list of server flags

#### Scenario: Statement statistics are available
- **WHEN** an operator queries the database of a shipped deployment
- **THEN** statement statistics are present without extra setup

### Requirement: Database connections are bounded in count and time
Rahdar SHALL size its connection pool so that every replica together stays under the server's connection limit. Connections SHALL set a statement timeout, a lock timeout and an idle-in-transaction timeout. The driver SHALL set a connect timeout and a socket timeout. The pool SHALL fail a request that waits too long for a connection, and not queue it without end. Work that is legitimately longer, such as the SQL console, exports, the lifecycle preview and partition maintenance, SHALL raise the statement timeout for its own transaction only.

#### Scenario: A query runs too long
- **WHEN** an ordinary query runs past the statement timeout
- **THEN** the database cancels it and the caller receives an error

#### Scenario: The pool is exhausted
- **WHEN** every connection is in use and another request needs one
- **THEN** that request fails after the pool wait with a clear error, and the pool is not queued without end

#### Scenario: A long export
- **WHEN** an export legitimately needs longer than the statement timeout
- **THEN** it raises the timeout for its own transaction, and other connections keep the ordinary one

#### Scenario: A transaction is abandoned
- **WHEN** a session holds a transaction open and idle beyond the idle-in-transaction timeout
- **THEN** the database ends the session and its locks are released

### Requirement: Rahdar runs on an external PostgreSQL 18 it does not administer
Rahdar SHALL run against an operator-provided PostgreSQL 18 or later, in any schema, as a non-superuser that owns its schema and may create schemas, without depending on server settings or extensions.

#### Scenario: A managed database in its own schema
- **WHEN** Rahdar is configured with a JDBC URL whose current schema is not `public` and a role without superuser
- **THEN** it migrates and starts, and every table it creates is in that schema

#### Scenario: Server settings differ from the bundled database
- **WHEN** the server's own timeouts or autovacuum settings differ from the shipped configuration
- **THEN** Rahdar's connection limits still apply, because Rahdar sets them on each connection

### Requirement: The boot refuses a database Rahdar cannot run on
Rahdar SHALL check the database before migrating, and SHALL fail the boot with a message naming the fix when the server is older than 18, a required privilege is missing, or the connection does not keep session state across transactions.

#### Scenario: Transaction-mode pooling
- **WHEN** Rahdar connects through a pooler in transaction mode
- **THEN** the boot fails and the message says that session-mode pooling or a direct connection is required

#### Scenario: Too few connections for the budget
- **WHEN** the server's connection limit cannot hold Rahdar's configured pools
- **THEN** Rahdar starts, and logs and reports a warning with the budget and the limit

### Requirement: Rahdar rides through a database failover
Rahdar SHALL accept a multi-host connection that targets the primary, SHALL recover its pools after a failover without a restart, and SHALL treat every session-scoped lock or listener on a dropped connection as lost.

#### Scenario: The primary fails over
- **WHEN** the database primary changes
- **THEN** requests fail fast during the switch and succeed again afterwards, the change listener reconnects and caches resync, and a duty held by a session lock stops until the lock is reacquired

### Requirement: Heavy reads may use a read replica
When a read-only database is configured, Rahdar SHALL serve heavy history reads and exports from it while its replication lag is within a bound, SHALL fall back to the primary otherwise, and SHALL say in the response that the data may lag.

#### Scenario: The replica lags too far
- **WHEN** the replica's lag exceeds the bound or it cannot be reached
- **THEN** the read is served by the primary

#### Scenario: Reads after writes
- **WHEN** a request reads what it has just written
- **THEN** it reads from the primary

### Requirement: The schema uses broker-neutral names
Tables, columns and stored enum values of shared modules SHALL use the SDK's neutral vocabulary (system, destination, subscription, kind, plane, native id) and SHALL store native details as namespaced attributes. A system module SHALL own its tables under its system's prefix (`artemis_`).

#### Scenario: A fresh install
- **WHEN** Rahdar creates its schema
- **THEN** no table, column or stored enum value of a shared module names an Artemis concept, and every Artemis-only table starts with `artemis_`
- **AND** the one exception is the resource kinds stored with team patterns, team shares and API token grants (QUEUE, ADDRESS, BOTH), which change together with the permission identifiers they scope

### Requirement: Clusters and nodes are stored in neutral terms
Every cluster SHALL record its system. Node endpoints SHALL be stored per plane and protocol, not as system-specific URL columns. A node's configured HA designation SHALL be stored apart from whether it is serving.

#### Scenario: A node with two planes
- **WHEN** an Artemis node is registered
- **THEN** its management and data endpoints are stored as two endpoint rows, each with its plane and protocol

### Requirement: Stored metric names are the standard keys
Metric history and alert rules SHALL store the SDK's standard metric keys.

#### Scenario: Metric history
- **WHEN** the scrape writes a subscription's backlog
- **THEN** the sample is stored under `openbroker.backlog`
