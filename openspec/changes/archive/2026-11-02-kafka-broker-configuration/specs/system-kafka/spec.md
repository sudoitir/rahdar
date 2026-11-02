## ADDED Requirements

### Requirement: Broker configuration is viewed and changed safely

The system SHALL show each Kafka broker's configuration and the cluster-wide default, each value with its source,
marking the settings that take effect only after a restart. A change to a dynamic setting SHALL be previewed as a
diff, confirmed, applied with the preview's etag, held by the approval gate and audited. A restart-only setting SHALL
be refused with the reason, and a secret setting SHALL be refused.

#### Scenario: Changing the cluster default

- **WHEN** an operator previews and confirms a new value for a dynamic setting on the cluster default
- **THEN** the setting is applied, audited, and read back with the cluster as its source

#### Scenario: A restart-only setting

- **WHEN** an operator tries to change a setting that only changes on a restart
- **THEN** the change is refused with a 409 that says the broker must be restarted with the new value
