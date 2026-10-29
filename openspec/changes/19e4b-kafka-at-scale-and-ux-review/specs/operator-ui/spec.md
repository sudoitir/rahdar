## ADDED Requirements

### Requirement: Every Kafka screen passes the visual sweep in every state
Every Kafka screen and every shared screen on a Kafka cluster SHALL pass the visual QA sweep at 1440 and 1920 wide, in light and dark, in loading, empty, filtered-empty, error, unavailable and no-permission states, with no console error, no failed request, no overflow and no accessibility violation.

#### Scenario: The sweep ends clean
- **WHEN** the sweep runs over the Kafka and shared routes
- **THEN** no capture fails a check

### Requirement: The Kafka flows meet an independent design review
The registration, offset reset, truncate, ACL change, transaction abort, browse and bulk purge flows SHALL be reviewed independently against an enterprise-grade bar, and every finding SHALL be fixed before the change merges.

#### Scenario: A review finding
- **WHEN** the review finds a flow that misleads an operator or hides a consequence
- **THEN** the flow is fixed and the finding is recorded as closed
