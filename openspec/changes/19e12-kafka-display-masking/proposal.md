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
Content policy already masks sensitive values on the way out of Rahdar and in what Rahdar stores. A Kafka record that is browsed, queried, tailed, exported or handed to an assistant can still show a field the operator should see masked. Masking that rewrites the stored record, or that searches and erases, is a different job. This change masks at display time, so an assistant never sees raw personal data the operator would also have masked, and the stored record stays as it is.

## What Changes
- Mask sensitive fields at display time in browse, SQL results, live tail, export and MCP tool output
- An assistant never sees raw personal data the operator would also have masked
- Stored records stay as they are
- This is not search or erasure
- MCP tools apply the same masking as the UI

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `data-governance`: Display-time masking on browse, SQL results, live tail, export and MCP, without changing the stored record and without search or erasure.

## Out of scope
- Search and erasure
- Rewriting a record stored on the cluster
- New data classes; sensitive fields are the ones the content policy already classifies

## Depends on
- 19e9-kafka-produce-and-inspect (browse, live tail and export of the browsed page)

## Execution
**Subagent-driven + reviewer**: each surface can be masked on its own, and the reviewer checks that stored records are untouched and that an assistant sees the same masking as the UI.

## Impact
Browse, SQL results, live tail, export, MCP, content policy.
