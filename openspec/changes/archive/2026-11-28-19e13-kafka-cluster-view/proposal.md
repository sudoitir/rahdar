## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Rahdar and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
The brokers screen lists each broker and the controller quorum, and it counts under-replicated and offline partitions. An operator still cannot read one cluster as a graph of topics, partitions and where replicas sit, with those facts in words. Flow shows which groups consume which topics. It does not draw the cluster, and it does not trace a message.

## What Changes
- A read-only graph of one Kafka cluster, read the way the Artemis topology is read
- Brokers and the controller or quorum, from what 19e4 shipped
- Topics and partitions, replica placement, and under-replicated and offline partitions, from 19e7, with the same facts in words
- It does not trace a message and it is not Flow
- Nothing on it writes
- A part the cluster cannot show stays visible and disabled, with the system's reason
- MCP tools that read it use the same permission as the screen

## Capabilities
### New Capabilities
- `kafka-cluster-view`: A read-only graph of one Kafka cluster and the same facts in words.
### Modified Capabilities
- `system-kafka`: "Brokers and the controller quorum are shown" becomes "The cluster view shows brokers and the controller quorum".

## Out of scope
- Tracing a message
- Flow
- Any write, including replica reassignment

## Depends on
- 19e4-kafka (brokers and the controller or quorum)
- 19e7-kafka-partition-replication (replica placement and under-replication)

## Execution
**Subagent-driven + reviewer**: the graph and the same facts in words are one view, and the reviewer checks that nothing on it writes.

## Impact
Kafka cluster view, admin UI, MCP.
