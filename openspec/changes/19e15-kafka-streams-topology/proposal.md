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
Nothing in the console shows a Kafka Streams topology. The cluster graph is brokers, topics and placement. A message's path is a different view. When an application reports a topology, an operator should read its sub-topologies, processors, state stores and the topics they use, and should not be able to write from that screen. When none is reported, the screen stays visible and disabled, with the reason.

## What Changes
- A read-only Streams topology when one is reported: sub-topologies, processors, state stores and the topics they use
- It is not the cluster graph and not a message's path
- Visible and disabled, with the system's reason, when no topology is reported
- Nothing on it writes
- MCP tools that read it use the same permission as the screen

## Capabilities
### New Capabilities
- `kafka-streams-topology`: A read-only Streams topology of sub-topologies, processors, state stores and the topics they use.
### Modified Capabilities
- none

## Out of scope
- The cluster graph
- A message's path
- Starting, stopping or reconfiguring a Streams application

## Depends on
- 19e4-kafka (a registered Kafka cluster)

## Execution
**Inline**: one read-only screen.

## Impact
Streams topology screen, MCP, admin UI.
