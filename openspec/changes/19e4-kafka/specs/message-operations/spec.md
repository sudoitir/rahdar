## ADDED Requirements

### Requirement: A partitioned log is browsed by partition, offset or time
On a system whose destinations are partitioned logs, browsing SHALL let the operator pick a partition and start from the earliest offset, the latest records, an offset or a time. Browsing SHALL never join a group or change an offset, and SHALL be bounded by count and bytes like every browse.

#### Scenario: Browsing from a time
- **WHEN** an operator browses partition 2 of a Kafka topic from 09:00
- **THEN** the records from the first offset at or after 09:00 are shown, and no group's offsets change

### Requirement: Purge and truncate state their reach on a partitioned log
On a partitioned log, purging a subscription SHALL move only its group to the end, and only while the group is empty. Truncating a topic SHALL remove records for every group. Both previews SHALL say so in words, naming the active members that block a purge and every group that loses unread records in a truncate, with how many. Both SHALL be held destructive confirmations and SHALL go through the approval gate when a policy holds them.

#### Scenario: Truncating a shared topic
- **WHEN** an operator previews truncating a topic that three groups read
- **THEN** the preview names the three groups and the unread records each loses, and confirming needs a press and hold

#### Scenario: Purging an active group
- **WHEN** an operator previews purging a subscription whose group has members
- **THEN** the preview names the members, and purge is disabled with the reason

### Requirement: Per-message actions the system lacks are disabled with the reason
Where a system cannot move, copy or delete a single message, those actions SHALL be shown disabled with the driver's reason, on the message and in bulk, and the API SHALL refuse them with the same reason.

#### Scenario: Deleting a Kafka record
- **WHEN** an operator selects a Kafka record
- **THEN** delete, move and copy are disabled, and each says that Kafka's log does not delete single records
