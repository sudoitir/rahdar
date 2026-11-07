# Rule: inline, subagent or workflow

Pick the smallest harness that does the job. Most tasks need neither agents nor workflows.

| Harness | Use it when | Not when |
| --- | --- | --- |
| **Inline** (default) | The task fits one context: a fix, a feature, a doc, a rule | — |
| **Subagent** (`.claude/agents/`) | A side job would flood the main context (wide search, a long check run), or a step needs a different model or effort, or an independent review | The step is quick, or you already know the file |
| **Workflow** | The user opts in, and the task is large, parallel or adversarial: an audit, a migration, a multi-lens review | Anything a subagent or two covers. Workflows cost many times the tokens |

## Models and effort

| Role | Agent | Model | Effort |
| --- | --- | --- | --- |
| Plan, design, the hardest reasoning | `planner` | Opus | medium |
| Review, adversarial check | `reviewer` | Opus | low |
| Implement a settled task | `implementer` | Sonnet | medium |
| Run checks, report pass/fail | `verifier` | Sonnet | low |
| Exploration, search, reading many files, other low-end jobs | `explorer` (in `~/.claude/agents`) | Haiku | n/a |

- The session runs on Opus at medium. Every subagent takes its model from the table, never from
  `CLAUDE_CODE_SUBAGENT_MODEL`: that variable overrides every agent's `model` frontmatter, so never set it.
  Exploration goes to `explorer` (Haiku), never to the built-in `Explore`.
  `ANTHROPIC_DEFAULT_OPUS_MODEL` / `ANTHROPIC_DEFAULT_SONNET_MODEL` pin the aliases to 5.5.
- **Never `high`, `xhigh` or `max`** for any agent: Sonnet runs at low or medium, Opus at low or medium. `maxEffortLevel: high` in `settings.json` is only the hard ceiling.
- Never set `CLAUDE_CODE_EFFORT_LEVEL`: it overrides every agent's `effort` frontmatter.
- In a workflow, give every `agent()` call its role: `agentType: 'planner'` (or the
  others), or `{model, effort}` from the table. Never leave a stage to inherit by accident.
- Raise effort only when a check failed at the lower level, never pre-emptively. Lower
  thinking with effort, not with "think less" in the prompt.
- Hand an agent a complete task with a finish line (the one test class or lint the change
  touches) and accept its result only with the evidence it returns. CI runs `just verify`.

## Token spend

Best quality per token, not maximum assurance:

- One verification pass per change, at the end. No per-task re-runs, no second reviewer, no
  repeated passes over work that already passed. CI is the backstop where a repo has it.
- While implementing, run only the narrow check the change touches (one test class, one lint).
- Subagents do not nest (`CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH=1`).
- Delegate only when it saves main-context tokens or needs another model. A subagent re-reads
  context it does not share, so a small task is cheaper inline.
- Default to the lower effort in the table; a failed check is the only reason to go up.

## OpenSpec features

Every feature goes through OpenSpec (`00-workflow.md`), from proposal to merged PR in one go.

| Phase | Who |
| --- | --- |
| `/opsx:explore`, `/opsx:propose`, `design.md` | inline on Opus, or `planner` for a hard design question |
| `/opsx:apply` | inline for small tasks; `implementer` per task when tasks are independent |
| End of apply | the narrow check the change touches (one test class, one lint), inline or via `verifier` when its output is long. CI runs the full suite |
| Before the PR | `reviewer` only for a risky diff: security, data loss, a public API, a migration |

## Skills: when to load which

| Situation | Skill |
| --- | --- |
| Any feature | `/opsx:propose` → `/opsx:apply` → `/opsx:archive`, with `openspec-git-discipline` |
| Thinking before a proposal | `/opsx:explore`, `grill-me` to stress-test it |
| A significant decision | `architectural-decision-records` |
| Specs, terms, wording | `glossary`; Gherkin in a spec: `gherkin-authoring` |
| Executable acceptance tests | `spec-as-source`, `acceptance-test-authoring` |
| Explaining architecture | `c4-diagrams` |
| Frontend code (with `20-frontend.md`) | `frontend-development-guide`; Mantine forms, comboboxes, custom components: `mantine-form`, `mantine-combobox`, `mantine-custom-components` |
| A new screen's visual direction | `ui-ux-pro-max` |
| A navigation row, a section list, the shell, a way back, scroll or flicker on a section change | `rahdar-navigation-and-layout` |
| A form with paired fields, a destructive confirmation, a gated operation or the approver quorum | `rahdar-safe-interactions` |
