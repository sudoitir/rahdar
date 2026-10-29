## ADDED Requirements

### Requirement: A partitioned log is browsed by partition, offset or time
On a system whose destinations are partitioned logs, browsing SHALL let the operator pick a partition and start from the earliest offset, the latest records, an offset or a time, with the time shown in the operator's time zone. Browsing SHALL never join a group or change an offset, and SHALL be bounded by count and bytes like every browse. 
#### Scenario: Browsing from a time
- **WHEN** an operator browses partition 2 of a Kafka topic from 09:00
- **THEN** the records from the first offset at or after 09:00 are shown, and no group's offsets change

### Requirement: A partitioned browse states where the log begins and ends
A start past the end SHALL show no records and name the end offset; a start before the log's first offset SHALL begin at it and say that retention removed the earlier records. A record with no value SHALL read as a tombstone. Uncommitted and aborted transactional records SHALL not be shown, and the view SHALL say so.

#### Scenario: An offset past the end
- **WHEN** an operator browses partition 0 from offset 900 and its end offset is 750
- **THEN** no records are shown, and the view says the partition ends at offset 750

#### Scenario: Records removed by retention
- **WHEN** an operator browses from offset 10 and the partition's first offset is 400
- **THEN** the records from offset 400 are shown with a notice that retention removed the earlier ones

#### Scenario: A tombstone
- **WHEN** a browsed record has a key and no value
- **THEN** it reads "Tombstone (no value)", never as an empty body

### Requirement: A browse filter the system lacks is disabled with the reason
Where the system cannot filter a browse, the filter SHALL be disabled with the reason and the API SHALL refuse a filter with it.

#### Scenario: No filter on Kafka
- **WHEN** an operator browses a Kafka topic
- **THEN** the filter input is disabled with the driver's reason, and a filter sent to the API is refused with it

### Requirement: Purge and truncate state their reach on a partitioned log
On a partitioned log, purging a subscription SHALL move only its group to the end, and only while the group is empty and not rebalancing. Truncating a topic or a partition, before an offset or a time, SHALL remove records for every group. Both previews SHALL say so in words, naming the active members that block a purge and every group that loses unread records in a truncate, with how many. Truncating a compacted topic SHALL be refused with the reason.

#### Scenario: Truncating a shared topic
- **WHEN** an operator previews truncating a topic that three groups read
- **THEN** the preview names the three groups and the unread records each loses, and confirming needs a press and hold

#### Scenario: Purging an active group
- **WHEN** an operator previews purging a subscription whose group has members
- **THEN** the preview names the members, and purge is disabled with the reason

#### Scenario: A compacted topic
- **WHEN** an operator previews truncating a compacted topic
- **THEN** the preview says compacted topics cannot be truncated, and truncate is disabled with that reason

### Requirement: Purge and truncate are held, gated and audited
Purge and truncate SHALL be held destructive confirmations, SHALL go through the approval gate when a policy holds them, and SHALL be audited with the offsets before and after. Truncating SHALL need the destination truncate permission.

#### Scenario: Truncating without permission
- **WHEN** an operator without the destination truncate permission opens a topic
- **THEN** truncate is shown disabled naming the missing permission, and the API refuses it

### Requirement: Per-message actions the system lacks are disabled with the reason
Where a system cannot move, copy or delete a single message, those actions SHALL be shown disabled with the driver's reason, on the message and in bulk, and the API SHALL refuse them with the same reason. A shared screen SHALL gate a system's own actions by what the kind declares, never by naming a system.

#### Scenario: Deleting a Kafka record
- **WHEN** an operator selects a Kafka record
- **THEN** delete, move and copy are disabled, and each says that Kafka's log does not delete single records

#### Scenario: The API refuses it too
- **WHEN** a client asks to delete a Kafka record by id
- **THEN** the request is refused with the same reason and nothing is audited as attempted on the broker
