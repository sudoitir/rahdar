## MODIFIED Requirements

### Requirement: An unavailable figure is stated, never shown as zero

Where a count, total or estimate that a view would show is unavailable, the view SHALL state
that it is unavailable and why. This covers a browse total, a capture loss estimate, a
capture footprint, and a Kafka depth that was not reported, including a subscription backlog
and a message index held count. It SHALL NOT show zero, a dash that reads as zero, or omit the figure.

#### Scenario: A browse without a total says so

- **WHEN** a browse response states that its total is unavailable
- **THEN** the message view says the total is unavailable and why, and shows no number in its place

#### Scenario: A filtered capture's loss says it cannot be estimated

- **WHEN** a capture subscription's loss estimate is unavailable
- **THEN** the subscription view says the estimate is unavailable and why

#### Scenario: A depth that was not reported

- **WHEN** a Kafka subscription or a message index has no reported depth
- **THEN** the view says the depth is unavailable and shows no zero in its place

## ADDED Requirements

### Requirement: Every Kafka screen passes the visual sweep in every state
Every Kafka screen and every shared screen on a Kafka cluster SHALL pass the visual QA sweep at 1440 and 1920 wide, in light and dark, in loading, empty, filtered-empty, error, unavailable and no-permission states, with no console error, no failed request, no overflow and no accessibility violation. The sweep SHALL also cover 1280 wide and 200% zoom.

#### Scenario: The sweep ends clean
- **WHEN** the sweep runs over the Kafka and shared routes
- **THEN** no capture fails a check

### Requirement: The Kafka flows meet an independent design review
The registration, offset reset, truncate, ACL change, transaction abort, hanging transactions, browse and bulk purge flows SHALL be reviewed independently against a production-grade bar, and every finding SHALL be fixed before the change merges. A finding that is a new Kafka feature SHALL be recorded and SHALL NOT be built in this change.

#### Scenario: A review finding
- **WHEN** the review finds a flow that misleads an operator or hides a consequence
- **THEN** the flow is fixed and the finding is recorded as closed

### Requirement: A Kafka purge confirms the records the group will skip
A purge of a Kafka group, from its row or from a bulk selection, SHALL state the number of records the driver's live purge preview says the group will skip, and SHALL state that no record is deleted. That number SHALL be the records up to the last offset the group can read, and SHALL NOT be the lag from the last scrape. Records an open transaction still holds SHALL be named as staying. A group that has members SHALL be refused by name and SHALL NOT be moved. A group the preview cannot count SHALL show the count as unavailable, SHALL NOT show zero, and SHALL NOT arm the confirmation. A group the preview would not move SHALL NOT be listed as a group the run will move. After the run, every group the preview said it would move SHALL be at the offset the preview counted, and every group it said it would not touch SHALL be unchanged.

#### Scenario: The confirmed count is the live preview
- **WHEN** an operator previews a purge of an empty group
- **THEN** the dialog states the live skip count, says that no record is deleted, and the hold names that count

#### Scenario: An uncounted purge is not armed
- **WHEN** the driver does not report how many records a purge would skip
- **THEN** the dialog says the count is unavailable, shows no zero, and the hold is not armed

#### Scenario: A bulk purge moves only the groups the preview marked
- **WHEN** a bulk purge preview marks some empty groups as acting and refuses or leaves the others
- **THEN** the run moves only the groups it marked as acting, and the others are unchanged
