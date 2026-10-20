# sql-console Specification

## Purpose
Defines the message query console: a restricted SQL dialect an operator writes to
search messages across many queues at once, how that query is planned, costed and
executed against either the live brokers or the historical message index, what a
result row is allowed to claim about itself, and the boundaries — read-only,
bounded, and honest about what it could not see.

## Requirements

### Requirement: Messages can be queried with a restricted SQL dialect

The system SHALL accept a query written in a restricted SQL dialect and return
matching messages. The dialect SHALL support `SELECT`, a single `FROM` naming one
or more subscriptions, `WHERE`, `ORDER BY` and `LIMIT`, over a fixed catalogue of columns
covering message identity, JMS headers, the message body, and application
properties addressed by name.

The dialect SHALL reject, at parse time and before any broker or database is
contacted, anything outside that subset — joins, subqueries, set operations,
common table expressions, any statement that is not a `SELECT`, any function
outside a documented whitelist, and any column not in the catalogue. A rejection
SHALL name the offending token, state why it is not allowed, and where a near
match exists in the catalogue, suggest it.

A `FROM` target SHALL be matched against the subscriptions known for the cluster, and
MAY use the broker's own wildcard syntax to name a set of subscriptions rather than one.
A query naming no subscription that exists SHALL say so, distinctly from a query that
matched subscriptions and found no messages.

#### Scenario: A header query returns matching messages

- **WHEN** an operator runs a query selecting messages on a subscription with a priority predicate
- **THEN** only messages matching that predicate are returned

#### Scenario: A wildcard names a set of subscriptions

- **WHEN** a query's FROM target uses the broker's wildcard syntax
- **THEN** every subscription on the cluster matching that pattern is searched and each returned row names the subscription it came from

#### Scenario: A statement outside the subset is rejected before execution

- **WHEN** an operator runs a query containing a join, a subquery, or a statement that is not a SELECT
- **THEN** the query is rejected with the offending construct named, and no broker or database is contacted

#### Scenario: An unknown column suggests a known one

- **WHEN** a query references a column that is not in the catalogue but is close to one that is
- **THEN** the rejection names the unknown column and suggests the catalogue column it resembles

#### Scenario: A FROM target matching nothing is distinguished from an empty result

- **WHEN** a query names a subscription pattern that matches no subscription on the cluster
- **THEN** the response states that no subscription matched, and does not present the outcome as zero matching messages

### Requirement: A query names its source and every result states which source answered

A query SHALL be executable against either the live brokers or the historical
message index, selected by an optional source qualifier on the `FROM` target. When
the qualifier is absent the system SHALL choose the index for a subscription that is
indexed and the live brokers otherwise, and SHALL state the choice it made.

Every result set SHALL carry the source that produced it, and every row SHALL carry
the source that produced that row. A row produced from the index SHALL additionally
carry when it was observed and SHALL be presented as possibly no longer present on
the broker.

The system SHALL offer, for any row produced from the index, an action that re-reads
that message from the live broker and reports whether it is still present.

#### Scenario: The source qualifier forces the live brokers

- **WHEN** a query qualifies its FROM target with the broker source
- **THEN** the query is executed against the live brokers even though the subscription is indexed, and the result states the broker source answered

#### Scenario: An indexed row is presented as possibly stale

- **WHEN** a result set is produced from the index
- **THEN** each row carries its observation time and the result set states that the messages may have been consumed since

#### Scenario: A consumed message is verified as gone

- **WHEN** an operator verifies an indexed row against the live broker and that message has since been consumed
- **THEN** the verification reports the message as no longer present, and the row is not removed from the result

### Requirement: Predicates the broker can evaluate are pushed down and the rest are scanned

The system SHALL split a query's `WHERE` clause into the part the broker can
evaluate natively — predicates over JMS headers and application properties — and
the residual part it cannot, which includes every predicate over the message body.
The pushed-down part SHALL be evaluated by the broker; the residual part SHALL be
evaluated by the system over the messages the broker returned.

A predicate SHALL only be pushed down when its meaning is identical on both sides.
A predicate whose SQL semantics differ from the broker's selector semantics —
case-insensitive matching among them — SHALL be treated as residual rather than
translated approximately.

A pushed-down predicate SHALL be constructed from the validated query structure and
never by interpolating query text, and the identifiers it names SHALL come from the
column catalogue.

#### Scenario: A header predicate costs no scan

- **WHEN** a query's WHERE clause references only headers and application properties
- **THEN** the broker evaluates the whole predicate and no message is scanned by the system

#### Scenario: A body predicate becomes residual

- **WHEN** a query's WHERE clause references the message body
- **THEN** that predicate is evaluated by the system over the returned messages and the plan reports it as a scan

#### Scenario: A semantically different predicate is not pushed down

- **WHEN** a query uses a case-insensitive comparison that the broker's selector syntax cannot express
- **THEN** the predicate is evaluated as a residual rather than translated, and the plan says so

### Requirement: A query is planned and costed before it executes

The system SHALL expose planning as an operation distinct from execution, which
parses and validates a query, resolves its targets, and returns without contacting a
broker: which source will answer, how many subscriptions and nodes will be read, which
predicates are pushed down, whether a body scan is required, and an estimate of how
many messages will be examined.

A query whose estimated cost exceeds the configured ceiling SHALL be refused before
its first broker call, stating the estimate, the ceiling, and how to narrow the
query. It SHALL NOT be started and silently truncated.

#### Scenario: The plan reports the pushdown split

- **WHEN** an operator plans a query mixing a property predicate and a body predicate
- **THEN** the plan names the pushed-down predicate, names the scanned predicate, and returns without contacting a broker

#### Scenario: An over-budget query is refused, not truncated

- **WHEN** a query's estimated scan exceeds the configured ceiling
- **THEN** the query is refused with the estimate, the ceiling and a narrowing hint, and no broker call is made

### Requirement: Execution is bounded, rate-limited and cancellable

Every broker read a query performs SHALL take a per-node management-call permit
like every other management call, so that a query throttles itself against the
broker rather than the broker against the query.

Execution SHALL be bounded by a configured maximum number of target subscriptions, a
maximum number of messages examined, a maximum number of rows returned, and a wall
clock timeout. A query that reaches any bound SHALL return the rows it has, stating
which bound it reached — never presenting a bounded result as a complete one.

Abandoning a query SHALL stop it: no further broker read is issued once the caller
is gone. A query SHALL NOT outlive the request or stream that asked for it.

The number of queries one operator may run concurrently SHALL be capped.

#### Scenario: A bounded result says it is bounded

- **WHEN** a query examines the configured maximum number of messages before exhausting its targets
- **THEN** the rows found so far are returned together with a statement that the scan cap was reached

#### Scenario: Abandoning a query stops the broker reads

- **WHEN** a client abandons an in-flight query
- **THEN** no further broker read is issued for it

#### Scenario: A query is throttled like any other management call

- **WHEN** a query reads many pages from one node
- **THEN** each read takes that node's management-call permit

### Requirement: A node that does not answer is reported, not omitted

A query fans out across the nodes serving its target subscriptions. When a node fails to
answer, the result SHALL report that node and the reason, and SHALL be presented as
partial. Rows from the nodes that did answer SHALL still be returned.

An absent node SHALL NOT be rendered as an absence of matching messages.

A primary and its synced backup are one logical node; a query SHALL return a
matching message once, not once per endpoint.

#### Scenario: One node fails and the rest answer

- **WHEN** one of three nodes serving a subscription pattern does not answer a query
- **THEN** the result contains the rows from the other two, is marked partial, and names the failed node and the reason

#### Scenario: A paired cluster does not double count

- **WHEN** a query targets a subscription on a node with a synced backup endpoint
- **THEN** each matching message appears once in the result

### Requirement: A body predicate over truncated content reports an incomplete result

The management channel truncates message bodies at the broker's configured attribute
size limit. When a query evaluates a body predicate over messages read through that
channel, a truncated body can produce a false negative.

The system SHALL detect that a body predicate was evaluated against truncated
content and SHALL mark the result set as possibly incomplete, naming the limit and
the channel that would return full bodies. It SHALL NOT present such a result as
authoritative.

When the same query is served over the channel that returns full bodies, no such
warning SHALL be shown.

#### Scenario: A truncated body warns on a body predicate

- **WHEN** a query with a body predicate is served over the management channel and a returned message's body was truncated
- **THEN** the result is marked possibly incomplete, naming the size limit and the channel that returns full bodies

#### Scenario: A full-fidelity channel does not warn

- **WHEN** the same query is served over the channel that returns untruncated bodies
- **THEN** no incompleteness warning is shown

### Requirement: A result row's identity is node-local

A message identifier is issued by, and meaningful only on, the node holding the
message. A result row SHALL identify a message by the combination of its node, its
subscription and its message identifier, and SHALL NOT present the identifier alone as a
cluster-wide identity. Any action taken on a row SHALL carry that full identity.

#### Scenario: A row identifies its node and subscription

- **WHEN** a query returns messages from several subscriptions across several nodes
- **THEN** each row names the node and subscription it came from alongside its message identifier

### Requirement: Time predicates are expressed in broker time

Message timestamps are assigned by the broker, whose clock may differ from the
system's. A relative time predicate SHALL be resolved against the measured offset
for the node being queried, not against the system clock alone, so that a skewed
broker does not silently return nothing.

Where a target node's clock offset is unknown, the result SHALL state that the time
window could not be normalised for that node.

#### Scenario: A skewed broker still matches a relative window

- **WHEN** a query asks for messages from the last hour against a node whose clock is measurably offset
- **THEN** the window is resolved in that node's time and matching messages are returned

#### Scenario: An unmeasured offset is disclosed

- **WHEN** a relative time predicate targets a node whose clock offset has not been measured
- **THEN** the result states that the window was not normalised for that node

### Requirement: A query can be tailed live, and a tail is a sample

The system SHALL stream new messages matching a query as they are observed, over a
stream scoped to that query, which ends when the client disconnects.

Where the tailed subscriptions are not captured, a tail SHALL be implemented without mutating
broker configuration and without consuming messages, and SHALL therefore be presented as a
**sample**: messages that arrive and are consumed between two observations are not seen. The
system SHALL state this for the duration of the tail, and the statement SHALL NOT be
dismissable.

Where every target of the tail is covered by an active capture subscription, the tail SHALL
be served from the capture and SHALL state that instead — that it shows every message routed
to those destinations while capture has been active — together with the coverage that claim
rests on. The system SHALL NOT present a captured tail and a sampled tail identically, and
SHALL NOT claim capture for a target that is only partly covered.

Where the system can estimate how many messages passed through unobserved — because a poll
missed them, or because a capture dropped them — it SHALL report that figure rather than
implying the tail was complete.

A tail SHALL respect the same per-node rate limit, bounds and cancellation as a static query.

#### Scenario: New matching messages stream to the client

- **WHEN** a tail is running and a matching message is enqueued
- **THEN** that message is delivered on the query's stream

#### Scenario: The sampling limitation is always stated

- **WHEN** a tail is running over subscriptions that are not captured
- **THEN** the interface states that messages consumed between observations are not shown, and the operator cannot dismiss that statement

#### Scenario: A captured tail states the stronger claim

- **WHEN** a tail is running over subscriptions every one of which is covered by an active capture subscription
- **THEN** the interface states that it is showing every message routed to those destinations while capture has been active, rather than the sampling statement

#### Scenario: Partial capture does not claim capture

- **WHEN** a tail's targets include one covered by capture and one that is not
- **THEN** the interface states the sampling limitation and names the target it cannot claim capture for

#### Scenario: A message consumed immediately is tailed when captured

- **WHEN** a message is routed to a captured destination and consumed before any poll could observe it
- **THEN** it is delivered on the tail's stream

#### Scenario: Disconnecting ends the tail

- **WHEN** a client disconnects from a tail
- **THEN** the tail stops and no further broker read is issued for it

### Requirement: The console is read-only and every query is audited

The dialect SHALL express no mutation. Acting on a result row SHALL route through
the existing message operations, with their dry run, their bulk cap and their audit
record — the console SHALL NOT provide a second path to a destructive verb.

Running a query SHALL require the message read permission on the target cluster, and
SHALL only search subscriptions within the caller's cluster scope. A query naming a subscription
outside that scope SHALL say so rather than returning it as an empty match.

Every executed query SHALL write an audit record carrying the query text, the number
of targets it resolved to, the source that answered, and the number of rows returned.
Literal values in the audited query text that the content policy classifies as
sensitive, or that are compared with a classified field, SHALL be masked before the
record is written. When a query served sensitive values in clear, the record SHALL
also carry the classes and counts served in clear.

#### Scenario: A mutating statement is not expressible

- **WHEN** an operator submits a statement that would delete or move messages
- **THEN** it is rejected at parse time as outside the dialect

#### Scenario: An out-of-scope subscription is named, not hidden

- **WHEN** a query's FROM pattern matches a subscription the caller has no access to
- **THEN** the response states that a matching subscription was excluded by permission, rather than silently omitting it

#### Scenario: A query is audited

- **WHEN** an operator executes a query
- **THEN** an audit record is written with the query text, the resolved target count, the answering source and the row count

#### Scenario: A sensitive literal is masked in the audit record

- **WHEN** an operator runs a query comparing a classified property with a literal email address
- **THEN** the audit record's query text shows the literal masked

### Requirement: The console teaches its own syntax and its cost model

The console SHALL provide, in the page rather than through an external link, the
column catalogue, the source qualifiers, and worked examples an operator can place
into the editor in one action.

The editor SHALL offer completion over the subscriptions that actually exist on the cluster
and over the column catalogue, and SHALL report a syntax error against the query
being typed without requiring the operator to run it.

The interface SHALL state, before a query runs, whether its predicates are pushed
down to the broker or require a scan, so that the cost of a query is visible at the
moment it can still be changed.

#### Scenario: The syntax reference is reachable from the console

- **WHEN** an operator opens the console's help
- **THEN** the column catalogue, the source qualifiers and runnable examples are shown without leaving the page

#### Scenario: An example is placed into the editor

- **WHEN** an operator chooses a worked example from the help
- **THEN** that query is placed into the editor ready to run

#### Scenario: A syntax error is reported before running

- **WHEN** an operator types a query with an unknown column
- **THEN** the error is reported against the query without the operator running it

#### Scenario: The cost is stated before the query runs

- **WHEN** an operator has typed a query requiring a body scan
- **THEN** the interface states that a scan is required and how many messages it estimates, before the query is run

### Requirement: A query is shareable

The query text, the chosen source and whether the tail is running SHALL be
navigable state, so that a console can be linked to a colleague and restored in the
state it was left. Editor interaction state SHALL remain local.

#### Scenario: A console link restores the query

- **WHEN** an operator copies the console's address while a query is loaded and opens it elsewhere
- **THEN** the same query text and source are restored in the editor

### Requirement: Message bodies can be searched by word and phrase, with relevance ordering

The dialect SHALL provide a full-text predicate over the message body supporting whole
words, quoted phrases, and the exclusion of a term, and SHALL allow results to be ordered by
how well they match it.

The predicate SHALL be evaluated by the stored index and SHALL NOT be offered against a live
broker, which has no such facility. A query using it against a live broker SHALL be refused
with that reason, in the same way as any other index-only column.

Relevance ordering SHALL be available only when the query contains the full-text predicate,
and SHALL be refused with its reason otherwise.

#### Scenario: A phrase search matches only the phrase

- **WHEN** an operator searches for a quoted phrase
- **THEN** only messages whose body contains that sequence of words are returned

#### Scenario: An excluded term removes matches

- **WHEN** an operator searches for a term while excluding another
- **THEN** messages containing the excluded term are not returned

#### Scenario: Full-text against a live broker is refused with its reason

- **WHEN** a query uses the full-text predicate against the broker source
- **THEN** it is refused, naming the predicate and stating that it is answerable only from the stored index

#### Scenario: Relevance ordering without a full-text predicate is refused

- **WHEN** a query orders by relevance without a full-text predicate
- **THEN** it is refused with that reason

### Requirement: Query text is not carried in a URL

The system SHALL NOT transmit query text as part of a request URL. A query is submitted in a
request body and the resulting stream is opened by reference to it.

Query text can contain the values an operator is searching for, which are application data;
a URL is recorded by intermediaries that a request body is not. A query SHALL also not be
limited in length by what a URL can carry.

#### Scenario: Query text is not in the request URL

- **WHEN** an operator runs any query, static or tailed
- **THEN** no request URL contains the query text

#### Scenario: A long query runs

- **WHEN** an operator runs a query longer than a URL can carry
- **THEN** it executes normally

### Requirement: A running query can be stopped from the interface

While a query is executing, the interface SHALL offer a control that stops it, for a static
query as well as for a tail. An operator SHALL NOT have to navigate away to stop a query
that is fanning out across a cluster.

#### Scenario: A static query can be stopped

- **WHEN** a static query is executing
- **THEN** a control that stops it is available, and using it stops the broker reads

### Requirement: A result states its provenance per row and its coverage per node

Where a result can contain rows from more than one source, each row SHALL state which source
it came from, and a captured row SHALL be distinguishable from a sampled one — the first
records everything routed to a destination while capture was active, the second records only
what happened to be observed.

Coverage SHALL be reported per node. A result whose targets are captured on some nodes and
not others SHALL name the nodes it cannot answer for, rather than presenting the rows it has
as the answer.

#### Scenario: Captured and sampled rows are distinguishable

- **WHEN** a result contains both captured and sampled rows
- **THEN** each row states which it is

#### Scenario: Partial node coverage is named

- **WHEN** a query's targets are captured on one node and not on another
- **THEN** the result names the node it cannot answer for

### Requirement: The console retains what was run and can export what was returned

The system SHALL retain the queries an operator has run in this browser, with when each ran,
how many rows it returned, and which source answered, and SHALL allow one to be reloaded
into the editor.

The system SHALL allow the rows of a result to be exported in a machine-readable form.

An operator mid-incident re-runs variations of the same query, and retyping one from memory
is how the wrong query gets run and believed.

#### Scenario: A previous query can be reloaded

- **WHEN** an operator selects a previously run query from the history
- **THEN** its text is loaded into the editor without being executed

#### Scenario: A result can be exported

- **WHEN** an operator exports a result
- **THEN** the rows are produced in a machine-readable form

### Requirement: A tail can be paused without being stopped

While a tail is running, the interface SHALL allow it to be paused. While paused the tail
SHALL continue to receive rows and SHALL state how many have arrived, and resuming SHALL
present them without having lost any.

The interface SHALL NOT move the operator's position in the results while they are reading
rows above the newest.

#### Scenario: Pausing holds the view and counts what arrives

- **WHEN** an operator pauses a running tail and matching messages continue to arrive
- **THEN** the displayed rows do not change and the number of rows waiting is stated

#### Scenario: Resuming loses nothing

- **WHEN** an operator resumes a paused tail
- **THEN** the rows that arrived while it was paused are shown

### Requirement: Result rows and exports are governed

Every result row, whether streamed, tailed, or returned in the final result, SHALL carry content governed by the content policy for the caller, with each masked, dropped, withheld or clear-by-grant value identified. An export of results SHALL contain exactly the governed content the caller was shown, and the interface SHALL state that exported content is masked where it was masked on screen.

#### Scenario: A tailed row is governed

- **WHEN** a user without clear access tails a query over messages carrying card numbers
- **THEN** each streamed row shows the card numbers partially masked

#### Scenario: An export carries masked content

- **WHEN** a user without clear access exports results that contained masked values
- **THEN** the exported file contains the masked values, not originals

### Requirement: A predicate cannot reveal a masked value

For a caller without clear access on the target cluster, the system SHALL refuse a query whose predicate references a header or property the content policy classifies for any target destination, and the refusal SHALL name the field. Body predicates for such a caller SHALL be evaluated over the governed body.

#### Scenario: A predicate on a classified property is refused

- **WHEN** a user without clear access runs a query with a predicate on property `customerEmail`, which a rule classifies
- **THEN** the query is refused before execution with a reason naming `customerEmail`

#### Scenario: A body search does not match a masked value

- **WHEN** a user without clear access searches bodies for a card number that appears in a message
- **THEN** the message does not match, because the predicate is evaluated over the masked body

### Requirement: The dialect's columns and plan codes are named by role
The dialect's catalogue SHALL name the columns that identify where a message is by role: `subscription`, `destination` and `orig_destination`, never one system's words for them. The planner's findings SHALL use role codes in the same way (`NO_SUBSCRIPTION_MATCHED`, `DESTINATION_SCOPED_CAPTURE`). A query using an old system-specific column name SHALL be rejected at parse time, naming the column to use.

#### Scenario: A query selects by subscription
- **WHEN** an operator runs a query that filters on the `subscription` column
- **THEN** it is accepted on a cluster of any system, and each returned row names its subscription

#### Scenario: An old column name is refused
- **WHEN** an operator runs a query that uses the column `queue`
- **THEN** it is rejected before any broker is contacted, and the message names `subscription`
