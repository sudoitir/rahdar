## ADDED Requirements

### Requirement: An action whose outcome is unknown is audited in doubt
An audit row SHALL take the outcome `IN_DOUBT` when its action's confirmation never came back, so the action may or may not have happened. The audit screen SHALL show it as "in doubt", in words, and SHALL offer it as an outcome filter. Audit receivers SHALL see the same outcome.

#### Scenario: An unconfirmed send in the audit trail
- **WHEN** a send is left in doubt
- **THEN** its audit row has the outcome `IN_DOUBT` with the reason, the audit screen shows "in doubt", and filtering by "In doubt" finds it
