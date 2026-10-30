# ADR-0093: Bulk operations are persisted runs over the single-queue commands

- **Status**: accepted; partly superseded by [ADR-0152](0152-replicas-coordinate-through-postgres.md) and [ADR-0221](0221-a-log-purge-preview-asks-the-driver.md)
- **Date**: 2026-09-21
- **Deciders**: Artemis Studio maintainers

> **Partly superseded.** [ADR-0152](0152-replicas-coordinate-through-postgres.md) replaces in-process stops and startup recovery: stops reach the executing replica and recovery interrupts only runs of a gone replica. [ADR-0221](0221-a-log-purge-preview-asks-the-driver.md) replaces decision 2 for a purge of a partitioned log: that preview asks the driver once per group.

## Context

Operators need to pause, resume, purge or delete many queues at once (Roadmap B). Each
of those already exists for one queue. It runs through `BrokerCommands`, which provides:

- per-node fan-out;
- preflight;
- the bulk safety cap (ADR-0022);
- an audit row committed before the broker call (ADR-0078);
- the per-node rate limiter.

A bulk operation that reimplemented any of that would be a second, weaker copy of the
safety model.

A 200-queue delete takes minutes. An HTTP request cannot hold it honestly: no progress, no
stop, and a lost result on reload. `kernel.jobs` schedules recurring work only, and there
is no user-submitted job facility. The audit trail has no way to say "these forty rows are
one operator action".

## Decision

1. **A bulk run is one operation over a frozen set of queues**, resolved and hashed at
   preview time. Execute must echo the hash, and the preview expires after 10 minutes.
   Nothing added after the preview joins the run.
2. **The preview estimates from the aggregated queue list**: one batched read per node,
   never a dry run per queue (non-negotiable #1). An unanswered node is "unknown", never
   zero. The authoritative preflight still runs per queue at execution.
3. **Execution is sequential, and each queue goes through the existing single-queue
   command**, unchanged. Safety, audit and rate limiting are inherited, not re-implemented.
4. **Runs are persisted** (`bulk_run`, `bulk_run_item`, owned by `feature/bulk`).
   - They execute on a virtual thread that carries the initiating operator's identity, so
     grants are re-checked per queue.
   - A partial unique index allows one running run per cluster.
5. **The default failure policy is stop at the first failed or partial queue**, and
   continuing is opt-in. Stop is cooperative: the queue in flight finishes.
6. **A restart marks a running run `INTERRUPTED`** and its in-flight queue `UNKNOWN`.
   It is never resumed.
7. **Two caps.**
   - Queue count, `safety.bulk-queue-cap`, default 200, with no override. It bounds how
     long a run can be.
   - Estimated messages, the existing `safety.bulk-cap`, overridable. It bounds how much
     data one confirmation can destroy.
8. **Audit grouping is a nullable `audit_event.parent_id`**, bound through a
   `ScopedValue` (`AuditScope.PARENT`) that `AuditService.begin` reads. No signature of an
   existing service or of `BrokerCommands` changes.
9. **Bulk lives in its own module**, depending on `queues`, `messages` and `resources`. The
   queues screen shows bulk actions through a kernel slot (`queues.selection`), so queues
   never depends on bulk (ADR-0069, ADR-0070).

## Consequences

- Every bulk safety property is the single-queue property, proven by the single-queue
  tests. A fix to a single-queue command fixes bulk too.
- Sequential execution is slower than parallel. That is deliberate: Studio must never be
  why a broker falls over.
- The stop flag and the runner are in-process, so Studio assumes one instance per
  deployment, as every supported deployment is today. The unique index keeps a second
  instance from starting a concurrent run on a cluster. It would still need a DB-polled
  stop flag, and that is the change to make if Studio ever runs replicated.
- `kernel.jobs` gains no async-job abstraction. When a second feature needs persisted,
  observable, stoppable work, extract it from `feature/bulk` then.
- Bulk move/retry and bulk over MCP are not covered. Each needs its own decision.
