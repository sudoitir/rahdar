# estate-view Specification

## Purpose
TBD - created by archiving change 19d-broker-neutral-surface. Update Purpose after archive.

## Requirements

### Requirement: An estate view across systems
The UI SHALL show clusters of every connected system side by side on its landing page, with comparable health, and a list of subscriptions across them. Shared screens SHALL use role nouns, with each row's native kind label. An action a system cannot perform SHALL appear disabled with the reason from the capability model, never hidden without explanation and never failing after the click. System-only screens SHALL appear only for clusters of that system.

#### Scenario: A mixed estate
- **WHEN** an operator opens the estate view with an Artemis cluster and a cluster of another system
- **THEN** both are listed with comparable health from their conditions, and the subscriptions table shows rows from both with their native kinds

#### Scenario: An action the system cannot perform
- **WHEN** an operator selects a message on a system that cannot move messages
- **THEN** the move action is disabled and says why

#### Scenario: A disabled action says why, in order
- **WHEN** an action is unavailable for more than one reason
- **THEN** the shown reason is the first of: the kind lacks the verb, a capability is unavailable, a permission is missing, and a missing permission names the permission

#### Scenario: An empty estate
- **WHEN** an operator with no registered cluster opens the estate view
- **THEN** it explains that a cluster is a set of brokers of one system and offers to register one

#### Scenario: A cluster that cannot be reached
- **WHEN** one cluster of the estate cannot be reached
- **THEN** its row states that in words and the rest of the estate is shown, and its subscriptions are noted as unavailable rather than the page failing

#### Scenario: A filter that matches nothing
- **WHEN** an operator filters the subscriptions so that none match
- **THEN** the table states that no subscriptions match the filter and offers to clear it, distinct from an estate that has none

#### Scenario: Only what the caller may read
- **WHEN** a team member opens the estate view
- **THEN** the clusters and subscriptions listed are only those the member may read

#### Scenario: A system-only screen is absent elsewhere
- **WHEN** an operator opens a cluster that is not Artemis
- **THEN** the Artemis-only screens (broker configuration, configuration diff, setup review) are absent from its navigation, and opening one by address shows not found
