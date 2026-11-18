## ADDED Requirements

### Requirement: A Kafka record can be produced from the console
The console SHALL produce a record onto a topic with a key, a value, headers and a chosen serde.

#### Scenario: A record with a key, headers and a serde
- **WHEN** an operator produces a record with a key, a value, headers and a serde
- **THEN** the topic holds that record with that key, value, headers and serde

### Requirement: A browsed Kafka record shows its headers
A browsed Kafka record SHALL show its headers.

#### Scenario: Headers on a record
- **WHEN** an operator browses a record that has headers
- **THEN** those headers are shown

### Requirement: A topic or a partition can be tailed without a group
The console SHALL live-tail a topic or one partition without joining a consumer group and without changing any group's offsets.

#### Scenario: Tailing one partition
- **WHEN** an operator live-tails one partition
- **THEN** records that arrive are shown, no group is joined, and no group's offsets change

#### Scenario: Tailing a topic
- **WHEN** an operator live-tails a topic
- **THEN** records that arrive on its partitions are shown, and no group's offsets change

### Requirement: A Kafka browse and tail take a filter expression
A Kafka browse and its live tail SHALL accept a filter expression and show only the records that match. The SQL console SHALL remain the query product.

#### Scenario: A filter on a browse
- **WHEN** an operator filters a browse with an expression
- **THEN** only matching records are shown

#### Scenario: A filter on a tail
- **WHEN** an operator filters a live tail with an expression
- **THEN** only matching records that arrive are shown

#### Scenario: SQL stays the query product
- **WHEN** an operator wants to query records
- **THEN** the SQL console is still where a query is run

### Requirement: The browsed page can be exported
The console SHALL export the page of Kafka records the operator is browsing.

#### Scenario: Exporting the page
- **WHEN** an operator exports the page they are browsing
- **THEN** the export contains that page and not the rest of the topic

### Requirement: Producing a record is previewed, confirmed, gated and audited
Producing a record, including onto a topic that is not a dev topic, SHALL show a preview that changes nothing, refuse a stale preview, confirm with a press-and-hold, pass the approval gate where it is on, and write an audit event with before and after.

#### Scenario: A preview writes nothing
- **WHEN** an operator previews a produce
- **THEN** no record is written

#### Scenario: A stale preview is refused
- **WHEN** the operator applies a stale preview
- **THEN** the produce is refused and no record is written

#### Scenario: The gate is on
- **WHEN** the operator confirms with a press-and-hold and the approval gate is on
- **THEN** the record is written only after approval, and the audit event records before and after

#### Scenario: The gate is off
- **WHEN** the operator confirms with a press-and-hold and the approval gate is off
- **THEN** the record is written and the audit event records before and after

#### Scenario: A topic that is not a dev topic
- **WHEN** the operator produces onto a topic that is not a dev topic
- **THEN** the same preview, refusal, press-and-hold, gate and audit apply

### Requirement: Produce is its own permission
Produce SHALL be its own permission, so a role can stay read-only. When the role lacks it, or the topic cannot accept a record, produce SHALL stay visible and disabled, with the system's reason, and the API SHALL refuse it.

#### Scenario: A read-only role
- **WHEN** an operator whose role cannot produce opens a topic
- **THEN** produce stays visible and disabled, with the reason, and the API refuses a produce

#### Scenario: A topic that cannot accept a record
- **WHEN** the topic cannot accept a produce
- **THEN** produce stays visible and disabled, with the system's reason, and the API refuses it
