# bulk-operations Specification

## Purpose

A bulk run applies one queue operation — pause, resume, purge, or delete — to a set of
queues chosen by an operator. It is previewed, confirmed, executed one queue at a time
through the same command a single-queue operation uses, observable while it runs,
stoppable, and recorded for later.

## Requirements

### Requirement: A bulk run is previewed over a frozen set of subscriptions before it can execute

The system SHALL let an authorized operator preview a bulk run of one operation, pause,
resume, purge or delete, on a cluster. The set of subscriptions SHALL be named in one of two ways:
- an explicit list of subscription names; or
- the subscriptions matching a filter, resolved by the server at preview time.

The preview SHALL freeze the resolved set. Executing the run SHALL act on exactly that set.
A subscription created or matched after the preview SHALL NOT be added to the run.

The preview SHALL report, for the run and for each subscription:
- the nodes it lives on;
- its current message count and consumer count, or that the figure is unavailable;
- whether the operation will be refused for that subscription, and why;
- any warning the single-subscription dry run would give.

The run's estimate SHALL be the sum over the subscriptions whose figure is known. When any
subscription's figure is unknown, the preview SHALL say so, and it SHALL NOT present the partial
sum as the whole.

The preview SHALL be computed from at most one batched read per node. It SHALL NOT
issue a broker call per subscription.

A preview SHALL expire ten minutes after it was made. An expired preview SHALL NOT
execute.

#### Scenario: The set is frozen at preview

- **WHEN** an operator previews a purge of every subscription matching `orders.*` and a new
  subscription `orders.late` is created before they execute it
- **THEN** the run purges only the subscriptions listed in the preview, and `orders.late` is
  untouched

#### Scenario: An unknown estimate is stated

- **WHEN** one node does not answer while a delete of 12 subscriptions is previewed
- **THEN** the preview reports the subscriptions on that node as having an unavailable message
  count, and states that the total is incomplete rather than presenting the known sum as
  the total

#### Scenario: A refused subscription is named before execution

- **WHEN** a delete preview includes a subscription that still has consumers attached
- **THEN** that subscription is listed as refused with the reason, and it is not counted in the
  run's blast radius

#### Scenario: An expired preview does not execute

- **WHEN** an operator executes a preview made more than ten minutes earlier
- **THEN** the request is refused, stating that the preview expired, and nothing is done

### Requirement: A bulk run is capped by subscription count and by estimated messages

A bulk run SHALL NOT contain more subscriptions than the configured subscription cap
(`safety.bulk-subscription-cap`, default 200). A preview that would exceed it SHALL be refused,
and the refusal SHALL state the cap and how many subscriptions matched. There is no override
for this cap.

A purge or delete run whose total estimated message count exceeds the existing bulk
safety cap SHALL NOT execute unless the operator explicitly overrides the cap on
execution. The per-subscription commands the run issues SHALL carry that override.

#### Scenario: Too many subscriptions is refused with the numbers

- **WHEN** an operator previews a pause of 340 subscriptions with the subscription cap at 200
- **THEN** the preview is refused, stating that 340 subscriptions matched and the cap is 200

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
single-subscription operation it applies. The permission SHALL be checked again before each
subscription is acted on. A subscription whose permission is no longer held SHALL fail with that
reason.

#### Scenario: A second concurrent run is refused

- **WHEN** a delete run is executing on a cluster and an operator executes a pause run on
  the same cluster
- **THEN** the pause run is refused, naming the run in progress

#### Scenario: A permission withdrawn mid-run fails the remaining subscriptions honestly

- **WHEN** an operator's permission to delete subscriptions is withdrawn while their delete run is
  executing
- **THEN** each subscription not yet acted on fails, stating that the permission is not held

### Requirement: A bulk run acts one subscription at a time through the single-subscription command

The system SHALL execute a bulk run's subscriptions sequentially, in the order the preview
listed them. It SHALL act on each subscription through the same command the single-subscription
operation uses, so that each subscription gets:
- the same preflight checks;
- the same per-node fan-out and per-node outcome;
- the same audit event; and
- the same per-node rate limiting.

Each subscription's outcome SHALL be recorded as one of: succeeded, partially succeeded (some
nodes failed), failed, skipped, cancelled, or unknown.

By default the run SHALL stop at the first subscription that fails or partially succeeds, and
the subscriptions after it SHALL be recorded as skipped. When the operator chose to continue
past failures, the run SHALL act on every subscription.

The run SHALL finish as one of:
- **succeeded**: every subscription succeeded;
- **partial**: some subscriptions succeeded and some did not;
- **failed**: no subscription succeeded;
- **stopped**: the operator stopped it;
- **interrupted**: Rahdar stopped while it ran.

#### Scenario: The run stops at the first failure by default

- **WHEN** the third of ten subscriptions fails to delete in a run that does not continue past
  failures
- **THEN** subscriptions one and two are succeeded, subscription three is failed with its reason, subscriptions
  four to ten are skipped, and the run finishes as partial

#### Scenario: Continuing past failures acts on every subscription

- **WHEN** the same run was executed with "continue past failures"
- **THEN** all ten subscriptions are acted on, and each is reported with its own outcome

#### Scenario: A partially succeeded subscription carries its per-node detail

- **WHEN** a subscription is deleted on two of its three nodes and the third refuses
- **THEN** the subscription is reported as partially succeeded, with the outcome and error of each
  node

### Requirement: A running bulk run is observable and stoppable

The system SHALL publish a bulk run's progress as it executes: its status, and how many
subscriptions have succeeded, failed, been skipped, and remain. A run and its per-subscription outcomes
SHALL be readable at any time while it runs and after it finishes.

An operator holding the run's permission SHALL be able to stop a running run. Stopping
SHALL let the subscription in flight finish, and SHALL mark every subscription not yet started as
cancelled. The run SHALL finish as stopped.

#### Scenario: Stopping lets the current subscription finish

- **WHEN** an operator stops a delete run while its fifth subscription is being deleted
- **THEN** the fifth subscription's deletion completes and is reported, the remaining subscriptions are
  cancelled, and the run finishes as stopped

### Requirement: A bulk run interrupted by a restart is never resumed on its own

When Rahdar starts and finds a bulk run still recorded as running, it SHALL mark the run
as interrupted. It SHALL mark the subscription that was in flight as unknown, stating that the
broker must be checked for that subscription. It SHALL mark the subscriptions not yet started as
cancelled. It SHALL record the run's audit event as failed.

Rahdar SHALL NOT resume an interrupted run.

#### Scenario: A restart mid-run leaves an honest record

- **WHEN** Rahdar is restarted while the fourth of eight subscriptions is being purged
- **THEN** after restart the run reads as interrupted, subscriptions one to three keep their
  outcomes, subscription four is unknown, and subscriptions five to eight are cancelled

### Requirement: A bulk run is audited as a whole and in parts

Executing a bulk run SHALL create one audit event for the run. The event SHALL be
committed before any subscription is acted on, and SHALL record:
- the operation;
- the number of subscriptions;
- the selection (the explicit names or the filter);
- the estimate;
- whether the cap was overridden;
- whether the run continues past failures.

Each subscription's audit event SHALL name the run's event as its parent. The run's event
SHALL be updated with the run's outcome when the run finishes, and it SHALL be a
failure unless every subscription succeeded.

A preview SHALL NOT create an audit event.

#### Scenario: The trail shows the whole and the parts

- **WHEN** a pause run over five subscriptions finishes
- **THEN** the audit trail holds one event for the run and five subscription events whose parent
  is that run event
