# alerting Specification

## Purpose

Defines how an operator sets a condition worth being told about — a metric crossing a
threshold, or a cluster entering a bad HA state — and how Rahdar debounces, tracks,
and delivers that condition to a Slack, Microsoft Teams, PagerDuty, email or signed webhook
channel until it clears.

## Requirements

### Requirement: An alert rule is either a metric threshold or a state condition

The system SHALL support two kinds of alert rule: a metric-threshold rule, which
compares a named metric's current value for each matching subject against a threshold
using a comparator; and a state-condition rule, which watches a fixed, closed set of
cluster health transitions (split-brain, node down, replication behind, cluster
degraded). A rule SHALL be exactly one kind, and creating or updating a rule with both
or neither kind's fields populated SHALL be rejected.

#### Scenario: A threshold rule is created

- **WHEN** an operator creates a rule with a metric, comparator, and threshold
- **THEN** the rule is stored as a metric-threshold rule and no state condition is
  accepted alongside it

#### Scenario: A state rule is created

- **WHEN** an operator creates a rule naming a state condition (split-brain, node down,
  replication behind, or cluster degraded)
- **THEN** the rule is stored as a state-condition rule and no metric/comparator/
  threshold is accepted alongside it

#### Scenario: A malformed rule is rejected

- **WHEN** a rule is submitted with both a metric and a state condition, or with
  neither
- **THEN** the system rejects the request without creating a rule

### Requirement: A metric-threshold rule evaluates the current value per matching subject

For a gauge metric (queue depth, consumer count, delivering count, scheduled count),
the system SHALL evaluate the rule against each matching subject's most recently
scraped value. For a rate metric (messages added, messages acked), the system SHALL
evaluate the rule against each matching subject's throughput computed over a recent
window, using the same restart-safe never-negative computation the metrics capability
already uses for chart rates. For the derived acknowledgement-rate-per-consumer metric,
the system SHALL evaluate as defined by the slow-consumer requirement below. A subject
with fewer than two samples in the rate window SHALL be treated as having no evaluable
value for that tick, neither active nor resolving.

#### Scenario: A depth threshold fires per queue

- **WHEN** a rule thresholds queue depth and several queues exceed it
- **THEN** each exceeding queue is evaluated and tracked independently under the same
  rule

#### Scenario: A brand-new queue does not falsely trigger a rate rule

- **WHEN** a queue is scraped for the first time and has not yet accumulated two
  samples in the rate window
- **THEN** a rate-threshold rule produces no verdict for that queue this tick, neither
  firing nor resolving

#### Scenario: A counter reset never triggers a false negative-rate reading

- **WHEN** a monotonic counter backing a rate metric resets (a broker restart) within
  the evaluation window
- **THEN** the computed rate for that subject is never negative

### Requirement: A state-condition rule evaluates cluster health, not a metric

A split-brain rule SHALL fire only when the cluster's split-brain status is corroborated
critical, never on a first-sighting suspected status. A node-down rule SHALL fire when a
manageable node is unreachable or reports stopped. A replication-behind rule SHALL fire
when a backup node's replication is not in sync. A cluster-degraded rule SHALL fire
when the cluster's overall health level is degraded or worse.

#### Scenario: A first-sighting split-brain does not fire

- **WHEN** two nodes are seen live within the same refresh cycle for the first time
- **THEN** a split-brain rule does not fire, since the condition is only suspected, not
  yet corroborated

#### Scenario: A corroborated split-brain fires

- **WHEN** the same dual-active condition is corroborated on a later refresh cycle
- **THEN** a split-brain rule for that cluster fires

### Requirement: A rule debounces through a PENDING state for a configured duration

Each rule SHALL have a configurable duration; a condition becoming active SHALL move
that (rule, subject) from OK to PENDING, and only to FIRING once the condition has
remained continuously active for that duration. A duration of zero SHALL fire
immediately on the condition becoming active. The condition becoming false at any point
before the duration elapses SHALL return the (rule, subject) to OK, resetting the
elapsed time.

#### Scenario: A brief threshold breach does not fire

- **WHEN** a metric crosses a threshold for less than the rule's configured duration
  and then returns below it
- **THEN** the rule never reaches FIRING for that subject

#### Scenario: A sustained breach fires once the duration elapses

- **WHEN** a metric stays across a threshold continuously for at least the rule's
  configured duration
- **THEN** the rule transitions to FIRING for that subject at the moment the duration
  elapses

#### Scenario: A zero-duration rule fires immediately

- **WHEN** a rule's configured duration is zero and its condition becomes active
- **THEN** the rule fires for that subject on the same evaluation

#### Scenario: The debounce survives a restart

- **WHEN** Rahdar restarts while a (rule, subject) is PENDING
- **THEN** the elapsed time toward FIRING is preserved and evaluation resumes from
  where it left off

### Requirement: A firing resolves when its condition is no longer active

The system SHALL transition a FIRING (rule, subject) back to OK the first evaluation on
which its condition is no longer active, and SHALL record the resolution time. A
subject that disappears entirely (for example, a deleted queue) while PENDING or FIRING
SHALL be treated as resolved rather than left in a state that can never change again.

#### Scenario: A cleared threshold resolves

- **WHEN** a firing metric-threshold subject's value returns to the non-alerting side
  of the threshold
- **THEN** that (rule, subject) resolves on the same evaluation

#### Scenario: A deleted subject resolves rather than hangs

- **WHEN** a queue with a PENDING or FIRING alert state is deleted from the broker
- **THEN** its alert state resolves and is removed rather than remaining indefinitely

### Requirement: Every firing and resolution is recorded in a durable history

The system SHALL append a history record each time a (rule, subject) starts firing and
each time it resolves, including the rule, the subject, the severity, the observed
value, and the start and resolution timestamps. This history SHALL be independently
queryable from the current firing state.

#### Scenario: History survives after resolution

- **WHEN** a firing has resolved
- **THEN** an operator can still see when it started, what value triggered it, and when
  it resolved

### Requirement: A firing rule delivers to its bound notification channels

A rule SHALL be routable to zero or more notification channels. When a rule's
evaluation produces one or more new firings or resolutions in a single evaluation
tick, the system SHALL queue exactly one notification per bound channel for that
tick, describing every affected subject in that one notification — never one
notification per subject.

#### Scenario: Many subjects crossing at once produce one notification

- **WHEN** a single evaluation causes 50 subjects under one rule to start firing
  simultaneously
- **THEN** each bound channel receives exactly one notification for that evaluation,
  listing all 50 subjects

#### Scenario: An unrouted rule still tracks state

- **WHEN** a rule has no bound channels
- **THEN** it still evaluates, debounces, fires, resolves, and records history — it
  simply delivers nothing

### Requirement: Notification delivery is retried and durable across a restart

A queued notification SHALL be retried with increasing delay on failure, up to a
bounded number of attempts, after which it SHALL be marked permanently failed rather
than retried forever. Queued notifications SHALL survive a Rahdar restart and resume
being retried afterward.

#### Scenario: A transient failure is retried

- **WHEN** a notification delivery attempt fails with a server error
- **THEN** the system retries after a delay, increasing on each subsequent failure

#### Scenario: Repeated failure gives up

- **WHEN** a notification has failed delivery the maximum configured number of times
- **THEN** it is marked permanently failed and no further attempts are made

#### Scenario: A pending notification survives a restart

- **WHEN** Rahdar restarts while a notification delivery is queued or awaiting retry
- **THEN** the notification is still retried after Rahdar comes back up

### Requirement: A Slack channel delivers a readable message

A Slack channel SHALL deliver notifications via an incoming webhook with a
human-readable summary. An invalid or revoked webhook SHALL be treated as
permanently failed without retry.

#### Scenario: A revoked Slack webhook is not retried

- **WHEN** a Slack channel's webhook URL has been revoked
- **THEN** the delivery is marked permanently failed on the first attempt, without
  retrying

### Requirement: A webhook channel's delivery is signed and rate-limit aware

A generic webhook channel SHALL sign each delivery so the receiver can verify it
originated from Rahdar and was not replayed or altered, and SHALL include an
identifier a receiver can use to deduplicate retried deliveries. The system SHALL
honor a receiver's request to slow down before retrying.

#### Scenario: A receiver can verify the signature

- **WHEN** a webhook channel receives a delivery
- **THEN** it can compute a matching signature over the delivery id, timestamp, and
  body using the channel's shared secret

#### Scenario: A rate-limited receiver is respected

- **WHEN** a webhook receiver responds indicating the sender should wait before
  retrying
- **THEN** the next retry attempt is delayed at least as long as requested

### Requirement: A notification channel's secret is never exposed in plaintext after creation

The system SHALL store a notification channel's secret material (a Slack webhook URL,
a webhook signing secret) encrypted at rest, SHALL accept it only on create or update,
and SHALL NOT return it in plaintext from any read.

#### Scenario: Reading a channel does not reveal its secret

- **WHEN** an operator reads a previously created channel's configuration
- **THEN** the secret material is masked, not returned in plaintext

#### Scenario: An operator can test a channel without guessing its secret

- **WHEN** an operator triggers a test notification on an existing channel
- **THEN** Rahdar sends the test using the stored secret without requiring the
  operator to re-enter it

### Requirement: Rule and channel changes are audited; firings and deliveries are not

Creating, updating, deleting, or testing an alert rule or a notification channel SHALL
be recorded as an audited operator action. A rule firing, resolving, or a notification
delivery attempt SHALL NOT create an audit event — these are recorded in the alert
history and delivery ledger instead.

#### Scenario: Editing a rule is audited

- **WHEN** an operator changes a rule's threshold
- **THEN** an audit event records who changed it and what changed

#### Scenario: A firing does not create an audit event

- **WHEN** a rule transitions to FIRING
- **THEN** no audit event is created for that transition; it appears only in the alert
  firing history

### Requirement: Built-in critical rules are seeded per cluster and remain ordinary rules

When a cluster is registered, the system SHALL seed the state-condition rules the cluster's system can answer:
split-brain, clock skew, node-down and replication-behind for an Artemis cluster, and cluster-degraded for a Kafka
or RabbitMQ cluster. These seeded rules SHALL behave as ordinary rules (editable, routable to channels, and
disableable), not as unconditional checks outside the rule model.

#### Scenario: A newly registered cluster has built-in rules

- **WHEN** an Artemis cluster is registered
- **THEN** split-brain, clock-skew, node-down and replication-behind rules exist for it, unbound to any channel
  and enabled by default

#### Scenario: A newly registered Kafka cluster is watched through its own health

- **WHEN** a Kafka or RabbitMQ cluster is registered
- **THEN** one cluster-degraded rule exists for it, and no Artemis HA rule

#### Scenario: A built-in rule can be silenced

- **WHEN** an operator disables the seeded replication-behind rule for a cluster
- **THEN** it no longer evaluates or fires for that cluster until re-enabled

### Requirement: The alerts screen shows current firings, history, and rule management

The system SHALL provide a screen listing currently firing alerts for a cluster, a
browsable history of past firings and resolutions, and management of rules and their
channel bindings. Firing alerts SHALL also be reflected as an indicator on the
affected node in the cluster's topology view, and as a count visible outside the
current cluster's view.

#### Scenario: A firing alert appears on the topology graph

- **WHEN** a state-condition rule scoped to a node is firing
- **THEN** that node's topology graph representation shows a firing indicator

#### Scenario: An operator sees firing counts while viewing another cluster

- **WHEN** an operator is viewing a cluster other than the one with active firings
- **THEN** a firing count for the other cluster is still visible somewhere in the
  application shell

### Requirement: Alert rule and channel writes require the matching permission

Creating, updating, deleting, or testing an alert rule or a notification
channel SHALL require a write permission at the target cluster's scope.
Reading alert rules, firings, or channels SHALL require read permission at
that scope, and any cross-cluster firing summary SHALL be filtered to clusters
the caller holds read permission on.

#### Scenario: Rule write requires the write permission

- **WHEN** a user without write permission on a cluster attempts to create an
  alert rule for it
- **THEN** the request is rejected

#### Scenario: Cross-cluster firing summary is filtered

- **WHEN** a user holding read permission on only some clusters requests the
  firing summary
- **THEN** only firings for clusters they hold read permission on are included

### Requirement: A slow-consumer rule evaluates acknowledgement rate per consumer

The system SHALL offer an acknowledgement-rate-per-consumer metric whose subject universe
is restricted to queue subjects that **both** have at least one consumer attached and a
non-zero message backlog at their most recent scrape. A queue with no consumers, or with
consumers but no backlog, SHALL NOT be a subject of this metric — it is idle, not slow.

The metric's value SHALL be the queue's acknowledgement throughput over the same recent
window used by rate metrics, divided by its consumer count, and SHALL inherit that
window's restart-safe never-negative computation, so that a broker restart resetting the
underlying counter cannot produce a firing. A subject with fewer than two samples in the
window SHALL be absent from the evaluation rather than treated as zero.

Subjects SHALL be keyed the same way as other queue-scoped metric subjects, including
when the rule is scoped to a node.

#### Scenario: A queue with no backlog is not a subject

- **WHEN** a queue has consumers attached and no messages
- **THEN** it is not evaluated by a slow-consumer rule

#### Scenario: A queue with no consumers is not a subject

- **WHEN** a queue has a backlog and no consumers attached
- **THEN** it is not evaluated by a slow-consumer rule

#### Scenario: Attached, backlogged and not draining

- **WHEN** a queue has consumers attached, a non-zero backlog, and an acknowledgement
  rate per consumer below the rule's threshold
- **THEN** the rule is active for that queue

#### Scenario: Attached, backlogged and draining

- **WHEN** the same queue's acknowledgement rate per consumer is above the threshold
- **THEN** the rule is evaluated for that queue and is not active

#### Scenario: A counter reset does not fire a slow-consumer rule

- **WHEN** the acknowledgement counter backing the metric resets within the window
- **THEN** the computed rate is not negative and no firing is produced by the reset

#### Scenario: One sample in the window

- **WHEN** a subject has only one sample in the rate window
- **THEN** it produces no verdict this tick, neither firing nor resolving

### Requirement: Rahdar's own slow-consumer detection states its attribution limit

Where the system derives slow-consumer state itself, it SHALL attribute that state to a
queue on a node and SHALL state in the interface that it cannot attribute it to an
individual consumer, because the broker's consumer listing carries no per-consumer
acknowledgement counter. Attribution to a named consumer SHALL come only from the
broker's own slow-consumer notification.

#### Scenario: The limit is disclosed, not implied

- **WHEN** an operator views a slow-consumer firing derived by Rahdar
- **THEN** the firing names the queue and node and states that per-consumer attribution
  is available only from the broker's own detection

### Requirement: Slow-consumer detection ships a rule template, not a seeded rule

The system SHALL NOT seed a slow-consumer rule on cluster registration. It SHALL instead
offer a prefilled slow-consumer rule template in the rule-creation interface, because a
meaningful threshold is workload-specific and any seeded value would be wrong for most
deployments.

#### Scenario: No rule is created without an operator

- **WHEN** a cluster is registered
- **THEN** no slow-consumer rule exists for it

#### Scenario: The template is offered

- **WHEN** an operator creates a new rule
- **THEN** a prefilled slow-consumer template is offered as a starting point

### Requirement: Clock skew is an alertable cluster-state condition

The system SHALL provide a cluster-state alert condition for a clock that
disagrees with its own beyond the configured tolerance, evaluated from the same
polled state as the other cluster-state conditions and never from a metric sample.

Its subjects SHALL be the individual nodes, plus one distinct subject representing
the system's own host for the case where every measured node disagrees the same
way, so that each is tracked and silenced independently.

Only nodes whose clocks have actually been measured SHALL be evaluated: an
unmeasured node SHALL NOT contribute to resolving the condition.

A rule for this condition SHALL be created for each cluster at warning severity,
and SHALL be an ordinary rule — editable, silenceable and deletable like any other.

#### Scenario: A skewed node fires

- **WHEN** one node's clock disagrees beyond tolerance for longer than the rule's
  debounce
- **THEN** the alert fires with that node as the subject

#### Scenario: The host itself is named

- **WHEN** every measured node disagrees in the same direction beyond tolerance
- **THEN** the alert fires against the subject representing the system's own host,
  not against each node

#### Scenario: An unmeasured node cannot resolve the alert

- **WHEN** a node's clock has never been measured
- **THEN** it is not evaluated, and its absence does not resolve a firing alert

### Requirement: Configuration drift is an alertable cluster-state condition

The system SHALL provide a cluster-state alert condition that is active for a node whose
last configuration evaluation found drift, evaluated from the recorded per-node state and
never from a metric sample. Its subjects SHALL be the individual nodes, so that each is
tracked and silenced independently. A node that is not evaluated or unreachable SHALL
NOT contribute to resolving the condition.

The condition SHALL ship as a rule template, not a seeded rule, because a cluster without
a declaration has nothing to drift from.

#### Scenario: A drifted node fires

- **WHEN** an evaluation records drift on one node for longer than the rule's debounce
- **THEN** the alert fires with that node as the subject

#### Scenario: An unevaluated node cannot resolve the alert

- **WHEN** a node has not been evaluated since it last drifted
- **THEN** its absence from the latest evaluation does not resolve a firing alert

### Requirement: A consumer-health rule evaluates the shared verdict, not a raw metric

The system SHALL offer a rule kind whose condition is a queue's consumer-health verdict,
evaluated from the same classification the console and the agent surface report, compared
against a severity the operator selects. It SHALL NOT reimplement the classification.

#### Scenario: A rule fires on a severity, not a number an operator must decode

- **WHEN** an operator creates a consumer-health rule
- **THEN** the condition is expressed as a named verdict severity, not as a bare numeric
  threshold

#### Scenario: A queue reaching the selected severity fires

- **WHEN** a queue's verdict reaches or exceeds the severity a rule selects, for the
  rule's debounce duration
- **THEN** that rule fires for that queue, carrying the verdict and its evidence

#### Scenario: A recovering queue resolves

- **WHEN** a firing queue's verdict falls below the rule's selected severity
- **THEN** the firing resolves through the existing resolution path

#### Scenario: An uncomputable verdict never fires and never resolves

- **WHEN** a queue's verdict is `INSUFFICIENT_DATA`
- **THEN** it is excluded from the rule's evaluation entirely, so it neither fires nor
  resolves a firing that is still true

#### Scenario: A paused queue does not page

- **WHEN** a queue is paused and therefore holds a backlog by design
- **THEN** it does not fire a consumer-health rule

### Requirement: Email, Microsoft Teams and PagerDuty are notification channel kinds

In addition to Slack and signed webhooks, the system SHALL support three more
notification channel kinds, each delivering through the same durable, retried,
once-per-tick delivery path:

- an **email** channel, sent over SMTP;
- a **Microsoft Teams** channel, sent to a Teams webhook URL;
- a **PagerDuty** channel, sent to a PagerDuty Events API v2 endpoint.

A channel's secret material SHALL be encrypted at rest and never returned: the SMTP
password, the Teams webhook URL, and the PagerDuty routing key.

#### Scenario: A PagerDuty channel is created

- **WHEN** an operator creates a PagerDuty channel with a routing key
- **THEN** the channel is stored, reading it back shows that a secret is configured
  without revealing it, and rules can be bound to it

#### Scenario: An unknown kind is rejected

- **WHEN** a channel is submitted with a kind that is not one of the five supported kinds
- **THEN** the request is rejected, naming the kind

### Requirement: A channel's configuration is validated per kind before it is stored

The system SHALL validate a channel's configuration for its kind on create, update
and test, and SHALL reject an invalid one with a message that names the field at
fault:

- A URL SHALL use `http` or `https` and name a host.
- An email channel SHALL name an SMTP host, a port between 1 and 65535, a transport
  security mode (STARTTLS, TLS or none), a valid sender address, and at least one
  valid recipient address.
- A PagerDuty channel that uses the default endpoint SHALL have a 32-character
  routing key.

#### Scenario: An email channel without recipients is rejected

- **WHEN** an operator saves an email channel with no recipient address
- **THEN** the request is rejected and the message names the recipients field

#### Scenario: A malformed recipient is rejected

- **WHEN** an email channel lists a recipient that is not a valid address
- **THEN** the request is rejected, naming that address

### Requirement: An email channel sends over SMTP without downgrading its security

An email channel SHALL send one message per delivery. The message SHALL have a
subject naming the severity, the rule and the cluster, and a body listing every
transition in the delivery, as both HTML and plain text. When the channel requires
STARTTLS, a server that does not offer it SHALL cause the delivery to fail rather
than be sent unencrypted.

- An authentication failure SHALL be permanent.
- A delivery whose every recipient is rejected SHALL be permanent.
- A connection or I/O failure SHALL be retried.

Values from a rule or a subject SHALL NOT be able to add or alter a message header.

#### Scenario: Wrong SMTP credentials are not retried

- **WHEN** the SMTP server rejects the channel's credentials
- **THEN** the delivery is marked permanently failed on the first attempt

#### Scenario: A rule name cannot inject a header

- **WHEN** a rule's name contains a line break followed by header text
- **THEN** the sent message's subject contains no line break and no extra header is
  added

### Requirement: A Teams channel delivers an Adaptive Card

A Teams channel SHALL deliver each notification as an Adaptive Card message. The card
SHALL state the severity in words, and SHALL name the cluster, the rule and each
transition. When a link to Rahdar is available, the card SHALL offer it as an action.
A response saying the webhook no longer exists, or is not authorised, SHALL be
permanent. A response asking the sender to slow down SHALL be honoured before the
next retry.

#### Scenario: A deleted Teams workflow is not retried

- **WHEN** the Teams webhook responds that it was not found
- **THEN** the delivery is marked permanently failed without retrying

### Requirement: A PagerDuty channel opens and resolves one incident per firing subject

A PagerDuty channel SHALL send one Events API v2 event per transition in a delivery:
a `trigger` for a subject that started firing, and a `resolve` for a subject that
resolved. Each event SHALL carry a deduplication key derived from the rule and the
subject, so a subject's resolve closes the incident its trigger opened, and resending
the same delivery opens no second incident. The event severity SHALL follow the rule's
severity. The endpoint SHALL default to PagerDuty's, and SHALL be configurable per
channel for another service region or a PagerDuty-compatible receiver.

- A response rejecting the event as malformed SHALL be permanent.
- A rate-limit response SHALL be retried no sooner than the receiver asks.

#### Scenario: A resolution closes the incident

- **WHEN** a subject that fired resolves
- **THEN** PagerDuty receives a `resolve` event with the same deduplication key as the
  `trigger` it received when the subject fired

#### Scenario: A retried delivery opens no duplicate incident

- **WHEN** a delivery carrying three firings fails on its third event and is retried
- **THEN** the retry resends all three with the same deduplication keys, and PagerDuty
  still holds one incident per subject

### Requirement: A notification can be read without opening Rahdar

Every notification, whatever its channel kind, SHALL name the cluster by name. It SHALL
name each subject in readable form: a node by its name, not an internal identifier,
and a queue by its name. It SHALL state the observed value where there is one, and
the time of each transition. When a public URL for Rahdar is configured, it SHALL
include a link to the cluster's alerts. The generic webhook payload SHALL carry these
as additional fields, and SHALL keep every field it carried before with an unchanged
meaning.

#### Scenario: A node subject is named

- **WHEN** a node-down rule fires for a node
- **THEN** the notification names the node, not the identifier Rahdar stores for it

#### Scenario: Existing webhook receivers keep working

- **WHEN** a webhook receiver written against the previous payload receives a delivery
- **THEN** the fields it reads are present, with the same names and meaning

### Requirement: A channel can be tested before it is saved, and the test reports its outcome

The system SHALL let an operator send a test notification using a configuration that
has not been saved yet. When editing an existing channel and leaving the secret
blank, the test SHALL use the stored secret. A test SHALL report whether the
notification was delivered and, if not, the receiver's or the transport's stated
cause and whether retrying could help. An unsuccessful test SHALL be reported as a
result, not as an error of the request. Every test SHALL be audited.

#### Scenario: A wrong URL is caught before saving

- **WHEN** an operator tests an unsaved webhook configuration whose receiver answers 404
- **THEN** the test reports not delivered, with the receiver's status, and nothing is
  saved

### Requirement: A channel shows its delivery health and log

Each channel SHALL show the outcome and time of its most recent delivery attempt, its
most recent error, the number of deliveries waiting, and how many failed and
succeeded in the last 24 hours. It SHALL also show the rules it is bound to by count.
An operator SHALL be able to open a channel's delivery log, newest first, where each
entry shows:

- the rule;
- a one-line summary;
- the state (waiting, sent or failed);
- the number of attempts;
- the last error;
- the creation, next-attempt and delivered times.

#### Scenario: A failing channel is visible without opening it

- **WHEN** a channel's last delivery failed permanently
- **THEN** the channel list shows that it failed, when, and why

### Requirement: A permanently failed delivery can be retried

The system SHALL let an operator return a permanently failed delivery to the queue.
It SHALL then be attempted again, with its attempt count reset. Retrying a delivery
that is not permanently failed SHALL be refused. A retry SHALL be audited.

#### Scenario: A delivery that failed while the receiver was down is retried

- **WHEN** a delivery was marked permanently failed and the operator retries it after
  fixing the channel
- **THEN** the dispatcher attempts it again on its next pass

### Requirement: Deleting a channel states what it is bound to

Deleting a notification channel SHALL be confirmed by typing the channel's name.
Before it can be confirmed, the operator SHALL be told how many rules are bound to the
channel and will stop delivering to it.

#### Scenario: Deleting a bound channel

- **WHEN** an operator starts deleting a channel bound to three rules
- **THEN** the confirmation states that three rules will stop delivering to it, and
  the delete is not armed until the channel's name is typed

### Requirement: Setup risk is an alertable cluster-state condition

The system SHALL provide a cluster-state alert condition, `SETUP_RISK`. It SHALL be
active for each open finding of the cluster setup review with critical or warning
severity that has not been accepted as a known risk, or whose acceptance has expired.
Each finding SHALL be its own subject, so that each is tracked independently. A
finding about a node the last review could not read SHALL remain in the evaluation,
so that an unreachable node cannot resolve it. A finding the review no longer
produces SHALL resolve. The condition SHALL ship as a rule template, not a seeded
rule.

#### Scenario: A new critical finding fires

- **WHEN** a review records a critical finding and a `SETUP_RISK` rule exists for the
  cluster
- **THEN** the rule fires for that finding once its debounce elapses

#### Scenario: Accepting a risk resolves its firing

- **WHEN** an operator accepts a firing finding as a known risk
- **THEN** the firing for that finding resolves on the next evaluation

#### Scenario: A fixed configuration resolves

- **WHEN** a later review no longer produces a finding
- **THEN** its firing resolves

### Requirement: An alert rule can be about the installation rather than a cluster
An alert rule SHALL be either cluster-scoped or installation-scoped. An installation-scoped rule SHALL
evaluate a condition of the Rahdar installation itself, such as storage quotas and storage health,
once per installation, and SHALL otherwise behave like any rule: debounce, history, channels, editing,
disabling. Rahdar SHALL seed one `STORAGE_QUOTA` and one `STORAGE_HEALTH` rule per installation,
enabled and bound to no channel.

#### Scenario: Seeded once
- **WHEN** Rahdar starts on a new installation, with or without registered clusters
- **THEN** exactly one `STORAGE_QUOTA` and one `STORAGE_HEALTH` installation rule exist

#### Scenario: Fires once, not per cluster
- **WHEN** a store crosses its quota warning on an installation with three clusters
- **THEN** one alert fires, shown as belonging to the installation

#### Scenario: Can be silenced
- **WHEN** an operator disables the seeded `STORAGE_HEALTH` rule
- **THEN** it no longer fires until re-enabled

### Requirement: Plugins SHALL send notices through the configured channels

A plugin SHALL be able to list the notification channels by name and kind, without their configuration or secrets, and send a notice to one of them. The notice SHALL be delivered with the same retry, signature and delivery history as alerts. A notice's link SHALL be built by Rahdar from its public address and a path within Rahdar. Rahdar SHALL limit the rate and size of notices per plugin.

#### Scenario: Webhook notice

- **WHEN** a plugin sends a notice to a webhook channel
- **THEN** the webhook receives a signed notice payload with a link into Rahdar

#### Scenario: Channel secrets

- **WHEN** a plugin lists the channels
- **THEN** it receives no channel configuration or secret

### Requirement: Cluster degraded reads the system's own health

The cluster-degraded condition SHALL read the health the cluster's system judges, the same verdict the cluster
screen shows.

#### Scenario: Offline partitions fire cluster degraded

- **WHEN** a Kafka cluster's health is degraded or critical
- **THEN** its cluster-degraded rule becomes active

### Requirement: An Artemis-only rule is refused on another system

A rule on a condition or metric only Artemis reports SHALL be refused on a Kafka or RabbitMQ cluster with
`unsupported-on-system`, naming the system and what to watch instead. The rule form SHALL list those choices
disabled with the reason, and SHALL offer them while the system is unknown.

#### Scenario: A split-brain rule on a Kafka cluster

- **WHEN** an operator saves a split-brain rule on a Kafka cluster
- **THEN** the request is refused with a 409 that names Kafka and cluster-degraded
