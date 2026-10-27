## ADDED Requirements

### Requirement: A plugin learns that a send is in doubt
A plugin's send whose confirmation never came back SHALL throw `MessageInDoubtException`, stating that the message may or may not have been delivered, and SHALL be audited as in doubt. It SHALL never be reported to the plugin as a success or as a failure.

#### Scenario: A plugin's send in doubt
- **WHEN** a plugin sends a message and the connection is lost before the broker confirms it
- **THEN** `send` throws `MessageInDoubtException`, and the audit records the plugin's send with the outcome `IN_DOUBT`
