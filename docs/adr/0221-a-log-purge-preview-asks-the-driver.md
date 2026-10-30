# ADR-0221: A log purge preview asks the driver

- **Status**: accepted
- **Date**: 2026-10-30
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0093](0093-bulk-operations-are-persisted-runs-over-single-queue-commands.md) estimates a bulk preview from the aggregated queue list and never does a dry run per queue. That list's depth is the lag from the last scrape. For a queue, the scrape is the depth a purge would destroy. For a Kafka group it is not: a purge moves the group to the last offset it can read, and the scraped lag also counts records an open transaction still holds. Confirming the scraped number tells the operator a move the broker will not make.

The same live preview already exists for one group (`BrokerMessages.previewLogPurge`). The row action opens the bulk dialog, so both paths have to use it.

## Decision

A purge of a partitioned log asks the driver once per selected group at preview time, and the number on the confirmation is that answer. A count the driver does not report is unavailable, never zero, and the purge is not offered. A count of zero means the group is already at the last record it can read, and the purge is not offered. A group with members is still refused from the snapshot, with no broker call. Queue pause, resume, delete and purge stay on the aggregated list.

This supersedes decision 2 of ADR-0093 for a purge of a partitioned log only. The rest of that ADR stands, including one frozen plan, sequential execution through the single-group command, and no audit row for the preview itself.

## Consequences

- The confirmation names the records the move will skip, and says that records an open transaction still holds stay.
- A preview of many groups makes one driver call each. The bulk subscription cap already bounds how many.
- A group that starts rebalancing after the preview is still refused when the run reaches it.

## Alternatives considered

- **Keep the scraped lag on the button.** Fast, and wrong whenever an open transaction holds the end of the log.
- **A batched preview in the broker SDK.** One call would be kinder at the cap, and this change does not need a new SDK shape to be honest.
