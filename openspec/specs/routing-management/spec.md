# routing-management Specification

## Purpose
Covers how a cluster's message routing — its diverts and its bridges — is made visible
across nodes, and the disclosure that a divert created through the system persists on the
broker while remaining absent from whatever manages that broker's configuration.

## Requirements

### Requirement: Routes are visible across the cluster

The system SHALL present the routes of a cluster — the edges that move messages, of
every kind the cluster's system has (for an Artemis cluster, its diverts and bridges)
— merged across its nodes, in one list at the same quality as its other resource
views: paged, filterable, and attributed to the nodes each was found on. Each route
SHALL carry its route class (binding, forward or link), its kind label, its source
and its target as canonical resource ids.

A route that is a link to another system, such as an Artemis bridge, SHALL carry its
running state, so that an operator can answer whether a configured link is actually
running.

Viewing routes SHALL require only the permission needed to view a cluster.

#### Scenario: The routing between endpoints is visible

- **WHEN** an operator views a cluster's routes
- **THEN** each route is listed with its source, its target, its kind label,
  its filter and exclusivity where its kind has them, and the nodes it exists on

#### Scenario: A link's running state is answerable

- **WHEN** an operator views a cluster's routes that include bridges
- **THEN** each bridge is listed with whether it is currently running

### Requirement: The system does not claim where a divert came from

Artemis exposes no marker recording whether a divert was declared in broker configuration or
created over the management API, and no operation that returns configured but undeployed
diverts. The system SHALL NOT present a divert's origin as though it were known.

The system SHALL identify the diverts it owns itself, from its own records, and SHALL treat
every other divert as one whose origin it cannot establish.

The system SHALL NOT describe a divert as temporary, runtime-only, or as one that will be lost
when the broker restarts. Configuration created over the management API survives a restart,
so such wording would be false and would invite an operator to leave something in place
expecting it to clear itself.

#### Scenario: A divert Rahdar did not create carries no origin claim

- **WHEN** an operator views a divert the system does not own
- **THEN** it is listed with what it does, and no statement is made about whether it came
  from broker configuration

#### Scenario: No divert is described as temporary

- **WHEN** any divert is listed
- **THEN** nothing in the view says it will be lost when the broker restarts

### Requirement: A divert created through the system is disclosed as configuration drift

A divert created over the management API persists on the broker and is absent from the
configuration whatever manages that broker will next deploy. The system SHALL state this
consequence — that the running broker and its configuration will disagree, silently, until
one of them is changed — and SHALL show the configuration that would make them agree.

The configuration shown SHALL be generated from the values the operator supplied, so it can be
applied directly rather than re-entered, and SHALL be copyable in one action.

A divert MAY also be declared in the cluster's declared configuration, in which case the
declaration's drift report is where its presence on every node is tracked.

#### Scenario: The drift consequence is stated before the action

- **WHEN** an operator is about to create a divert
- **THEN** the form states that the divert will persist on the broker and will not appear in
  its configuration, and shows the configuration that would make the two agree

#### Scenario: The configuration is copyable, not retypable

- **WHEN** an operator wants the created divert reflected in configuration
- **THEN** the configuration built from their entered values can be copied in one action

### Requirement: Creating and deleting a divert is an authorized, audited act

The system SHALL allow an authorized operator to create and to delete a divert
through the Artemis namespace of a cluster (`/clusters/{c}/artemis/diverts`), which
exists only for clusters of that system, and SHALL NOT write to broker configuration
files. A divert is a route of kind `artemis.divert`; once created it is listed with
every other route.

Because nothing removes a divert on the system's behalf, deletion SHALL be presented as the
only thing that removes one.

#### Scenario: Deletion is presented as the only removal

- **WHEN** an operator views a divert the system created
- **THEN** deleting it is presented as what removes it, with no suggestion that a restart
  or any other event will

#### Scenario: The system does not edit broker configuration

- **WHEN** a divert is created
- **THEN** the running broker is changed and no broker configuration file is written

#### Scenario: Another system has no divert route

- **WHEN** a client creates a divert on a cluster that is not Artemis
- **THEN** the request answers not found, as the route does not exist for that cluster

### Requirement: Divert mutations apply across live nodes and report per node

A divert exists per node. The system SHALL apply a divert creation or deletion, made
through the Artemis namespace, to every live node of the cluster, determined from
observed topology, and SHALL report the outcome for each node, using the same
per-node outcome vocabulary as other cluster-wide topology mutations.

A node's creation outcome SHALL reflect what the broker actually deployed, checked after the
request. The outcome is:
- applied, when the divert is now present with the requested configuration;
- already, when an identical divert was present before;
- failed, naming the differing fields, when a divert of that name exists with a different
  configuration;
- failed, when the broker accepted the request but did not deploy the divert.

The system SHALL support previewing such a mutation, naming the target nodes and any refusal
or warning the preflight raises, without changing any broker.

The system SHALL NOT undo the nodes on which the mutation succeeded when it fails on
another; it SHALL report the divergence.

#### Scenario: A divert is created across the cluster

- **WHEN** an operator creates a divert on a cluster
- **THEN** it is created on every live node and the result reports each node

#### Scenario: A preview changes nothing

- **WHEN** an operator previews creating a divert
- **THEN** the target nodes are named and no broker is changed

#### Scenario: A duplicate name is not reported as applied

- **WHEN** an operator creates a divert whose name already exists on a node with a different configuration
- **THEN** that node's outcome is failed and names the fields that differ, rather than applied

#### Scenario: An identical existing divert is reported as already present

- **WHEN** an operator creates a divert identical to one already on a node
- **THEN** that node's outcome is already

#### Scenario: An undeployed divert is reported as failed

- **WHEN** the broker accepts a divert creation but does not deploy the divert
- **THEN** that node's outcome is failed, stating that the broker declined to deploy it and that its log holds the reason

### Requirement: Changing a divert is an explicit delete and create

The system SHALL NOT offer an in-place update of an existing divert. An operator who
needs to change one SHALL delete it and create the replacement, each step confirmed
and audited separately, so that no partially applied change is presented as atomic.

#### Scenario: No in-place edit is offered

- **WHEN** an operator opens an existing divert
- **THEN** its properties are shown and no edit action is offered, with delete and
  create presented as the way to change it

### Requirement: Drift is a persistent state in the view, not a one-time warning

A divert the system owns SHALL be marked wherever it is listed, with the configuration that
would make the broker and its configuration agree reachable from that marking.

The marking SHALL be carried in text, not by colour alone, and SHALL be readable by an
operator who did not create the divert and has no memory of anything shown at creation time.

Wording that describes such a divert as temporary SHALL NOT be used, because it implies
something will remove it.

#### Scenario: The marking survives the session that created it

- **WHEN** an operator who did not create a system-owned divert views the list
- **THEN** it is marked, in text, as one the system created and one that broker
  configuration does not carry

#### Scenario: The marking leads to the remedy

- **WHEN** an operator selects the marking
- **THEN** the configuration that would make the broker and its configuration agree is shown

### Requirement: A routes view answers where a message goes without being read end to end

The routes view SHALL make the source and the target of each route legible as a
direction, so an operator scanning for "where does traffic on this destination go"
is not reconstructing it from two columns.

Where a route diverts rather than copies — an exclusive Artemis divert — that SHALL
be distinguishable at a glance, because it is the difference between traffic being
duplicated and traffic being taken away.

The view SHALL be filterable by destination so that the question "what touches this
destination" is answerable directly.

#### Scenario: Direction is legible

- **WHEN** an operator scans the routes view
- **THEN** the direction from source to target is apparent without
  cross-referencing columns

#### Scenario: Exclusive diverts are distinguishable

- **WHEN** a view contains both exclusive and non-exclusive diverts
- **THEN** the difference is apparent, and conveyed in text rather than by colour
  alone

#### Scenario: What touches a destination is answerable

- **WHEN** an operator filters the routes view by a destination
- **THEN** the routes that read from or write to it are shown

### Requirement: Capture taps are identified as Rahdar's own and are not managed here

A route that exists to serve a message capture subscription (the tap Rahdar installs
on an Artemis cluster is built with a divert) SHALL be identified as such wherever
routes are listed, together with the capture subscription it serves.

The system SHALL NOT offer to delete such a route from the routes view. Removing it there
would leave a capture subscription whose tap is reinstated on the next reconciliation, so
the deletion would appear to succeed and then silently undo itself. The routes view SHALL
instead lead to the capture subscription that owns it.

The system SHALL refuse, on every interface (the REST API and the MCP tools included), to
create or delete a route whose name is in the namespace reserved for Rahdar's taps. The
refusal SHALL name the capture subscription to manage instead.

#### Scenario: A capture tap is attributed to its subscription

- **WHEN** an operator views a cluster's routes while a capture subscription is active
- **THEN** the tap's route is listed, identified as serving message capture, and names the subscription it belongs to

#### Scenario: Deleting a capture tap is not offered in the routes view

- **WHEN** an operator opens a capture tap's route in the routes view
- **THEN** no delete action is offered there, and the view leads to the capture subscription that owns it

#### Scenario: The API refuses to delete a capture tap

- **WHEN** a client calls the route delete API or tool with a capture tap's name
- **THEN** the request is refused with the reason, and no broker is changed

#### Scenario: The API refuses to create a route in the capture namespace

- **WHEN** a client creates a route whose name is in the capture namespace
- **THEN** the request is refused with the reason, and no broker is changed

### Requirement: A divert that would break routing is refused before it is created

Before creating a divert, in both the preview and the real request, the system SHALL check
each target node. It SHALL refuse the creation, naming the condition, when:
- the source and forwarding addresses are the same;
- the forwarding address does not exist on a node and would not be created automatically
  there, because producers to the source address would then fail;
- the new divert would complete a cycle of diverts, in which case the cycle is named.

When the new divert is exclusive and its source address carries a capture tap, the system
SHALL warn that capture of that address would observe nothing. It SHALL require the operator
to acknowledge this explicitly before creating it.

#### Scenario: A missing forwarding address is refused

- **WHEN** an operator creates a divert to a forwarding address that does not exist on a node and is not auto-created there
- **THEN** the creation is refused for that node, naming the missing address and the configuration that would create it, and no divert is created there

#### Scenario: A divert cycle is refused

- **WHEN** a divert from address A to address B exists and an operator creates a divert from B to A
- **THEN** the creation is refused, naming the cycle

#### Scenario: Source equal to forwarding is refused

- **WHEN** an operator creates a divert whose forwarding address equals its source address
- **THEN** the request is refused with the reason

#### Scenario: Shadowing a capture requires acknowledgement

- **WHEN** an operator previews an exclusive divert on an address that has a capture tap
- **THEN** the preview warns that capture of that address would observe nothing, and the creation is refused unless the operator acknowledges it

### Requirement: Divert requests are validated and generated configuration is well-formed

The system SHALL validate a divert request before any broker is contacted. It SHALL reject,
field by field:
- a missing name, source or forwarding address;
- a name longer than the permitted length, or containing characters that are not safe in
  a broker management name;
- a routing type that is not one of the broker's routing types.

The broker configuration the system shows for a divert SHALL be well-formed XML, whatever
characters the name, addresses or filter contain.

#### Scenario: An invalid request is rejected per field

- **WHEN** a client submits a divert with an unknown routing type and a name containing a comma
- **THEN** the request is rejected with an error for each of those fields, and no broker is contacted

#### Scenario: A filter with XML-special characters yields valid configuration

- **WHEN** a divert's filter contains `<`, `&` or a quote
- **THEN** the broker configuration shown for it parses as XML and carries the filter unchanged

### Requirement: A cluster's routing is presented as a graph that can be edited

The system SHALL present a cluster's routing as a graph — its destinations, its
subscriptions, the routes between destinations, the links out of subscriptions, and
the targets those links reach — so that "where does traffic on this destination go"
is answered by following a line rather than by cross-referencing rows on separate
screens. Each element carries its kind label, so an Artemis divert and bridge are
still named as such.

The graph SHALL be an editing surface for the cluster's declared configuration. Composing a
route in it SHALL change only the declaration, and SHALL change no broker until the
declaration is applied through the ordinary apply path.

Every element SHALL state which of three things it is, in words: declared and observed on the
brokers, declared but not yet applied, or observed on the brokers and not declared. These are
facts the system holds; none of them is a claim about where an element originally came from.

The graph SHALL NOT be the only way to reach anything it presents. Every element it shows and
every action it offers SHALL also be reachable from the non-graph view of the same
declaration.

#### Scenario: A route is followed rather than reconstructed

- **WHEN** an operator opens the routing graph for a cluster with a divert from one destination to
  another and a bridge out of a subscription on the second destination
- **THEN** the path from the first destination through the divert, the second destination, its subscription
  and the bridge to its target is drawn as connected elements

#### Scenario: Composing a route changes no broker

- **WHEN** an operator composes a divert in the graph and saves it
- **THEN** a new declaration revision is recorded and no broker has been contacted to create it

#### Scenario: An unapplied edit is distinguishable from a running one

- **WHEN** the declaration contains a divert that has not been applied to any node
- **THEN** that element states in words that it is declared and not yet applied, and removing
  colour from the view removes no information

#### Scenario: An undeclared element is shown without an origin claim

- **WHEN** a divert is observed on the brokers that the declaration does not contain
- **THEN** it is drawn and stated to be observed and not declared, and nothing states whether
  it came from broker configuration

#### Scenario: The graph is never the only route to an action

- **WHEN** an operator cannot use the graph
- **THEN** every element in it and every edit it offers remain available from the non-graph
  view of the same declaration

### Requirement: The routing graph is bounded and states its bound

A cluster's routing can be larger than a graph can usefully draw. The system SHALL bound what
it renders, and SHALL state the bound and what has been left out rather than silently drawing
a subset.

Where the graph is bounded, the system SHALL let the operator choose the destination the drawn
region is anchored on, so the part they came to see is the part they get.

#### Scenario: A large cluster states what is not drawn

- **WHEN** a cluster's routing exceeds what the graph renders at once
- **THEN** the view states that it is showing a bounded region, how much is not shown, and how
  to choose what is

### Requirement: A transformer is declared with its properties, and its availability is not claimed

A divert or a bridge MAY carry a transformer: a class name together with the properties that
configure it. The system SHALL allow both to be declared, and SHALL carry the properties
through export and import unchanged.

The system SHALL NOT claim that a transformer class is available on a broker. No management
operation reports what a broker has loaded, so the system SHALL state that it cannot verify
this before applying, and SHALL NOT disable or hide the field on that basis.

When a node declines to deploy a divert or a bridge that carries a transformer, that node's
outcome SHALL name the transformer class, so the operator is told what to put on the
classpath rather than that something unspecified failed.

#### Scenario: A transformer's properties survive a round trip

- **WHEN** a divert carrying a transformer class and two properties is exported and imported
  again
- **THEN** the class and both properties are present and unchanged

#### Scenario: Availability is stated as unverifiable, not as unavailable

- **WHEN** an operator enters a transformer class
- **THEN** the field remains usable and the view states that the system cannot verify the
  class is on each broker's classpath before the configuration is applied

#### Scenario: A failed deployment names the transformer

- **WHEN** a node accepts the request but does not deploy a divert whose transformer class it
  cannot load
- **THEN** that node's outcome is failed and names the transformer class

### Requirement: A bridge is declared, applied and removed like other configuration

The system SHALL allow an authorized operator to declare a bridge as part of a cluster's
declared configuration, and SHALL apply, change and remove it through the same plan, hazard,
canary-first apply and drift machinery as every other declared item. There SHALL be no
separate path that writes a bridge to a broker.

Because the broker offers no in-place update for a bridge, a changed bridge SHALL be planned
and presented as a removal followed by a creation, and SHALL be named as a hazard: nothing is
forwarded between the two steps and the bridge's source queue accumulates.

Removing a bridge SHALL be named as a hazard in its own right, because traffic to another
broker stops and nothing on this cluster reports that it has.

A bridge's running state SHALL continue to be reported as observed state, separate from
whether its configuration matches what is declared. A declared bridge whose configuration
matches but which is not connected SHALL be reported as a fault, not as drift.

#### Scenario: A declared bridge is applied like any other item

- **WHEN** an operator declares a bridge and applies the declaration
- **THEN** it is created on each node through the same canary-first apply, and each node's
  outcome is reported

#### Scenario: A changed bridge is planned as a removal and a creation

- **WHEN** an operator changes a declared bridge's forwarding address and reviews the plan
- **THEN** the plan shows a removal followed by a creation, and states that nothing is
  forwarded between them

#### Scenario: Removing a bridge is named as a hazard

- **WHEN** an operator plans the removal of a declared bridge
- **THEN** the plan states that traffic to that bridge's target stops and that no signal on
  this cluster will report it

#### Scenario: A disconnected bridge is a fault, not drift

- **WHEN** a node runs a bridge whose configuration matches the declaration but which is not
  connected to its target
- **THEN** it is reported as not connected, and is not reported as configuration drift

### Requirement: A bridge's credentials are held as a reference, never as declared configuration

A bridge may authenticate to the broker it forwards to. The system SHALL hold such credentials
in its own secret storage and SHALL carry only a reference to them in the declaration.

The credential SHALL NOT appear in the declaration document, in any stored revision of it, in
a difference between revisions, in an audit record's parameters, in any tool or API response,
or in exported configuration. Exported configuration SHALL carry a placeholder naming the
credential that must be supplied.

#### Scenario: The declaration carries a reference, not a secret

- **WHEN** an operator declares a bridge with a user and password
- **THEN** the stored declaration carries a reference to the credential and no password

#### Scenario: Export does not disclose the credential

- **WHEN** a declaration containing an authenticated bridge is exported
- **THEN** the exported configuration names the credential that must be supplied and contains
  no password

#### Scenario: A revision difference does not disclose the credential

- **WHEN** an operator compares two revisions that differ in a bridge's credential
- **THEN** the difference states that the credential changed and shows neither value
