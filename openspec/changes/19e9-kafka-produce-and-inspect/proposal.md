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
Browsing a Kafka topic already picks a partition and an offset or a time, and it never joins a group. The filter on that browse is disabled. An operator still cannot produce a record from the console, cannot see a record's headers on the browse, cannot follow a topic or a partition live, and cannot export the page they are reading. Sending a message today addresses a queue. The SQL console remains the query product.

## What Changes
- Produce a record from the console with a key, a value, headers and a chosen serde
- Show headers on browse
- Live-tail a topic or a partition without joining a group
- Filter that browse and tail with an expression; the disabled Kafka browse filter is the behaviour this replaces
- Export the browsed page
- The SQL console stays the query product
- Producing a record, including onto a topic that is not a dev topic, gets a preview that changes nothing, a stale-preview refusal, press-and-hold confirm, the approval gate where it is on, and an audit event with before and after
- Produce is its own permission, so a role can stay read-only, and it stays visible and disabled when the role or the topic cannot accept it, with the system's reason
- MCP tools use the same permissions, previews and audit trail

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `message-operations`: Produce a Kafka record, show its headers, live-tail and filter a topic or a partition without a group, and export the browsed page.
- `mcp-server`: Tools for that produce and inspect, with the same permissions, previews and audit trail.

## Out of scope
- The SQL console as a query product
- Joining a consumer group, or moving a group's offsets, in order to tail
- Registering schemas or changing a subject
- Search or erasure of stored records

## Depends on
- A chosen serde that decodes keys and values, so browse and produce show the record

## Execution
**Subagent-driven + reviewer**: produce, browse and tail, and the MCP tools are separable, and the reviewer checks the preview, the permission and the audit.

## Impact
Message browse and produce, permissions, the approval gate, audit, MCP, admin UI.
