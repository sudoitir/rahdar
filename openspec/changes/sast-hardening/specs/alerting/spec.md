## ADDED Requirements

### Requirement: A channel's destination is checked, and an internal address is refused

The system SHALL check a notification channel's destination before the server fetches it: the URL SHALL be
`https`, SHALL name a host, SHALL carry no user information, and SHALL NOT resolve to any-local, loopback,
link-local, site-local, unique-local, multicast or carrier-grade NAT (`100.64.0.0/10`) addresses. The check SHALL
run when a channel is created or updated, when it is tested, and before every delivery, so a channel stored before
the rule existed is checked as well. A refused delivery SHALL be recorded dead with the reason, and a refused test
SHALL return an unsuccessful result rather than performing the request. A destination that does not resolve SHALL
be accepted and left to the connection, where a failure stays retryable. An email channel's SMTP host SHALL NOT be
subject to this check.

#### Scenario: A webhook pointed at the loopback interface is refused

- **WHEN** a channel is created with a destination that resolves to `127.0.0.1`, a private range, or a link-local
  address
- **THEN** the request is refused with a message naming the field, and no channel is stored

#### Scenario: A stored channel pointed inside the network does not deliver

- **WHEN** a delivery is claimed for a channel whose destination resolves to an internal address
- **THEN** the delivery is marked dead with the reason, and no request is made to that address

#### Scenario: A test of an internal destination reports failure without fetching

- **WHEN** an operator tests a channel whose destination is internal
- **THEN** the result is unsuccessful and permanent, and the address is not contacted

#### Scenario: Plain HTTP is refused

- **WHEN** a channel is created with an `http://` destination
- **THEN** the request is refused, because the destination must be `https`

#### Scenario: An on-premise SMTP relay still works

- **WHEN** an email channel names an SMTP host on the private network
- **THEN** it is accepted, subject to its port, security mode and recipient checks
