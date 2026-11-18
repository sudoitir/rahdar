## ADDED Requirements

### Requirement: Sensitive fields are masked at display time
The system SHALL mask sensitive fields at display time in browse, SQL results, live tail and export.

#### Scenario: The same field on each surface
- **WHEN** a record with a sensitive field is browsed, returned from SQL, live-tailed or exported
- **THEN** the field is masked the same way on each surface

### Requirement: Display masking leaves stored records unchanged
Display masking SHALL leave stored records as they are. It SHALL NOT search for records and it SHALL NOT erase them.

#### Scenario: A masked display leaves the record
- **WHEN** a sensitive field is masked on screen
- **THEN** the record stored on the cluster is unchanged, and nothing was searched or erased

### Requirement: An assistant sees the same masking as the UI
MCP tool output SHALL apply the same masking as the UI, so an assistant never sees raw personal data the operator would also have masked.

#### Scenario: An assistant reads a masked field
- **WHEN** an assistant reads a record whose field the UI would mask for that caller
- **THEN** the tool output masks that field the same way
