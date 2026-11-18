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
A registered Kafka cluster has no screen for ksqlDB. Operators who have a ksqlDB endpoint leave the console to query and to run a statement, and a statement that writes needs the same preview and audit as any other write. A cluster with no endpoint should still show the screen, disabled, with the reason.

## What Changes
- A ksqlDB query and execute screen, only when that cluster has a ksqlDB endpoint
- Visible and disabled, with the system's reason, when it does not
- Executing a statement that writes gets a preview that changes nothing, a stale-preview refusal, press-and-hold confirm, the approval gate where it is on, and an audit event with before and after
- Execute is its own permission, so a role can stay read-only
- MCP tools use the same permissions, previews and audit trail

## Capabilities
### New Capabilities
- `kafka-ksqldb`: Query and execute against a cluster's ksqlDB endpoint, with a statement that writes held and audited.
### Modified Capabilities
- none

## Out of scope
- Hosting or installing ksqlDB
- The cluster graph
- A message's path

## Depends on
- 19e4-kafka (a registered Kafka cluster)

## Execution
**Inline**: one screen, one permission and one held write.

## Impact
ksqlDB screen, permissions, the approval gate, audit, MCP, admin UI.
