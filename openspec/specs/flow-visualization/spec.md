# flow-visualization Specification

## Purpose
Defines the Flow view: which clients produce to which addresses, how those addresses route into
queues through diverts, bridges and cluster redistribution, and who consumes them, at what rate —
bounded, stated in words where anything is wrong, and sampled from the brokers only while someone
is watching.

## Requirements

### Requirement: The flow view draws clients, addresses, queues and the routes between them

The system SHALL present, for a cluster, a graph whose nodes are producing clients,
addresses, queues, consuming clients and remote destinations, arranged in that order, and
whose edges are produce, route, divert, bridge, cluster-hop, wildcard-match, dead-letter,
expiry and consume relationships. Each kind of node SHALL be distinguishable by shape and
each kind of edge by line style, and a legend rendered inside the view SHALL name every
mark and line style the view can draw.

#### Scenario: A simple produce-route-consume path

- **WHEN** a client sends to an address bound to one queue that another client consumes
- **THEN** the view shows the producing client, the address, the queue and the consuming
  client, joined by produce, route and consume edges in that order

#### Scenario: The legend covers what is drawn

- **WHEN** the flow view renders
- **THEN** its legend names each node shape and each edge style the view can draw

### Requirement: Route edges state the binding semantics

A route edge from a multicast address SHALL state that each bound queue receives a copy; a
route edge from an anycast address SHALL state that its queues share messages. A route to a
queue with a filter SHALL be marked as filtered, and the filter SHALL be readable from the
view.

#### Scenario: Multicast copies

- **WHEN** a multicast address has two bound queues
- **THEN** both route edges state that each queue receives a copy

#### Scenario: A filtered binding

- **WHEN** a queue bound to an address has a filter
- **THEN** its route edge is marked as filtered and the filter expression is available in
  the inspector

### Requirement: Diverts and bridges are drawn with their effect and never with an invented rate

A divert SHALL be drawn from its source address to its forwarding address, stating whether
it reroutes (exclusive) or copies (non-exclusive). An exclusive divert SHALL mark the
source address's own queues as bypassed for matching messages. A divert filter or
transformer SHALL be marked. Because the broker keeps no count for a divert, a divert edge
SHALL state that it is not counted and SHALL NOT show a rate or motion.

A bridge SHALL be drawn from its queue to its forwarding address, or to a remote
destination when the target is outside the cluster, with a rate derived from the bridge's
acknowledged count. A bridge that is not connected SHALL be shown as a fault.

A divert or bridge present on only some of the cluster's serving nodes SHALL be shown as a
fault stating on how many of how many nodes it exists.

#### Scenario: An exclusive divert

- **WHEN** an address has an exclusive divert to another address
- **THEN** the divert edge states "reroutes", the source address's queues are marked
  bypassed, and the edge shows no rate

#### Scenario: A bridge to another broker

- **WHEN** a bridge forwards from a queue to an address on a broker outside the cluster
- **THEN** the view draws an edge from that queue to a remote destination with the bridge's
  rate

#### Scenario: A divert missing on one node

- **WHEN** a divert exists on one of two serving nodes
- **THEN** the view shows the fault "on 1 of 2 nodes" on that divert

### Requirement: Cluster redistribution, wildcard matches and anonymous producers are drawn honestly

Messages the cluster moves between nodes SHALL be drawn as cluster-hop edges from the
internal store-and-forward queue to the receiving node, with that queue's rate, instead of
showing the internal queue as an ordinary queue.

An address whose name matches a wildcard address with bound queues SHALL be joined to it
by a wildcard-match edge. The view SHALL state that matching assumes the broker's default
wildcard syntax.

A producer that names no address SHALL be drawn to a node stating that its address is
chosen per message, with the producer's rate, rather than being omitted.

#### Scenario: Redistribution to another node

- **WHEN** a store-and-forward queue toward another node has messages added
- **THEN** the view draws a cluster-hop edge to that node with the queue's rate

#### Scenario: Wildcard subscription

- **WHEN** a queue is bound to `orders.#` and a client produces to `orders.eu`
- **THEN** `orders.eu` is joined to `orders.#` by a wildcard-match edge and the view
  states the default-syntax assumption

#### Scenario: Anonymous producer

- **WHEN** a producer has no address
- **THEN** it is drawn to a node stating that its address is chosen per message

### Requirement: Optional layers are off or on by a stated default and internal objects are hidden unless asked for

The view SHALL offer layers for diverts, bridges, cluster redistribution, dead-letter and
expiry routes, temporary queues, and Rahdar capture taps. Diverts, bridges and cluster
redistribution SHALL be on by default; the others SHALL be off. Broker-internal addresses
and queues SHALL NOT be drawn as ordinary resources. Capture taps SHALL be drawn only
when their layer is on and SHALL be marked as Rahdar's. Temporary queues SHALL be collapsed
into one node per client stating how many there are. The active layers SHALL be part of the
view's address.

#### Scenario: Capture taps hidden by default

- **WHEN** a cluster has an active capture subscription and the capture-tap layer is off
- **THEN** the capture divert and queue are not drawn and are not counted among the
  shown paths

#### Scenario: Temporary queues collapsed

- **WHEN** a client owns twelve temporary queues and the temporary-queue layer is on
- **THEN** one node states "temporary queues ×12" for that client

### Requirement: The view is bounded and states its bound

The system SHALL draw at most a bounded number of paths, defaulting to the 40 busiest by
the selected ranking (messages in, messages out, or backlog), with an upper limit the
server enforces. The view SHALL state how many paths it shows out of how many exist and
SHALL offer ways to reach the rest: raising the limit, focusing, and the table. Ranking
SHALL be stable between refreshes whose rates differ only slightly, so that paths do not
enter and leave the view on every refresh.

#### Scenario: A large cluster

- **WHEN** a cluster has 1,212 paths and the view uses its default bound
- **THEN** it draws the 40 busiest and states "showing 40 of 1,212 paths"

#### Scenario: The server enforces the limit

- **WHEN** a request asks for more paths than the server's upper limit
- **THEN** the response contains no more than that limit and states that it was clamped

#### Scenario: Small rate changes do not reshuffle

- **WHEN** two consecutive refreshes differ only by small rate changes
- **THEN** the same paths are shown in the same order

### Requirement: An operator can focus the view on one resource

Selecting a client, address or queue as the focus SHALL redraw the view as that resource's
upstream and downstream neighbourhood, still bounded, with a control to widen it by one
further hop and a control to clear the focus. The focus SHALL be part of the view's address
so that reloading or sharing it restores the same view. A focus that matches nothing SHALL
be stated as such with the action that clears it.

#### Scenario: Focus survives reload

- **WHEN** an operator focuses the view on queue `orders.billing` and reloads the page
- **THEN** the view is again focused on `orders.billing`

#### Scenario: A focus that matches nothing

- **WHEN** the focused queue no longer exists
- **THEN** the view states that nothing matches the focus and offers to clear it

### Requirement: Clients are grouped by a selectable identity

Clients SHALL be grouped by client identifier by default, falling back to authenticated
user and then remote host (without port) where the preceding value is absent. The operator
SHALL be able to group by client identifier, user, or host instead, and the grouping SHALL
be part of the view's address. A grouped node SHALL state how many connections or consumers
it stands for.

#### Scenario: Consumers grouped per application

- **WHEN** three consumers with the same client identifier consume one queue
- **THEN** the view draws one consuming node stating ×3 for that queue

#### Scenario: Grouping by host

- **WHEN** the operator groups by host and two clients connect from the same host on
  different ports
- **THEN** they are drawn as one node for that host

### Requirement: Every rate states its source and age and an unknown rate is never zero

Each edge rate SHALL state its source — sampled client activity, stored queue metrics, a
broker counter, or none — and the time it describes. A rate older than three sampling
intervals SHALL be marked stale with its age. A rate derived from the slow queue sweep SHALL
state that it is an average over that interval. A rate not yet measurable SHALL read
"measuring…"; a rate the broker does not count SHALL read "not counted by broker"; neither
SHALL be displayed or sorted as zero.

#### Scenario: First sample

- **WHEN** a client has been sampled once
- **THEN** its produce edge reads "measuring…" and not 0 msg/s

#### Scenario: Stale rate

- **WHEN** a consume edge's latest sample is older than three sampling intervals
- **THEN** the edge is marked stale and states the sample's age

### Requirement: Client activity is sampled only while a cluster's flow is observed

The system SHALL sample producers and consumers of a cluster only while at least one
operator is observing that cluster's flow, on any Rahdar instance, and SHALL stop sampling
within one observation lease after the last observer leaves. Each sampling sweep SHALL issue
one batched management request per serving node, SHALL be counted against that node's
management-call ceiling, and SHALL NOT start while the previous sweep for that cluster is
still running. Client identity SHALL be taken from the producer and consumer rows
themselves, so sampling SHALL NOT list sessions or connections. The number of rows read per
node SHALL be capped by an operational setting.

#### Scenario: Nobody is looking

- **WHEN** no Rahdar instance has an operator observing a cluster's flow for longer than
  the observation lease
- **THEN** no producer or consumer listing is sent to that cluster's nodes

#### Scenario: One request per node

- **WHEN** a sampling sweep reads producers, consumers, sessions and connections from a node
- **THEN** it does so in one batched request to that node

#### Scenario: Sampling truncated

- **WHEN** a node has more consumers than the per-node row cap
- **THEN** the view states, for that node, how many consumers were sampled out of how many
  exist

### Requirement: Every Rahdar instance serves the same flow state

When several Rahdar instances share one database, exactly one of them SHALL sample a
cluster at a time, and every instance SHALL serve the flow view from the same latest
sample. A change of the sampling instance SHALL cause rates to read "measuring…" for at most
one sweep rather than showing wrong values.

#### Scenario: Two instances

- **WHEN** two Rahdar instances share a database and operators on both open the same
  cluster's flow
- **THEN** only one instance sends sampling requests, and both views show the same rates

### Requirement: Unavailable nodes and permissions are stated where their data would be

A serving node that did not answer the latest sweep SHALL be named in the view with the
statement that its clients are not shown. A node whose management permissions refuse the
listings SHALL be named with the reason and the `broker.xml` change that grants them. A
broker that does not report a counter a rate needs SHALL cause that rate to read
"rate unavailable on this broker", not zero.

#### Scenario: Unreachable node

- **WHEN** one of three serving nodes does not answer a sweep
- **THEN** the view names that node and states that its clients are not shown

#### Scenario: Permission refused

- **WHEN** a node refuses the consumer listing for lack of management permission
- **THEN** the view names the node, states the reason, and shows the `broker.xml` snippet
  that grants it

### Requirement: Faults are stated in words on the path they affect

The view SHALL mark, with a shape and a word and not by colour alone: an address or queue
with backlog and no consumer; a consumer group whose unacknowledged count is non-zero and
whose acknowledgement rate has been zero for two consecutive sweeps (stalled); a bridge that
is not connected; a divert or bridge partially present; and an unreachable node. The view
SHALL state the number of faults on the paths it shows and SHALL offer to show only the
faulted paths. A view without faults SHALL use no fault colour.

#### Scenario: Backlog without a consumer

- **WHEN** a queue has messages and no consumer
- **THEN** the queue is marked "no consumer" and counted among the faults

#### Scenario: Stalled consumer

- **WHEN** a consumer group has unacknowledged messages and acknowledged nothing for two
  sweeps
- **THEN** its consume edge is marked "stalled"

### Requirement: The view leads with current totals and states its freshness

The view SHALL lead with the cluster's current total inbound rate, outbound rate, backlog,
connected client count and fault count, and SHALL state when its data was last sampled.
Totals SHALL be computed over all paths, not only the shown ones, and SHALL say so.

#### Scenario: Totals exceed what is drawn

- **WHEN** the view shows 40 of 1,212 paths
- **THEN** its totals describe all 1,212 paths and state that they do

### Requirement: The table presents the same paths as the graph

The system SHALL offer a table of the same edges the graph draws for the same address,
with kind, source, target, rate, rate source, age, nodes and faults, sortable, with the
same bound and the same statement of it. Switching between graph and table SHALL keep the
focus, ranking, grouping and layers.

#### Scenario: Parity

- **WHEN** an operator switches from the graph to the table
- **THEN** the table lists exactly the edges the graph drew, with the same "showing N of M"
  statement

### Requirement: Motion encodes rate and is never the only carrier

Edges carrying a known, non-zero rate SHALL show movement in the direction of flow whose
speed and density increase with the rate, bounded so that no edge flickers, and the total
amount of moving marks SHALL be bounded. Every moving edge SHALL also state its rate in
text and by width. Movement SHALL stop when the operator pauses it, when the operator's
system requests reduced motion, and when the view is not visible; widths and rate text SHALL
remain.

#### Scenario: Reduced motion

- **WHEN** the operator's system requests reduced motion
- **THEN** no edge moves and every edge still shows its width and rate text

#### Scenario: Pause

- **WHEN** the operator activates Pause
- **THEN** all movement stops until they resume, and data keeps refreshing

### Requirement: The graph is operable from the keyboard and stable to read

Every drawn node SHALL be reachable by keyboard with a visible focus indicator and an
accessible name stating its kind, name, rates and faults. Activating a node SHALL open an
inspector for it; dismissing the inspector SHALL return focus to that node. Selecting a node
SHALL emphasise its complete upstream and downstream path. A refresh that changes only rates
SHALL NOT move any node; a change in the set of drawn nodes SHALL re-fit the view without
magnifying a small graph beyond its natural size. A screen-reader summary SHALL state the
number of paths, faults and the busiest path.

#### Scenario: Inspector round trip

- **WHEN** an operator tabs to a queue node, presses Enter, then Escape
- **THEN** the inspector opens for that queue and closes, and focus returns to the queue
  node

#### Scenario: Rate-only refresh

- **WHEN** a refresh changes rates but not the set of nodes
- **THEN** no node changes position

### Requirement: The inspector explains a resource and links to existing actions without mutating

The inspector SHALL show a selected resource's rates with their sources and ages, the nodes
it is present on, its members (connections, sessions, producers or consumers with node,
remote host, protocol and user), and its routing (bindings, filters, diverts and bridges).
Where an existing action applies to a member, such as closing a connection, the inspector
SHALL link to the screen that performs it with its confirmation. The flow view itself SHALL
issue no mutating request.

#### Scenario: Closing a connection from flow

- **WHEN** an operator chooses to close a connection listed in the inspector
- **THEN** they are taken to the existing connection close flow for that connection, and no
  close is sent by the flow view

### Requirement: The view teaches when it has nothing to show

A cluster with no connected clients SHALL show an explanation of what the flow view draws
and that clients appear as they attach. A view emptied by its focus or layers SHALL say so
and offer to clear them. While the first data loads, the graph area SHALL be occupied by a
placeholder of its own size.

#### Scenario: No clients

- **WHEN** a cluster has no producers or consumers
- **THEN** the view explains that flow shows clients as they attach instead of rendering
  an empty frame

#### Scenario: Filtered empty

- **WHEN** the active layers and focus leave nothing to draw
- **THEN** the view states that its filters hide everything and offers to clear them

### Requirement: Viewing flow requires cluster read permission and sampling is tunable at runtime

Viewing a cluster's flow SHALL require the same permission as viewing its connections and
consumers. The sampling interval, the per-node row cap and the observation lease SHALL be
operational settings whose changes
apply without a restart, with a lower bound on the interval.

#### Scenario: Without read permission

- **WHEN** a caller without cluster read permission requests a cluster's flow
- **THEN** the request is refused

#### Scenario: Interval change applies live

- **WHEN** an administrator changes the sampling interval
- **THEN** the next sweep uses the new interval without a restart

### Requirement: The view splits into the graph and a monitoring pane that follows the selection

The flow view SHALL offer a Split layout beside Graph and Table. The Split layout SHALL
place the graph beside a monitoring pane, divided by a separator that the operator can
resize and collapse with pointer and keyboard. The pane size SHALL be remembered in this
browser.

The selected client, address or queue SHALL be part of the view's address, in every
layout. Reloading or sharing the address SHALL restore the selection.

With nothing selected, the pane SHALL show each broker node's totals. With a selection, it
SHALL show that resource.

#### Scenario: Selection survives reload

- **WHEN** an operator selects queue `orders` in the Split layout and reloads the page
- **THEN** the Split layout opens with `orders` selected and its pane shown

#### Scenario: Resizing from the keyboard

- **WHEN** an operator focuses the separator and presses the arrow keys
- **THEN** the panes resize

### Requirement: A resource's traffic and backlog are broken down per broker node, and imbalance is stated in words

For a selected queue or address, the pane SHALL list every serving node with that node's
backlog, consumers, messages in and messages out. A node that did not answer SHALL be
listed as not answering, and its figures SHALL be stated as unknown, never zero.

The pane SHALL state in words, and not by colour alone:
- when one node holds most of the backlog;
- when a node holds messages and has no consumer while other nodes have consumers;
- when a node's share of messages in far exceeds its share of messages out.

When nothing is imbalanced, it SHALL say the resource is balanced across its nodes.

For a queue, the pane SHALL also show its per-node trends over a selectable range, one
chart per node on a shared scale, where the metrics feature is enabled. For a client, it
SHALL state that client history is not kept.

The breakdown SHALL be requested only by the Split layout.

#### Scenario: Stranded backlog

- **WHEN** queue `orders` holds 9,000 messages on `artemis-b`, which has no consumer, and
  its consumers are on `artemis-a`
- **THEN** the pane states that `artemis-b` holds 9,000 messages and has no consumer, and
  that the consumers are on `artemis-a`

#### Scenario: An unanswered node is not zero

- **WHEN** one of two nodes did not answer the latest sweep
- **THEN** its row states that it did not answer and its figures read unknown

### Requirement: Menus in the flow view navigate and never act

A node of the graph and a row of the table SHALL offer a menu, by right-click, by
Shift+F10 or the ContextMenu key, and by an Actions control where one is shown. The menu
SHALL offer:
- the resource's Open and Copy actions;
- one link to the screen where it can be acted on.

It SHALL NOT offer an action that changes the broker.

#### Scenario: Acting from flow

- **WHEN** an operator opens the menu of a queue node
- **THEN** it offers opening the queue and a link to act on it in Queues, and no delete

### Requirement: The inspector opens the exact resource

The inspector's links SHALL open the exact queue, address or connection they name, not a
list filtered by the client's label. The table's rows SHALL be activatable from the
keyboard, as the graph's nodes are.

#### Scenario: Opening a queue from the inspector

- **WHEN** an operator activates "Open in Queues" for queue `orders`
- **THEN** the Queues view opens with `orders` open

### Requirement: Flow maps every system in its own terms

Flow SHALL be offered on Artemis, Kafka and RabbitMQ clusters:

- On a Kafka cluster it SHALL show each consumer group on the topics it consumes, and SHALL state that producers
  are not observable there.
- On a RabbitMQ cluster it SHALL show consumers on their queues, and exchange bindings, shovels and federation
  links as routes.

No Kafka or RabbitMQ view SHALL name Artemis, Jolokia or `broker.xml`.

#### Scenario: A consumer group on a Kafka topic

- **WHEN** an operator opens Flow on a Kafka cluster where a group consumes a topic
- **THEN** the group is shown consuming that topic, and the view says producers are not observable on Kafka
