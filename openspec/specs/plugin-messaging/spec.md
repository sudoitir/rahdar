# plugin-messaging Specification

## Purpose
How a runtime plugin receives and sends broker messages through Rahdar: taps, consumers and sends that Rahdar owns end to end, acting for a user whose permissions it keeps checking (ADR-0111).

## Requirements

### Requirement: A plugin can tap a subscription without disturbing it

The system SHALL let a plugin register a tap on a subscription of a registered cluster, naming the subscription with a Rahdar-owned resource reference (a resource type and a name). The tap SHALL deliver to the plugin a copy of each message routed to that subscription, each identified by a Rahdar-owned message reference (the node and the position). It SHALL NOT remove messages from the subscription, change what existing consumers receive, or block or slow a producer. When the plugin cannot keep up, the tap SHALL drop the oldest copies within a stated bound, and the system SHALL report how many it dropped rather than grow without limit.

#### Scenario: Existing consumers are unaffected
- **WHEN** a plugin taps a subscription that has an application consumer
- **THEN** the application consumer receives every message exactly as before, and the plugin receives a copy of each

#### Scenario: A slow plugin never blocks producers
- **WHEN** the plugin stops processing while producers keep sending
- **THEN** producers are not blocked, and the system reports the number of dropped copies

### Requirement: A plugin can consume a subscription with explicit acknowledgement

The system SHALL let a plugin register a consumer on a subscription, named by a resource reference. The consumer SHALL receive each message and SHALL acknowledge, reject or release it. A message that the plugin rejects, fails on, or does not acknowledge, including when the plugin or Rahdar stops, SHALL remain available for redelivery, bounded by the broker's own delivery-attempt limit. A message that the plugin releases SHALL return to the subscription without counting as a delivery attempt, so that a release never brings it closer to the broker's dead-letter destination. A tapped copy that is released SHALL be discarded, as a rejected one is.

#### Scenario: A rejected message is redelivered
- **WHEN** a plugin rejects a message it consumed
- **THEN** the broker delivers it again

#### Scenario: An unacknowledged message is redelivered
- **WHEN** Rahdar stops while a plugin holds a received message it has not acknowledged
- **THEN** the message is delivered again after the consumer is back

#### Scenario: A released message never spends a delivery attempt
- **WHEN** a plugin releases the same consumed message more times than the broker's delivery-attempt limit
- **THEN** the message is still on its subscription, never on the dead-letter destination, and its delivery count did not grow

#### Scenario: A rejected message still runs out of attempts
- **WHEN** a plugin rejects the same consumed message as many times as the broker's delivery-attempt limit
- **THEN** the broker moves it to its dead-letter destination

### Requirement: A plugin can send a message

The system SHALL let a plugin send a message to a send target of a registered cluster, with a body, headers and properties. A send target SHALL be either a destination, or one subscription directly where the cluster's system supports it, each named by a resource reference. A send target of a kind the system cannot send to SHALL be refused with the reason.

#### Scenario: A sent message arrives with its headers
- **WHEN** a plugin sends a message with a header to a destination
- **THEN** a consumer of that destination receives the body and the header unchanged

#### Scenario: A send straight to a subscription
- **WHEN** a plugin sends to a subscription on a system that supports it
- **THEN** the message arrives on that subscription only

#### Scenario: An unsupported send target is refused
- **WHEN** a plugin sends to a target whose kind the cluster's system cannot send to
- **THEN** the send is refused with the reason, and nothing is sent

### Requirement: Rahdar owns messaging resources and threads

The system SHALL own every connection, thread and broker object used for plugin messaging. It SHALL cover every node of a clustered broker, and it SHALL report per node when a node cannot be reached. It SHALL process each registration exactly once when several Rahdar instances share a cluster. It SHALL remove every broker object that a registration created when the registration is removed or the plugin is disabled or uninstalled. After a crash, it SHALL reclaim those objects on the next start.

#### Scenario: Uninstall leaves nothing on the broker
- **WHEN** a plugin with an active tap is uninstalled
- **THEN** no broker object created for that tap remains

#### Scenario: Two Rahdar instances do not double-deliver
- **WHEN** two Rahdar instances share a database and a cluster that has one registered consumer
- **THEN** each message is handed to the plugin once

#### Scenario: An unreachable node is reported
- **WHEN** one node of a clustered broker is down
- **THEN** the registration reports that node as not covered, and it keeps working on the other nodes

### Requirement: Plugin messaging honours cluster permissions

Every registration and send SHALL name an acting user. The system SHALL allow a tap only when that user holds `message:read` on the cluster, a consumer only with `message:read` and `subscription:purge`, and a send only with `message:send`. It SHALL re-check the user's current grants on every reconciliation. When the user is removed or loses a permission, the registration SHALL be suspended: its delivery stops, its broker objects are removed, and its state names the user, the permission and the cluster. It SHALL resume by itself when the permission returns. The system SHALL refuse a registration on objects that Rahdar reserves for itself.

#### Scenario: A plugin cannot tap a cluster its operator cannot read
- **WHEN** a plugin registers a tap, acting for a user without access to that cluster
- **THEN** the registration is refused with a reason

#### Scenario: A lost grant suspends the registration with a reason
- **WHEN** the acting user of an active tap loses `message:read` on its cluster
- **THEN** within one reconciliation the tap stops, its broker objects are removed, and the registration reports it is suspended because that user lacks `message:read` on that cluster

#### Scenario: Rahdar's own objects cannot be tapped
- **WHEN** a plugin registers a tap on a subscription under Rahdar's reserved prefix
- **THEN** the registration is refused with a reason

### Requirement: A plugin's registrations are scoped to that plugin
The system SHALL bind every messaging operation to the plugin that performs it. A plugin SHALL NOT see, change or remove another plugin's registrations, and SHALL receive deliveries only for its own.

#### Scenario: Registrations are isolated
- **WHEN** two plugins each register under the same key
- **THEN** each sees only its own registration, and each receives only its own deliveries

### Requirement: A plugin can check a send before making it

The system SHALL let a plugin ask whether a send to a send target of a cluster would be allowed for a given user, without sending. The answer SHALL be the same reason a send would be refused with (a missing or too-long name, a target reserved for Rahdar or the broker, a target kind the system cannot send to, an unknown cluster, or a missing `message:send` permission), or none. A send SHALL apply the same check.

#### Scenario: A reserved destination is reported before sending
- **WHEN** a plugin checks a send to a destination under Rahdar's reserved prefix
- **THEN** it gets the reason the destination is reserved, and nothing is sent

#### Scenario: A user without send permission is reported
- **WHEN** a plugin checks a send for a user who lacks `message:send` on the cluster
- **THEN** it gets a reason naming the missing permission

### Requirement: Plugin consumers set their concurrency and get flow control

A consume registration SHALL state how many messages it processes at once per serving node (1 to 32). Rahdar SHALL open that many consumers per node. Each consumer SHALL hold at most one unacknowledged message, so a slow plugin receives fewer messages and the rest stay on the subscription. A tap registration SHALL have a concurrency of exactly 1. Any other value SHALL be refused, and the refusal SHALL give the reason.

#### Scenario: Concurrency runs handlers in parallel
- **WHEN** a consume registration with concurrency 4 receives 4 messages whose handling blocks
- **THEN** all 4 handlers run at the same time

#### Scenario: A slow consumer is not flooded
- **WHEN** a consume registration with concurrency 3 stops returning from its handler while 100 messages wait
- **THEN** at most 3 messages per node are delivered and unacknowledged, and the other 97 stay on the subscription

#### Scenario: Invalid concurrency is refused
- **WHEN** a plugin registers a consumer with concurrency 0 or 33, or a tap with concurrency 2
- **THEN** the registration is refused with the reason

#### Scenario: A changed concurrency takes effect
- **WHEN** a plugin registers the same key again with another concurrency
- **THEN** its consumers on each node are restarted with the new count

### Requirement: Message groups stay in order across concurrent consumers

When a consume registration has a concurrency above 1, messages that carry the same message group id SHALL be handled one at a time and in order on each node, as the broker's message grouping provides.

#### Scenario: Group order kept at concurrency above 1
- **WHEN** messages 1, 2 and 3 of group G reach a registration with concurrency 4, and the handling of message 1 is slow
- **THEN** message 2 is handled only after message 1 is settled, and message 3 only after message 2

### Requirement: Plugin consumers cannot starve Rahdar

Plugin message handling SHALL run on a bounded thread pool of its own. Plugin handlers that block SHALL NOT delay message capture, operator sessions, or other Core client work in Rahdar.

#### Scenario: A blocked plugin does not stall capture
- **WHEN** a plugin handler blocks every message at the full concurrency of several registrations
- **THEN** message capture on the same cluster keeps storing copies without delay

### Requirement: Plugin messaging uses Rahdar's own neutral types
The plugin contract SHALL describe message sources, message references, send targets and resource references with Rahdar-owned types in neutral vocabulary (a resource reference of a resource type and a name, and a message reference of a node and a position), not with one system's queue and address names and not with a broker SDK's types. The contract version SHALL be bumped with this break.

#### Scenario: A plugin registers on a source
- **WHEN** a plugin registers to receive a subscription's messages
- **THEN** it names the subscription by resource type and name, and receives messages with a message reference

#### Scenario: The plugin API names no broker SDK type
- **WHEN** a plugin author reads the messaging part of the plugin API
- **THEN** every type in it is Rahdar's own

### Requirement: A plugin learns that a send is in doubt
A plugin's send whose confirmation never came back SHALL throw `MessageInDoubtException`, stating that the message may or may not have been delivered, and SHALL be audited as in doubt. It SHALL never be reported to the plugin as a success or as a failure.

#### Scenario: A plugin's send in doubt
- **WHEN** a plugin sends a message and the connection is lost before the broker confirms it
- **THEN** `send` throws `MessageInDoubtException`, and the audit records the plugin's send with the outcome `IN_DOUBT`
