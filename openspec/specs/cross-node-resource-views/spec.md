# cross-node-resource-views Specification

## Purpose
Defines how Rahdar presents a cluster's queues, addresses, consumers,
sessions, connections, and producers as one view across every logical node —
the merge key, how a live/backup pair is counted once, and how nodes that are
unmanageable or temporarily unreachable are represented rather than hidden.

## Requirements

### Requirement: A live/backup pair is one logical node

The system SHALL treat two endpoints sharing one `NodeID` as a single logical
node and SHALL read resource lists only from the serving endpoint of a pair, so
that a queue present on a live/backup pair is counted once, not twice.

#### Scenario: Backup endpoint is not enumerated

- **WHEN** a cluster has a live/backup pair and the queues view is requested
- **THEN** each queue on that pair appears once, attributed to the one logical node

### Requirement: Subscriptions are aggregated across logical nodes

The system SHALL expose a cluster subscriptions view whose rows are keyed by the
subscription's canonical resource id, each row carrying the resource's kind and
kind label, its name, the destination it reads from, the system's native
attributes (for an Artemis queue, its routing type), per-node counters (message
count, consumer count, delivering count, scheduled count), a rolled-up total
across nodes, and how many of the cluster's logical nodes the subscription is
present on.

#### Scenario: A subscription on two nodes

- **WHEN** the same subscription exists on two logical nodes with different depths
- **THEN** the row shows both per-node depths, their sum as the total, and a
  node-presence count of two

#### Scenario: A subscription on one node only

- **WHEN** a subscription exists on one logical node of a multi-node cluster
- **THEN** the row's node-presence count reflects one of the cluster's nodes,
  not an error or a zero for the others

#### Scenario: Two kinds of subscription share a name

- **WHEN** a cluster holds two subscriptions of different kinds with the same name
- **THEN** they are two rows, told apart by their canonical resource id and kind label

### Requirement: Destinations, consumers, channels, connections, and producers are listed live across nodes

The system SHALL expose, per cluster, a view for each of destinations, consumers,
channels, connections, and producers, built by querying every serving endpoint
once, tagging each row with the logical node it came from, and returning the
merged, paginated result. Every row SHALL carry its canonical resource id, its
kind and its kind label, and SHALL refer to the other roles it touches (a
consumer's channel, subscription and destination) by canonical resource id.

#### Scenario: Consumers from every node in one list

- **WHEN** the consumers view is requested for a multi-node cluster
- **THEN** the response contains consumers from every serving node, each tagged
  with its node, in one paginated list

#### Scenario: One batched request per node

- **WHEN** any of these views is loaded
- **THEN** Rahdar issues one batched request per serving node, not one request
  per row or per node per attribute

#### Scenario: A channel is shown with its native kind

- **WHEN** an operator views the channels of an Artemis cluster
- **THEN** each row is a channel whose kind label is that system's own word for it

### Requirement: Unmanageable nodes are shown as present without data

The system SHALL represent a node that is known but has no reachable management
URL as present in the node dimension of every cross-node view, with no counters
rather than zeroed counters, and SHALL NOT omit it.

#### Scenario: Known-but-not-manageable node in the queues view

- **WHEN** a cluster has a discovered node with no management URL and the queues
  view is requested
- **THEN** that node appears in the view's node dimension marked as having no
  data, not as contributing zeros

### Requirement: A stale node's last data is marked, not dropped

When a serving endpoint is temporarily unreachable, the system SHALL continue to
show that node's most recent cached rows marked as stale with the time they were
last seen, and SHALL NOT delete them because one scrape failed.

#### Scenario: Live endpoint unreachable for one cycle

- **WHEN** a node's serving endpoint fails a scrape but succeeded previously
- **THEN** its rows remain in the queues view marked stale with a last-seen time

### Requirement: Cross-node views require management read access

When a cluster's connection does not have management read capability, the
system SHALL return an explanation and the `broker.xml` change that would grant
it rather than an empty result.

#### Scenario: Management read unavailable

- **WHEN** a cross-node view is requested for a cluster whose connection lacks
  management read
- **THEN** the response carries the capability reason and the enabling
  `broker.xml` snippet, and the UI shows those rather than an empty table

### Requirement: Connection, channel and consumer rows offer the action their finding implies

The connections, channels and consumers views SHALL offer, on each row, the close
operation that applies to it, so that a finding and the action it implies are not
separated by a change of tool.

Where the system marks a consumer as slow, that marking SHALL lead to the same
action, so an operator does not have to re-find the row in another view.

A row is served from a cache and may be stale; acting on a row whose target has
since gone SHALL be presented as already closed rather than as a failure.

#### Scenario: The action is on the row

- **WHEN** an operator views the consumers of a cluster
- **THEN** each row offers closing its connection, subject to permission

#### Scenario: A slow-consumer finding leads to the action

- **WHEN** a consumer is marked slow
- **THEN** the operator can act on that finding without leaving the view it appears
  in

#### Scenario: Acting on a stale row is not an error

- **WHEN** an operator closes a connection from a row whose connection has since
  disconnected
- **THEN** the view reports it as already closed

### Requirement: A close is confirmed against something the operator can recognise

The confirmation for closing a connection SHALL be typed against an identifier a
human can recognise — the client identifier, or the remote address where no client
identifier is reported — and SHALL NOT be typed against the opaque connection
identifier, which an operator cannot verify they have the right one from.

The confirmation SHALL show, alongside it, the user, the node, and the number of
channels and consumers that will be closed with it, so the scale of the disconnect
is visible before it is armed.

Where the target's in-flight message count is known, the confirmation SHALL state
it together with the consequence that those messages return to their subscription
with an increased delivery count.

#### Scenario: The operator confirms against a recognisable name

- **WHEN** an operator is asked to confirm closing a connection
- **THEN** the value to type is the client identifier or remote address, not the
  opaque connection identifier

#### Scenario: The scale of the disconnect is shown

- **WHEN** a connection carrying several channels and consumers is about to be closed
- **THEN** the confirmation states how many of each will be closed with it

### Requirement: The row action reflects that the row may be stale

Because rows are served from a cache, the connections, channels and consumers views
SHALL present the age of what they are showing, and SHALL treat a close whose target
has since gone as a completed outcome rather than an error — reporting it in the
same place the successful outcome would appear, not as a failure notification.

After a close, the affected row SHALL be reconciled without requiring the operator
to reload the view.

#### Scenario: Staleness is visible

- **WHEN** an operator views connections
- **THEN** how current the data is, is apparent

#### Scenario: An already-gone target reads as done, not failed

- **WHEN** an operator closes a connection that has already disconnected
- **THEN** the outcome appears as completed, in the same place a successful close
  would appear

### Requirement: Routes are a cross-node resource view like any other

The system SHALL present routes — the edges that move messages, of whatever kind
the system has (an Artemis divert or bridge) — through the same cross-node view
mechanism as subscriptions, destinations, consumers, channels, connections and
producers: the same paging, filtering, node attribution and caching behaviour,
in one list whose rows carry their route class and kind label, rather than as a
separately built screen.

#### Scenario: Route views behave like the other resource views

- **WHEN** an operator pages, filters or sorts the routes view
- **THEN** it behaves as the other cross-node resource views do, and the state that
  describes what is being viewed is carried in the URL

#### Scenario: Diverts and bridges in one list

- **WHEN** an operator opens the routes of an Artemis cluster that has both diverts
  and bridges
- **THEN** both appear in the one list, told apart by route class and kind label

### Requirement: A listing's filter matches the identifiers other views link with

The filter of each live listing SHALL match:
- **connections**: remote address, client identifier and connection identifier;
- **channels**: channel identifier, connection identifier and user;
- **consumers**: subscription name and channel identifier;
- **producers**: destination name, producer name and channel identifier.

Each filter's label SHALL name what it matches.

#### Scenario: Following a channel to its connection

- **WHEN** an operator follows the connection link of a channel row
- **THEN** the connections listing shows that connection

### Requirement: A row's close outcome survives the row

A close started from a row SHALL keep its preview, confirmation and outcome on screen even
when the listing refreshes and the row is no longer in it. It SHALL remain until the
operator dismisses it.

#### Scenario: Closing a connection

- **WHEN** an operator confirms closing a connection and the next listing no longer
  contains it
- **THEN** the per-node outcome of the close stays visible
