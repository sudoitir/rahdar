# ADR-0230: Kafka partition placement is a page, and a replica move is one preview

- **Status**: accepted
- **Date**: 2026-11-18
- **Deciders**: Mahdi Amirabdollahi

## Context

The brokers screen counts under-replicated and offline partitions. It does not say which partition, which replica is out of sync, or how many bytes sit on a broker. The driver is about to report that per replica, and to preview a preferred-leader election and a replica reassignment. Those two writes move data. They belong with the same preview, hold, approval gate and audit as a topic setting (ADR-0216, ADR-0225).

A replication throttle set for a move and then forgotten is how a cluster stays slow. The driver returns the previous throttle and which keys the move added. It does not store them. Something in this product has to put them back when the move finishes, including after a restart.

## Decision

- A topic's partitions are a Kafka page, linked from the destination row the way topic configuration is. The shared destinations list stays shared. Replication is a row in the observe group, beside Brokers. It lists only partitions that are under-replicated, have an offline replica, or are not led by their first replica. A healthy cluster says so and shows no table.
- Reads use `destination:read`. Election, reassignment and cancel use `destination:update`. No new permission name. This amends the operations list in ADR-0216. It does not add a permission.
- A preview changes nothing. Confirm is the existing press-and-hold. Apply, including an approval released later, checks the preview's etag again. The audit records the leaders or the replicas before and after. A stale etag changes nothing.
- The rack suggestion keeps every current replica and adds a broker for a rack not yet represented. It is labeled a suggestion. Only the form the operator confirms is sent.
- A throttle is required, as a positive number of bytes per second. The preview says whether that throttle will be removed when the move finishes or the previous value put back. `kafka_reassignment` stores the baseline in the same transaction as the apply audit. The scrape restores it when the broker no longer lists that reassignment, then deletes the row. A second scrape does not write. A move this product did not start is not restored.
- Unclean election is not a control, not a request field, and not an MCP argument. A control the operator cannot use stays visible and says why.
- MCP tools call the same operations. A read-only token is refused before a hold.

## Consequences

- An operator can read placement without a write, and cannot move a replica without a preview.
- One new table. A rollback that drops it leaves any throttle that table was going to restore. The operator clears that on the broker configuration screen.
- The cluster graph is a later change. This page is a table, not that graph.
- There is no plugin-API change, so `Contract.VERSION` stays.

## Alternatives considered

- **A new permission such as `partition:reassign`.** Rejected. A replica move is a change to a destination, which `destination:update` already names. A second name would split the same authority.
- **Leave the throttle until the operator clears it.** Rejected. A forgotten throttle is how a cluster stays slow after one move.
- **Clear every replication throttle when any move finishes.** Rejected. That deletes a throttle the operator set on purpose.
- **Put the partition table on the shared destinations screen.** Rejected. Placement is Kafka's. The shared list stays the list of destinations.
