## ADDED Requirements

### Requirement: Permissions for offsets, truncation, configuration, access rules and transactions
The system SHALL define, named by role, distinct permissions to move a subscription's position (`subscription:seek`), truncate a destination (`destination:truncate`), update a destination's configuration (`destination:update`), read and write access rules (`access-rule:read`, `access-rule:write`) and read and abort transactions (`transaction:read`, `transaction:abort`). Each SHALL be scoped like every other permission.

#### Scenario: Seek does not imply purge
- **WHEN** a role holds `subscription:seek` but not `subscription:purge`
- **THEN** its holder can reset a group's offsets and cannot purge a subscription

### Requirement: The built-in roles hold the new permissions
The built-in operator role SHALL hold the new permissions except `access-rule:write`, the administrator SHALL hold all of them, and the viewer SHALL hold the read permissions. Each SHALL be listed in the catalogue with its label.

#### Scenario: An operator cannot change access rules
- **WHEN** an operator with the built-in operator role previews removing an ACL
- **THEN** the action is disabled naming `access-rule:write`, and the API refuses it
