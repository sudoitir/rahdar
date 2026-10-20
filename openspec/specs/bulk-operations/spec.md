# bulk-operations Specification

## Purpose

A bulk run applies one queue operation — pause, resume, purge, or delete — to a set of
queues chosen by an operator. It is previewed, confirmed, executed one queue at a time
through the same command a single-queue operation uses, observable while it runs,
stoppable, and recorded for later.

## Requirements

### Requirement: A bulk run is previewed over a frozen set of queues before it can execute

The system SHALL let an authorized operator preview a bulk run of one operation, pause,
resume, purge or delete, on a cluster. The set of queues SHALL be named in one of two ways:
- an explicit list of queue names; or
- the queues matching a filter, resolved by the server at preview time.

The preview SHALL freeze the resolved set. Executing the run SHALL act on exactly that set.
A queue created or matched after the preview SHALL NOT be added to the run.

The preview SHALL report, for the run and for each queue:
- the nodes it lives on;
- its current message count and consumer count, or that the figure is unavailable;
- whether the operation will be refused for that queue, and why;
- any warning the single-queue dry run would give.

The run's estimate SHALL be the sum over the queues whose figure is known. When any
queue's figure is unknown, the preview SHALL say so, and it SHALL NOT present the partial
sum as the whole.

The preview SHALL be computed from at most one batched read per node. It SHALL NOT
issue a broker call per queue.

A preview SHALL expire ten minutes after it was made. An expired preview SHALL NOT
execute.

#### Scenario: The set is frozen at preview

- **WHEN** an operator previews a purge of every queue matching `orders.*` and a new
  queue `orders.late` is created before they execute it
- **THEN** the run purges only the queues listed in the preview, and `orders.late` is
  untouched

#### Scenario: An unknown estimate is stated

- **WHEN** one node does not answer while a delete of 12 queues is previewed
- **THEN** the preview reports the queues on that node as having an unavailable message
  count, and states that the total is incomplete rather than presenting the known sum as
  the total

#### Scenario: A refused queue is named before execution

- **WHEN** a delete preview includes a queue that still has consumers attached
- **THEN** that queue is listed as refused with the reason, and it is not counted in the
  run's blast radius

#### Scenario: An expired preview does not execute

- **WHEN** an operator executes a preview made more than ten minutes earlier
- **THEN** the request is refused, stating that the preview expired, and nothing is done

### Requirement: A bulk run is capped by queue count and by estimated messages

A bulk run SHALL NOT contain more queues than the configured queue cap
(`safety.bulk-subscription-cap`, default 200). A preview that would exceed it SHALL be refused,
and the refusal SHALL state the cap and how many queues matched. There is no override
for this cap.

A purge or delete run whose total estimated message count exceeds the existing bulk
safety cap SHALL NOT execute unless the operator explicitly overrides the cap on
execution. The per-queue commands the run issues SHALL carry that override.

#### Scenario: Too many queues is refused with the numbers

- **WHEN** an operator previews a pause of 340 queues with the queue cap at 200
- **THEN** the preview is refused, stating that 340 queues matched and the cap is 200

#### Scenario: Over the message cap needs an override

- **WHEN** a purge preview estimates 2,000,000 messages against a cap of 1,000,000, and the
  operator executes it without the override
- **THEN** the execution is refused as over the cap, and nothing is purged

### Requirement: Executing a bulk run requires the preview it confirms

Executing a bulk run SHALL name the preview being executed and its fingerprint. The
system SHALL refuse the execution if:
- the fingerprint does not match;
- the preview has expired; or
- the run has already started.

At most one bulk run SHALL execute at a time on a cluster. A second execution SHALL be
refused while one is running, and the refusal SHALL name the running run.

Executing, stopping and previewing a run SHALL each require the permission of the
single-queue operation it applies. The permission SHALL be checked again before each
queue is acted on. A queue whose permission is no longer held SHALL fail with that
reason.

#### Scenario: A second concurrent run is refused

- **WHEN** a delete run is executing on a cluster and an operator executes a pause run on
  the same cluster
- **THEN** the pause run is refused, naming the run in progress

#### Scenario: A permission withdrawn mid-run fails the remaining queues honestly

- **WHEN** an operator's permission to delete queues is withdrawn while their delete run is
  executing
- **THEN** each queue not yet acted on fails, stating that the permission is not held

### Requirement: A bulk run acts one queue at a time through the single-queue command

The system SHALL execute a bulk run's queues sequentially, in the order the preview
listed them. It SHALL act on each queue through the same command the single-queue
operation uses, so that each queue gets:
- the same preflight checks;
- the same per-node fan-out and per-node outcome;
- the same audit event; and
- the same per-node rate limiting.

Each queue's outcome SHALL be recorded as one of: succeeded, partially succeeded (some
nodes failed), failed, skipped, cancelled, or unknown.

By default the run SHALL stop at the first queue that fails or partially succeeds, and
the queues after it SHALL be recorded as skipped. When the operator chose to continue
past failures, the run SHALL act on every queue.

The run SHALL finish as one of:
- **succeeded**: every queue succeeded;
- **partial**: some queues succeeded and some did not;
- **failed**: no queue succeeded;
- **stopped**: the operator stopped it;
- **interrupted**: Rahdar stopped while it ran.

#### Scenario: The run stops at the first failure by default

- **WHEN** the third of ten queues fails to delete in a run that does not continue past
  failures
- **THEN** queues one and two are succeeded, queue three is failed with its reason, queues
  four to ten are skipped, and the run finishes as partial

#### Scenario: Continuing past failures acts on every queue

- **WHEN** the same run was executed with "continue past failures"
- **THEN** all ten queues are acted on, and each is reported with its own outcome

#### Scenario: A partially succeeded queue carries its per-node detail

- **WHEN** a queue is deleted on two of its three nodes and the third refuses
- **THEN** the queue is reported as partially succeeded, with the outcome and error of each
  node

### Requirement: A running bulk run is observable and stoppable

The system SHALL publish a bulk run's progress as it executes: its status, and how many
queues have succeeded, failed, been skipped, and remain. A run and its per-queue outcomes
SHALL be readable at any time while it runs and after it finishes.

An operator holding the run's permission SHALL be able to stop a running run. Stopping
SHALL let the queue in flight finish, and SHALL mark every queue not yet started as
cancelled. The run SHALL finish as stopped.

#### Scenario: Stopping lets the current queue finish

- **WHEN** an operator stops a delete run while its fifth queue is being deleted
- **THEN** the fifth queue's deletion completes and is reported, the remaining queues are
  cancelled, and the run finishes as stopped

### Requirement: A bulk run interrupted by a restart is never resumed on its own

When Rahdar starts and finds a bulk run still recorded as running, it SHALL mark the run
as interrupted. It SHALL mark the queue that was in flight as unknown, stating that the
broker must be checked for that queue. It SHALL mark the queues not yet started as
cancelled. It SHALL record the run's audit event as failed.

Rahdar SHALL NOT resume an interrupted run.

#### Scenario: A restart mid-run leaves an honest record

- **WHEN** Rahdar is restarted while the fourth of eight queues is being purged
- **THEN** after restart the run reads as interrupted, queues one to three keep their
  outcomes, queue four is unknown, and queues five to eight are cancelled

### Requirement: A bulk run is audited as a whole and in parts

Executing a bulk run SHALL create one audit event for the run. The event SHALL be
committed before any queue is acted on, and SHALL record:
- the operation;
- the number of queues;
- the selection (the explicit names or the filter);
- the estimate;
- whether the cap was overridden;
- whether the run continues past failures.

Each queue's audit event SHALL name the run's event as its parent. The run's event
SHALL be updated with the run's outcome when the run finishes, and it SHALL be a
failure unless every queue succeeded.

A preview SHALL NOT create an audit event.

#### Scenario: The trail shows the whole and the parts

- **WHEN** a pause run over five queues finishes
- **THEN** the audit trail holds one event for the run and five queue events whose parent
  is that run event
