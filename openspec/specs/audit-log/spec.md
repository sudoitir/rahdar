# audit-log Specification

## Purpose

Defines how Rahdar records who did what to which broker and with what
outcome — the real, authenticated actor an action is attributed to, the
lifecycle of an audit row within the command's transaction, and the filtered
read an operator uses to review the trail.

## Requirements

### Requirement: Every mutating call is attributed to a resolved actor

For every mutating operation the system SHALL resolve an actor and record, on
the audit row, the actor's username, the resolved user id when the actor is an
authenticated user, the request source IP, and a request id. When an
authenticated principal is present its username and user id SHALL be used;
otherwise the literal `anonymous` SHALL be recorded with no user id. The
request id SHALL be taken from an `X-Request-Id` request header when present
and otherwise generated. Operations originating from the scrape scheduler
SHALL be attributed to `system` with no user id.

#### Scenario: Authenticated operator is attributed by identity

- **WHEN** a mutation is made by an authenticated user
- **THEN** the audit row records that user's username and user id, the
  caller's source IP, and a request id

#### Scenario: Unauthenticated operator is anonymous

- **WHEN** an audited action occurs with no authenticated principal
- **THEN** the audit row records `anonymous`, the caller's source IP, and a
  request id, with no user id

#### Scenario: Failed login is anonymous

- **WHEN** a login attempt fails
- **THEN** the resulting audit row records `anonymous` with no user id

#### Scenario: Supplied request id is preserved

- **WHEN** a mutation request carries an `X-Request-Id` header
- **THEN** that value is recorded on the audit row

### Requirement: An audit row is created before the broker call and updated with the outcome

The system SHALL commit the `audit_event` row with a pending outcome before issuing the
broker call, in its own transaction, so that the record exists even if the process stops
during the call. After the call, it SHALL update the same row, in a separate transaction:
- to a success outcome with the affected count; or
- to a failure outcome with the error message and, where the operation acted partially,
  the count affected before the failure.

The row SHALL record the action, the target type and name, the cluster and node, the
parameters, and whether it was a dry run. No mutating path SHALL issue a broker call before
its audit row has committed.

A command that fans out across nodes SHALL NOT hold a database transaction open across its
broker calls.

Creating, updating, deleting, or testing an alert rule or a notification channel SHALL follow
this same pattern. Machine-generated alert firings, resolutions, and notification delivery
attempts SHALL NOT create an audit event.

#### Scenario: Outcome transitions from pending to success

- **WHEN** a mutation completes without error
- **THEN** its audit row is committed with a success outcome and the affected
  count

#### Scenario: Outcome transitions from pending to failure

- **WHEN** the broker call throws
- **THEN** its audit row is committed with a failure outcome and the error

#### Scenario: The pending row survives a crash during the broker call

- **WHEN** the process stops after the broker call was issued but before its outcome was recorded
- **THEN** the audit trail contains the committed pending row for that call

#### Scenario: A partial failure records what was already done

- **WHEN** an operation acting on a list of items fails after acting on some of them
- **THEN** its audit row is committed with a failure outcome, the error, and the count affected before the failure

#### Scenario: Dry run is recorded

- **WHEN** a mutation is a dry run
- **THEN** the audit row is marked as a dry run

#### Scenario: Alert rule changes are audited

- **WHEN** an operator creates, updates, deletes, or tests an alert rule or a
  notification channel
- **THEN** an audit row is created for that operator action, following the same
  pending-then-outcome lifecycle as any other mutation

#### Scenario: An alert firing does not create an audit row

- **WHEN** an alert rule transitions to firing or resolves, or a notification
  delivery attempt is made
- **THEN** no audit event is created for it

### Requirement: The audit trail is readable with filters

The system SHALL expose a paged read of audit events for a cluster, filterable
by actor username, action, outcome, and a time range, ordered most-recent first.
Each returned event SHALL include its timestamp, actor, action, target, affected
count, outcome, dry-run flag, parameters, and error when present.

#### Scenario: Filter by action and outcome

- **WHEN** an operator lists audit events filtered to a single action and the
  failure outcome
- **THEN** only failed events for that action are returned, newest first

#### Scenario: Time range narrows the result

- **WHEN** a from/to range is supplied
- **THEN** only events whose timestamp falls in the range are returned

### Requirement: The audit screen shows outcome without relying on colour alone

The frontend SHALL present each audit event's outcome with a status word as well
as a colour, and SHALL make the parameters and any error inspectable per row.

#### Scenario: Outcome is legible without colour

- **WHEN** the audit list renders a failed event
- **THEN** the row shows the word for the failure outcome, not only a coloured mark

#### Scenario: Parameters are inspectable

- **WHEN** an operator expands an audit row
- **THEN** the recorded parameters and error text are shown

### Requirement: Identity and governance actions are audited

Creating, updating, disabling, or deleting a user, role, permission grant,
environment, API token, or OIDC role mapping, and every login, logout, and
password change, SHALL follow the same pending-then-outcome audit lifecycle as
any other mutation.

#### Scenario: A role grant change is audited

- **WHEN** an administrator grants or revokes a role from a user
- **THEN** an audit row is created for that action, following the pending-then-
  outcome lifecycle

#### Scenario: A login attempt is audited

- **WHEN** a user attempts to log in, whether the attempt succeeds or fails
- **THEN** an audit row is created recording the attempt and its outcome

### Requirement: The audit trail's actor filter is a real user, not free text

The audit read SHALL allow filtering by a specific user account, in addition
to the existing action, outcome, and time-range filters.

#### Scenario: Filter by user

- **WHEN** an operator filters the audit trail to one user account
- **THEN** only events attributed to that user's account are returned

### Requirement: An audit event outlives what it describes

An audit event SHALL keep its actor user id, cluster id, node id, target name and cluster name exactly as recorded, even after that user, cluster or node is removed. Removing a user, cluster or node SHALL NOT modify or delete any audit event. The audit read for a removed cluster SHALL remain available to a caller holding a global grant for the audit read.

#### Scenario: Removing a cluster keeps its audit trail intact

- **WHEN** a cluster with recorded audit events is removed
- **THEN** its audit events still carry that cluster's id and name and are returned by the audit read to a globally granted caller

#### Scenario: Removing a user keeps attribution

- **WHEN** a user who performed audited actions is deleted
- **THEN** those audit events still record that user's username and user id

### Requirement: Audit parameters never carry a sensitive value

Before an audit record is written, its parameters SHALL be passed through the content policy. That covers query text, filter expressions and selector strings. Values the policy detects as sensitive, and literals compared with classified fields, SHALL be masked, and credentials SHALL be dropped. Masking SHALL NOT change the record's action, target or outcome.

#### Scenario: A move by filter does not store the filter literal

- **WHEN** an operator moves messages with the filter `email = 'jane@example.com'`
- **THEN** the audit record's filter parameter shows the literal redacted

### Requirement: Governance actions and clear views are audited

The system SHALL write an audit record for each of these actions:

- creating, changing, enabling, disabling and deleting a masking rule;
- confirming and dismissing a finding;
- every response that served sensitive values in clear.

A clear-view record SHALL carry the classes and counts served and SHALL NOT carry any value.

#### Scenario: Disabling a built-in rule is audited

- **WHEN** an administrator disables the built-in `Authorization` rule
- **THEN** an audit record names the actor, the rule and the change

### Requirement: An audit event can belong to a parent event

An audit event MAY name another audit event as its parent, when it was issued as one part
of a larger operation such as a bulk run. The parent SHALL be committed before any of its
children. A child SHALL carry everything an ordinary audit event carries. Its parent link
adds to that and replaces nothing.

The audit trail SHALL show, for a child, the parent it belongs to. For a parent, it SHALL
show its children, reachable from the parent.

#### Scenario: A child event links to its parent

- **WHEN** an operator opens the audit event for a subscription deleted by a bulk run
- **THEN** the event shows the bulk run's event it belongs to, and following that link
  shows the run's event with all of its subscription events

### Requirement: A single audit event is readable by id and can be linked to

The system SHALL expose a read of one audit event of a cluster by its id, gated exactly as the filtered read is: a caller without the read permission on the cluster gets the same 404 as for a cluster that does not exist. An id that is not among the cluster's audit events, whether it never existed or was removed, SHALL be answered with 404.

The audit screen SHALL open the details of the event named by an `event` search parameter, including an event that is not on the loaded page, and SHALL say that the event no longer exists when the id is unknown. Each row SHALL offer a "Copy link" item in its actions menu that copies the address of that event's details.

#### Scenario: An audit event is read by id

- **WHEN** an operator with read permission requests one audit event of a cluster by its id
- **THEN** the response is that event, with the same fields as in the filtered read

#### Scenario: An unknown audit event is a 404

- **WHEN** an operator requests an audit event id that does not exist, or that belongs to another cluster
- **THEN** the response is 404

#### Scenario: A caller without a grant is told nothing

- **WHEN** a caller with no read permission on the cluster requests one of its audit events
- **THEN** the response is 404, the same as for a cluster that does not exist

#### Scenario: A link opens an audit event that is not on the page

- **WHEN** an operator opens the audit screen with `?event=<id>` naming an event older than the loaded page
- **THEN** the details of that event are shown

#### Scenario: A link to an audit event that does not exist says so

- **WHEN** an operator opens the audit screen with `?event=<id>` naming an event that does not exist
- **THEN** the screen states that the event no longer exists

#### Scenario: Copying a link to an audit event

- **WHEN** an operator chooses "Copy link" in a row's actions menu
- **THEN** the address of the audit screen with `?event=` set to that row's id is copied

### Requirement: Audit events have a configurable retention, keeping everything by default
Rahdar SHALL keep audit events forever unless an administrator sets a retention period for the audit
store, which SHALL be at least 30 days. The purge of audit events SHALL itself be recorded as an audit
event that the purge does not remove. Rahdar SHALL consult every plugin that declares an audit retention
hold before purging, and SHALL leave every record a hold covers, whatever the retention. A hold
provider that errors, does not answer in time or is unavailable SHALL cause that run to purge no audit
event and to report the failure. The purge SHALL NOT remove events a registered receiver has not
acknowledged, up to a configured maximum age. Audit events SHALL be stored in monthly partitions. A
monthly partition SHALL be dropped only when every row in it is eligible: older than the retention,
acknowledged by every reliable receiver or older than the maximum age, and covered by no hold. When a
partition holds any row that is not eligible, the purge SHALL remove only the eligible rows, as it
would without partitions.

#### Scenario: Default
- **WHEN** no audit retention is set
- **THEN** no audit event is purged

#### Scenario: Configured
- **WHEN** a retention is set and events are older than it
- **THEN** the older events are purged, and one `PURGE_STORE` event records the store, the retention and the number purged

#### Scenario: The purge record survives
- **WHEN** the audit store is purged
- **THEN** the `PURGE_STORE` event of that purge is still present afterwards

#### Scenario: Hold respected
- **WHEN** a plugin holds records inside the purge window
- **THEN** the purge leaves them and purges the rest

#### Scenario: Hold provider failure
- **WHEN** a plugin that declares a retention hold errors or does not answer in time
- **THEN** no audit event is purged in that run and the failure is shown in the store's purge status

#### Scenario: A month is wholly eligible
- **WHEN** every row in a monthly partition is older than the retention, acknowledged or past the maximum age, and held by no one
- **THEN** the partition is dropped and the purge is audited

#### Scenario: A month holds one ineligible row
- **WHEN** a monthly partition holds a row that a receiver has not acknowledged, or a hold covers
- **THEN** the partition is kept and only its eligible rows are purged

### Requirement: Refused requests are audited

Rahdar SHALL record an audit event for every request refused for lack of a permission, naming the
actor, the permission, the cluster and the resource, and whether the resource was hidden (answered
as not found) or forbidden. Identical refusals by the same actor within one minute SHALL be counted
on one event rather than recorded again. A refused broker change SHALL leave a refused audit event,
not a missing one. The action and the target of a lifecycle event SHALL be named by role
(create, update, delete, pause, resume and reset counters of a subscription, destination or
route), and the resource by its canonical id.

#### Scenario: A refused purge is audited

- **WHEN** a Team Viewer tries to purge `orders.in`
- **THEN** an audit event records the refusal with `subscription:purge` and `orders.in`

#### Scenario: Repeated refusals are counted

- **WHEN** the same client is refused the same action 50 times in a minute
- **THEN** one audit event records the refusal with a count of 50

#### Scenario: A lifecycle event names a role, not a system word

- **WHEN** an operator deletes an Artemis queue
- **THEN** the audit event records a subscription delete whose target is the queue's canonical resource id

### Requirement: The audit trail shows only what the reader may see

A reader without the permission to read a cluster's whole audit trail (`cluster:read` through a
grant) SHALL see only that cluster's audit events about resources they may read.

#### Scenario: A team member reads audit

- **WHEN** an Orders-only user reads prod's audit trail
- **THEN** only events about Orders' resources are returned

### Requirement: Users with permission SHALL be able to export filtered audit records

Rahdar SHALL export the audit records matching the current filters as CSV, JSON or NDJSON. The export SHALL be limited to users with the right permission and SHALL be streamed so a large export does not exhaust memory.

#### Scenario: Filtered NDJSON export

- **WHEN** an auditor exports records filtered by actor and time range as NDJSON
- **THEN** the file contains exactly the matching records

#### Scenario: Not permitted

- **WHEN** a user without the permission requests an export
- **THEN** it is refused

#### Scenario: Large export

- **WHEN** an export matches millions of rows
- **THEN** it streams with bounded memory and can be cancelled

### Requirement: Every audit export SHALL itself be audited

Rahdar SHALL record who exported audit data, with which filters and format.

#### Scenario: Export audited

- **WHEN** a user exports audit records
- **THEN** a new audit row names the user, the filters and the format

### Requirement: Audit exports SHALL not leak secrets or data the exporter cannot see

Exported records SHALL contain exactly what the exporter could see in the audit view, with the same redaction and scope limits. A CSV export SHALL neutralise values a spreadsheet would run as a formula.

#### Scenario: Redaction

- **WHEN** a record contains a redacted field
- **THEN** the export contains the redacted value

#### Scenario: Scoped auditor

- **WHEN** an exporter may see the audit of one environment only
- **THEN** the export contains no record from another environment

#### Scenario: Formula in a value

- **WHEN** an audited value starts with `=`, `+`, `-` or `@` and is exported as CSV
- **THEN** opening the file in a spreadsheet shows the value as text and runs nothing

### Requirement: A plugin SHALL be able to receive audit events reliably and in order

Rahdar SHALL deliver every audit event to registered plugin receivers at least once, in commit order, across all replicas. Each delivered record SHALL carry its position in that order; a record that changes (an outcome replacing `PENDING`, a recount) SHALL be delivered again at a new position, and `(id, position)` SHALL identify a delivery for de-duplication. Once an audit record has an outcome, Rahdar SHALL refuse any further change to it except the count of a refused request. A receiver that is slow or down SHALL NOT lose events or block the audited operation, and events SHALL be replayed from the last acknowledged position. The audit purge SHALL NOT remove events a registered receiver has not acknowledged, up to a configured maximum age; past it, Rahdar SHALL record the gap as an audit event and alert. Receiving audit events SHALL be a plugin capability shown to the admin when the plugin is installed.

#### Scenario: Receiver down

- **WHEN** a receiver is unavailable while events occur
- **THEN** events are held and delivered in order when it returns

#### Scenario: Duplicate delivery

- **WHEN** Rahdar restarts before an acknowledgment is stored
- **THEN** events after the last acknowledged position are delivered again, with a stable identifier for de-duplication

#### Scenario: Operation not blocked

- **WHEN** a receiver is slow
- **THEN** audited operations complete without waiting for it

#### Scenario: Purge would outrun a receiver

- **WHEN** retention would purge events a receiver has not acknowledged
- **THEN** they are kept until acknowledged or until the maximum age, and a gap past that age is audited and alerted, never silent

#### Scenario: Two replicas

- **WHEN** two replicas record events at the same time
- **THEN** every receiver gets them in the same order, the order in which they were committed, with no event skipped

#### Scenario: Outcome recorded later

- **WHEN** an audited operation records its outcome after it started
- **THEN** the receiver gets the pending record and then the record with its outcome, at a later position

#### Scenario: Undeclared receiver

- **WHEN** a plugin provides a receiver without declaring audit access in its manifest
- **THEN** the plugin is not activated

### Requirement: The audit SPI SHALL be generic

The export and SPI SHALL NOT be tied to any named product or destination.

#### Scenario: Third-party receiver

- **WHEN** a third-party plugin implements a receiver
- **THEN** it works with no Rahdar change

### Requirement: An action whose outcome is unknown is audited in doubt
An audit row SHALL take the outcome `IN_DOUBT` when its action's confirmation never came back, so the action may or may not have happened. The audit screen SHALL show it as "in doubt", in words, and SHALL offer it as an outcome filter. Audit receivers SHALL see the same outcome.

#### Scenario: An unconfirmed send in the audit trail
- **WHEN** a send is left in doubt
- **THEN** its audit row has the outcome `IN_DOUBT` with the reason, the audit screen shows "in doubt", and filtering by "In doubt" finds it
