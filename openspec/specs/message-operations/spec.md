# message-operations Specification

## Purpose

Defines what an operator can do to the messages on a queue from Rahdar
over the Jolokia management channel — browse them with full headers and
properties, send one, and move / retry / delete / expire / purge them by ids or
by filter — and the safety contract around every mutation: a dry run that
reports the blast radius without acting, a server-enforced bulk cap, and an
audit record written in the command's own transaction.

## Requirements

### Requirement: A queue's messages can be browsed with full headers and properties

The system SHALL expose a paged read of the messages on one queue of one node,
accepting an optional Artemis filter expression. Each returned message SHALL
carry its message id, type, durability, priority, timestamp, expiration, size,
the count of application properties, its correlation id, and its reply-to
destination when the message has one. A single-message read SHALL additionally
return the full header set and the string, integer, long, and boolean property
maps, and the message body.

Every value returned by a browse or a single-message read SHALL be governed by the
content policy for the caller: sensitive values masked or dropped, uninspectable
content withheld, and each masked, dropped, withheld or clear-by-grant value
identified with its location and class. A body preview SHALL be derived from the
governed body, never from the broker's body.

The browse SHALL be served over the Core client when the cluster has an
available Core connection, and over Jolokia otherwise. Over the Core client the
body SHALL be returned faithfully — text as text, binary as bytes with an
encoding indicator — and application properties SHALL keep their real types.
Over Jolokia the browse SHALL cost exactly one batched Jolokia POST per call
(the `browse` exec and the `MessageCount` read in one array). Every browse and
single-message response SHALL state which channel served it.

Over the Core client, because a queue browser has no server-side offset, a
requested page beyond a bounded browse depth SHALL be served over Jolokia
instead, and the response SHALL state that it was. A Core browse SHALL read no
more messages from the broker than the requested page requires. It SHALL NOT read
the rest of the queue to count it.

The response's total SHALL come from the broker's own count: the queue's message count
when there is no filter, or the broker's count of messages matching the filter. Where
that count is not available, the response SHALL state that the total is unavailable
and why. It SHALL NOT report a guessed or partial number, and SHALL NOT report zero.

A filter expression the broker rejects SHALL be reported as invalid without
repeating the expression's text, because a filter can carry sensitive literals.

#### Scenario: Browse returns a page

- **WHEN** an operator browses a queue that holds more messages than the page size
- **THEN** the response contains one page of message summaries and either the queue's total message count or a statement that the total is unavailable

#### Scenario: A Core browse of a deep queue reads only its page

- **WHEN** an operator requests the first page of a queue holding many thousands of messages over the Core client
- **THEN** no more messages than that page requires are read from the broker, and the total comes from the broker's count

#### Scenario: An unavailable total is stated, not zero

- **WHEN** the broker's count for a browse cannot be obtained
- **THEN** the response states that the total is unavailable and why, and does not report zero

#### Scenario: Browse honours a filter

- **WHEN** an operator browses with an Artemis filter expression
- **THEN** only messages matching the filter are returned

#### Scenario: Core channel returns a faithful body

- **WHEN** a queue with an available Core connection holds a message with a binary body and the caller holds clear access
- **THEN** the single-message read returns the exact bytes with a binary encoding indicator and states that the Core channel served it

#### Scenario: Single POST per browse

- **WHEN** a browse is served over Jolokia
- **THEN** exactly one Jolokia POST is issued to the target node for that browse and the response states the Jolokia channel served it

#### Scenario: Deep page falls back to Jolokia

- **WHEN** an operator requests a page beyond the bounded Core browse depth on a cluster with a Core connection
- **THEN** that page is served over Jolokia and the response states the channel changed

#### Scenario: Node is explicit or defaulted to the live node

- **WHEN** a browse request omits the node
- **THEN** the message-holding live node of the logical node serving that queue is used, and the response states which node answered

#### Scenario: A message's reply-to destination is exposed

- **WHEN** a browsed message carries a JMS reply-to destination
- **THEN** the browse response includes that destination for the message

#### Scenario: Browsed content is governed

- **WHEN** a user without clear access browses a queue whose messages carry an `Authorization` property and an email in the body
- **THEN** the summaries' body previews and the single-message read show the credential dropped and the email redacted, each identified by location and class

#### Scenario: An invalid filter is not echoed

- **WHEN** an operator browses with a filter the broker rejects
- **THEN** the response states that the filter is invalid and does not contain the filter text

### Requirement: Truncated message content is detected and disclosed

The Jolokia management channel truncates returned message data at the broker's
`management-message-attribute-size-limit`. When a browse is served over Jolokia,
every returned message SHALL carry a boolean indicating whether its body or
properties were truncated, and the single-message read SHALL carry the effective
limit in bytes; the frontend SHALL show an explicit notice, naming the limit and
the `broker.xml` `address-setting` that raises it, whenever truncated content is
displayed. When a browse is served over the Core client, content is not
truncated: the truncation flag SHALL be false and no truncation notice SHALL be
shown.

#### Scenario: A large body is flagged

- **WHEN** a Jolokia-served browsed message's body exceeds the broker's attribute size limit
- **THEN** its truncation flag is true and the detail view shows the limit and the enabling `broker.xml` snippet

#### Scenario: The Core channel does not truncate

- **WHEN** the same large message is read over the Core client
- **THEN** its truncation flag is false, the full body is returned, and no truncation notice is shown

#### Scenario: Small messages are not flagged

- **WHEN** every returned message is within the limit
- **THEN** no truncation flag is set and no truncation notice is shown

### Requirement: A message can be sent to a queue's address

The system SHALL send a message to the address of a named queue on a chosen
node, accepting a body, a message type, a durability flag, standard headers, and
typed application properties. The send SHALL be served over the Core client when
the cluster has an available Core connection and over Jolokia otherwise; over
the Core client the body MAY be binary and application properties SHALL be set
with their declared types. The response SHALL state which channel sent the
message. The send SHALL be audited and rate-limited like every other message
operation.

#### Scenario: Send places a message

- **WHEN** an operator sends a message to a queue's address
- **THEN** the message appears on that address and the response states which node and channel handled it

#### Scenario: Binary send over Core

- **WHEN** an operator sends a message with a binary body on a cluster with a Core connection
- **THEN** the message is sent over the Core client as a bytes message and the response states the Core channel sent it

#### Scenario: Invalid send is rejected

- **WHEN** a send request omits a required field
- **THEN** it is rejected with a validation error and no message is sent

### Requirement: Messages can be moved, retried, deleted, or expired by ids or by filter

The system SHALL expose move, retry, delete, and expire operations on the
messages of one queue, targeted either by an explicit list of message ids or by
an Artemis filter expression. Move SHALL take a target queue. The affected-count
result SHALL be the broker's own operation result where the broker returns one,
and otherwise the number of ids acted on.

An operation by ids SHALL send its ids to the broker in bounded batches, never one request
per id. When it fails after acting on some ids, the result SHALL be reported as partial:
- the count affected before the failure;
- the ids not attempted;
- the error.

It SHALL NOT be reported as a plain failure that implies nothing changed.

#### Scenario: Delete by ids

- **WHEN** an operator deletes three messages by id
- **THEN** those messages are removed from the queue and the affected count is
  reported

#### Scenario: Move by filter

- **WHEN** an operator moves every message matching a filter to another queue
- **THEN** the matching messages are moved and the affected count is reported

#### Scenario: Retry replays from a dead-letter queue

- **WHEN** an operator retries messages on a dead-letter queue
- **THEN** each is returned to its original address

#### Scenario: A large id list is batched

- **WHEN** an operator deletes several hundred messages by id
- **THEN** the ids are sent to the broker in bounded batches rather than one request per id

#### Scenario: A failure part-way is reported as partial

- **WHEN** a move by ids fails after some of the ids were moved
- **THEN** the result is partial and states how many were moved, which ids were not attempted, and the error

### Requirement: A queue can be purged

The system SHALL expose removing every message from one queue of one node. The
frontend SHALL require the confirming button, which names the queue, to be held before a purge is
sent.

#### Scenario: Purge empties the queue

- **WHEN** an operator confirms a purge
- **THEN** every message is removed and the removed count is reported

#### Scenario: Purge needs a held confirmation in the UI

- **WHEN** an operator initiates a purge from the frontend
- **THEN** the purge happens only when its button, which names the queue, has been held

### Requirement: Every mutation supports a dry run that does not act

Every message mutation — send, move, retry, delete, expire, purge — SHALL accept
a dry-run flag. In dry-run mode the system SHALL return an affected-count
estimate — `countMessages(filter)` for a by-filter target, the id count for a
by-id target, the queue's message count for a purge, and one for a send — and
SHALL issue no Jolokia request that changes broker state. The response SHALL
mark the count as a point-in-time estimate.

#### Scenario: Dry run reports a count without acting

- **WHEN** an operator runs a by-filter delete with the dry-run flag
- **THEN** the estimated match count is returned and no message is removed

#### Scenario: Dry run issues no mutating call

- **WHEN** any mutation is invoked in dry-run mode
- **THEN** no state-changing Jolokia operation is sent to the broker

### Requirement: Bulk mutations are capped and the cap is enforced server-side

The system SHALL refuse any mutation whose dry-run affected count exceeds the
configured bulk cap, returning an error that names the count and the cap, unless
the request carries an explicit override flag. The frontend SHALL only send the
override flag after showing the dry-run preview and requiring the confirming button to
be held.

#### Scenario: Over the cap without override is refused

- **WHEN** a by-filter mutation would affect more messages than the cap and no
  override is set
- **THEN** the request is rejected with an error naming the affected count and
  the cap, and nothing is changed

#### Scenario: Override proceeds after confirmation

- **WHEN** the same mutation is retried with the override flag
- **THEN** it proceeds

#### Scenario: Under the cap proceeds normally

- **WHEN** a mutation's affected count is at or below the cap
- **THEN** no override is required

### Requirement: Every mutation writes an audit event in its own transaction

Every message mutation SHALL write an `audit_event` row that is committed with a pending
outcome before the broker call, in its own transaction, and updated after the call to
success with the affected count or to failure with the error. After a partial failure, the
row SHALL also carry the count affected before it.

A dry run SHALL also be audited, marked as a dry run with a success outcome. A broker
failure SHALL still leave a committed failure row and SHALL be returned to the caller as a
structured problem response.

#### Scenario: Successful mutation is audited

- **WHEN** a move succeeds
- **THEN** a committed `audit_event` records the action, target queue, actor,
  affected count, and a success outcome

#### Scenario: Failed mutation is audited

- **WHEN** the broker rejects a delete
- **THEN** a committed `audit_event` records the failure and its error, and the
  caller receives a structured problem response

#### Scenario: Partial mutation is audited with its count

- **WHEN** a delete by ids fails after removing some of the ids
- **THEN** a committed `audit_event` records the failure, the error, and the count removed before the failure

#### Scenario: Dry run is audited

- **WHEN** a mutation runs in dry-run mode
- **THEN** a committed `audit_event` marked as a dry run records the estimated
  count

### Requirement: Message operations require message-IO capability and are rate-limited

The system SHALL gate the message views on the connection's `MESSAGE_IO`
capability, rendering the capability reason and `broker.xml` guidance rather than
hiding the controls when it is unavailable. Every Jolokia request a message operation
issues, including each batch of a by-ids operation and the count read of a browse, SHALL
pass through the per-node management-call rate limiter.

Selecting the Core client for a browse, single-message read, or send SHALL NOT change the
audit, dry-run, bulk-cap, or typed-confirmation behaviour of any operation; those apply
identically regardless of channel. Core subscription connections are not counted against
the per-node call limiter.

#### Scenario: Unavailable capability is explained, not hidden

- **WHEN** a cluster's `MESSAGE_IO` is not available
- **THEN** the message views show why and the enabling configuration, with no silently missing buttons

#### Scenario: Operator calls are rate-limited per node

- **WHEN** message operations issue Jolokia calls to a node
- **THEN** each request, including every batch of a by-ids operation, is subject to the same per-node per-second ceiling as the scrape scheduler

#### Scenario: Channel choice does not weaken safety

- **WHEN** a destructive operation runs on a cluster with a Core connection
- **THEN** its dry-run, bulk cap, typed confirmation, and audit behaviour are exactly as they are over Jolokia

### Requirement: Message operations require the matching permission at the cluster's scope

Browsing messages SHALL require a read permission at the target cluster's
scope. Sending, moving, retrying, deleting, expiring, or purging messages
SHALL each require the corresponding write permission at the target cluster's
scope, including when performed as a dry run.

#### Scenario: Browse requires read permission

- **WHEN** a user without read permission on a cluster attempts to browse a
  queue on it
- **THEN** the request is rejected

#### Scenario: Purge requires the purge permission

- **WHEN** a user holding read but not purge permission on a cluster attempts
  to purge a queue, dry run or not
- **THEN** the request is rejected

### Requirement: A message body's format is detected and never overclaimed

The system SHALL report a detected format for a browsed message's body, and SHALL NOT
report a format it did not successfully parse. Detection SHALL prefer a format declared
by the producer — a content type carried on the message or in its application
properties — over any inspection of the body's bytes. Where no format is declared, the
system MAY infer a structured format from the body, and SHALL do so only by parsing it:
a body reported as JSON SHALL have parsed as JSON, and a body reported as XML SHALL have
parsed as XML with no parser error. A body that matches nothing SHALL be reported as
plain text.

Binary bodies SHALL be identified by their leading bytes where they match a known
container — at minimum gzip, zip, Java serialization, and Avro — and reported by that
name.

#### Scenario: A declared content type wins

- **WHEN** a message declares a content type that disagrees with what its body looks like
- **THEN** the declared type is the reported format

#### Scenario: Inference requires a successful parse

- **WHEN** a text body begins with `{` but does not parse as JSON
- **THEN** the reported format is text, not JSON

#### Scenario: XML parser errors are not ignored

- **WHEN** a text body is offered to the XML parser and the parser reports an error
- **THEN** the body is not reported as XML

#### Scenario: A known binary container is named

- **WHEN** a binary body begins with the gzip magic bytes
- **THEN** the reported format names gzip

### Requirement: A truncated body reports truncation, not a format failure

When a message body is known to have been truncated by the broker's management attribute
size limit, and the truncated body therefore fails to parse, the system SHALL report that
formatting is unavailable **because the broker truncated the body**, and SHALL point at
the existing truncation notice and its enabling `broker.xml` snippet. It SHALL NOT report
the body as malformed or as not being the format it claims.

#### Scenario: Truncated JSON

- **WHEN** a message declared as JSON is truncated and its remaining text does not parse
- **THEN** the panel says the body was truncated by the broker and cannot be formatted,
  and does not say the body is not JSON

### Requirement: A formatted body is presented readably, with raw always available

A body in a structured format SHALL be presentable indented and syntax-highlighted, with
a control to switch between the formatted and the raw body, and controls to copy and to
download the body. A JSON body that parsed and is within the indentation ceiling SHALL also be
presentable as a tree: objects and arrays collapsible, the tree searchable by key and value, every
node reachable by keyboard, and each node offering to copy its path in the form the SQL console
accepts for a JSON body path. Keys and values SHALL be rendered as text, never interpreted as markup.
A binary body SHALL be presented as a hexadecimal and ASCII dump of
its leading bytes; a binary body SHALL NOT be decoded as text into the body view.

#### Scenario: Formatted and raw

- **WHEN** an operator views a JSON body
- **THEN** it is shown indented and highlighted, and can be switched to the raw body

#### Scenario: Tree view

- **WHEN** an operator switches a JSON body to the tree presentation and collapses an object
- **THEN** the object's members are hidden, the object states how many it holds, and expanding it
  shows them again

#### Scenario: Copy a field's path

- **WHEN** an operator copies the path of a nested field from the tree
- **THEN** the clipboard holds that field's path in the SQL console's JSON body path form

#### Scenario: Binary is dumped, not decoded

- **WHEN** an operator views a body whose format is binary
- **THEN** a hexadecimal and ASCII dump of its leading bytes is shown rather than a text
  rendering of those bytes

### Requirement: Oversized bodies degrade instead of blocking the interface

The system SHALL apply size ceilings above which detection inspects only a leading
portion of the body, above which indentation is not performed, and above which syntax
highlighting is not performed. Above every ceiling the body SHALL still be shown as
plain text with copy and download available, and the interface SHALL state that
formatting is off because of the body's size.

#### Scenario: A very large body is still usable

- **WHEN** an operator views a body larger than every formatting ceiling
- **THEN** the body is shown unformatted, the panel states that formatting is off for a
  body this size, and copy and download remain available

### Requirement: A message's type is reported by name

A browsed message's type SHALL be presented by its name rather than as the broker's
integer code.

#### Scenario: Type is a word

- **WHEN** a browsed message carries the broker's type code for a text message
- **THEN** the detail view shows the type's name, not the integer

### Requirement: Text carried in a bytes message is read as text

When the system reads a bytes message from the broker, it SHALL present the body as text wherever the
body's bytes are well-formed UTF-8 containing no control characters other than tab, line feed and
carriage return. Where the bytes begin with a gzip or zlib (deflate) header and decompress, within a
fixed size ceiling, to such text, the system SHALL present the decompressed text and SHALL report the
compression alongside the detected format. A body that fails any of these tests SHALL remain binary
and SHALL be presented as it was before this requirement. The decision SHALL be made once, where the
body is read, so that every view of the message (the message view, the queue's body column, the SQL
console, the message index, request-reply payloads and tool access) presents the same body the same way.

The interface SHALL NOT state that the body was converted. The message's type SHALL still be reported
as a bytes message. Relaying a message to another destination SHALL carry its original bytes unchanged.
Reading the body SHALL never fail because a decode failed: a body that cannot be decoded is binary.

#### Scenario: JSON sent as bytes reads as JSON

- **WHEN** a producer sends a bytes message whose body is the UTF-8 encoding of a JSON object, and an
  operator opens it from the queue's messages
- **THEN** the body is shown indented as JSON, the format reads JSON, the message type reads bytes, and
  no notice about decoding is shown

#### Scenario: The queue's body column shows the text

- **WHEN** the queue's messages include a bytes message carrying UTF-8 text
- **THEN** the body column previews that text, not an encoding of its bytes

#### Scenario: Compressed JSON is decompressed and labelled

- **WHEN** a bytes message's body is gzip-compressed JSON below the decompression ceiling
- **THEN** the body is shown as JSON and its format names gzip

#### Scenario: A decompression bomb stays binary

- **WHEN** a gzip body would decompress to more than the decompression ceiling
- **THEN** decompression stops at the ceiling, the body is reported as binary gzip with its hexadecimal
  dump, and the read completes normally

#### Scenario: Binary stays binary

- **WHEN** a bytes message's body is not well-formed UTF-8, or contains a NUL or another control
  character
- **THEN** the body is reported as binary and shown as a hexadecimal dump

#### Scenario: A relayed message keeps its bytes

- **WHEN** a bytes message carrying UTF-8 JSON is moved or copied to another queue
- **THEN** the destination message carries the original bytes as a bytes message

### Requirement: A partitioned log is browsed by partition, offset or time
On a system whose destinations are partitioned logs, browsing SHALL let the operator pick a partition and start from the earliest offset, the latest records, an offset or a time, with the time shown in the operator's time zone. Browsing SHALL never join a group or change an offset, and SHALL be bounded by count and bytes like every browse. 
#### Scenario: Browsing from a time
- **WHEN** an operator browses partition 2 of a Kafka topic from 09:00
- **THEN** the records from the first offset at or after 09:00 are shown, and no group's offsets change

### Requirement: A partitioned browse states where the log begins and ends
A start past the end SHALL show no records and name the end offset; a start before the log's first offset SHALL begin at it and say that retention removed the earlier records. A record with no value SHALL read as a tombstone. Uncommitted and aborted transactional records SHALL not be shown, and the view SHALL say so.

#### Scenario: An offset past the end
- **WHEN** an operator browses partition 0 from offset 900 and its end offset is 750
- **THEN** no records are shown, and the view says the partition ends at offset 750

#### Scenario: Records removed by retention
- **WHEN** an operator browses from offset 10 and the partition's first offset is 400
- **THEN** the records from offset 400 are shown with a notice that retention removed the earlier ones

#### Scenario: A tombstone
- **WHEN** a browsed record has a key and no value
- **THEN** it reads "Tombstone (no value)", never as an empty body

### Requirement: A browse filter the system lacks is disabled with the reason
Where the system cannot filter a browse, the filter SHALL be disabled with the reason and the API SHALL refuse a filter with it.

#### Scenario: No filter on Kafka
- **WHEN** an operator browses a Kafka topic
- **THEN** the filter input is disabled with the driver's reason, and a filter sent to the API is refused with it

### Requirement: Purge and truncate state their reach on a partitioned log
On a partitioned log, purging a subscription SHALL move only its group to the end, and only while the group is empty and not rebalancing. Truncating a topic or a partition, before an offset or a time, SHALL remove records for every group. Both previews SHALL say so in words, naming the active members that block a purge and every group that loses unread records in a truncate, with how many. Truncating a compacted topic SHALL be refused with the reason.

#### Scenario: Truncating a shared topic
- **WHEN** an operator previews truncating a topic that three groups read
- **THEN** the preview names the three groups and the unread records each loses, and confirming needs a press and hold

#### Scenario: Purging an active group
- **WHEN** an operator previews purging a subscription whose group has members
- **THEN** the preview names the members, and purge is disabled with the reason

#### Scenario: A compacted topic
- **WHEN** an operator previews truncating a compacted topic
- **THEN** the preview says compacted topics cannot be truncated, and truncate is disabled with that reason

### Requirement: Purge and truncate are held, gated and audited
Purge and truncate SHALL be held destructive confirmations, SHALL go through the approval gate when a policy holds them, and SHALL be audited with the offsets before and after. Truncating SHALL need the destination truncate permission.

#### Scenario: Truncating without permission
- **WHEN** an operator without the destination truncate permission opens a topic
- **THEN** truncate is shown disabled naming the missing permission, and the API refuses it

### Requirement: Per-message actions the system lacks are disabled with the reason
Where a system cannot move, copy or delete a single message, those actions SHALL be shown disabled with the driver's reason, on the message and in bulk, and the API SHALL refuse them with the same reason. A shared screen SHALL gate a system's own actions by what the kind declares, never by naming a system.

#### Scenario: Deleting a Kafka record
- **WHEN** an operator selects a Kafka record
- **THEN** delete, move and copy are disabled, and each says that Kafka's log does not delete single records

#### Scenario: The API refuses it too
- **WHEN** a client asks to delete a Kafka record by id
- **THEN** the request is refused with the same reason and nothing is audited as attempted on the broker
