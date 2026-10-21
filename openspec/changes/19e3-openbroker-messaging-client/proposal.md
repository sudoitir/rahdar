## How to run this change
This change is designed: `design.md` holds the decisions, and `tasks.md` holds the steps. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, `design.md`, the `core-transport` and `plugin-messaging` capabilities, and OpenBroker's ADRs 0010-0017 (in its repository).
3. Check that OpenBroker `0.2.0` is on Maven Central, and re-check its messaging API with ctx7 or its sources jar. If a fact in `design.md` no longer holds, fix it with `/opsx:update` before applying. Ask the user only what is really theirs to decide.
4. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
5. Verify: the narrow tests the change touches (plugin messaging, relay, capture, request-reply sampling, message send and browse); then push and let CI run the full suite.
6. PR, merge on green CI and an empty Sonar list, `/opsx:archive`.

## Why
Rahdar runs OpenBroker `0.1.0`, whose transport was incubating. `0.2.0` is OpenBroker's production messaging client. It has:
- declared guarantees;
- bounded sending;
- flow control;
- processing lanes on virtual threads;
- retry policies;
- recovery with a stated loss and duplicate contract;
- telemetry hooks.

Rahdar still runs its own receive loops for plugin consumers and relays on the old API. Adopting `0.2.0` now, before any new system arrives, means every later driver gets Rahdar's messaging features through the SDK's contract, not through Rahdar code written for Artemis.

## What Changes
- Rahdar builds against OpenBroker `0.2.0`.
- Plugin consumers run as SDK processors. Their concurrency becomes processor lanes, their flow control the processor's credit, and their message-group ordering the SDK's group ordering scope. Plugin messaging's contract and behaviour are unchanged.
- The relay, capture and request-reply sampling use the SDK's transacted guarantee and receiver options.
- Sends report in-doubt and resent outcomes from the SDK's receipts.
- Broker link recovery is reported in diagnostics with its state and serving node.
- OpenTelemetry messaging spans and metrics come from the SDK's observer, joining Rahdar's existing telemetry export.

## Capabilities
### New Capabilities
<!-- none -->

### Modified Capabilities
- `core-transport`: broker links recover under the SDK's stated contract, and the outcome is reported.

## Out of scope
- Any new broker system.
- Changes to the plugin messaging contract (`Contract.VERSION` stays unless japicmp flags a break).
- A user-facing retry or dead-letter policy editor.

## Depends on
- 19d2-openbroker-messaging (OpenBroker `0.2.0` on Maven Central)

## Execution
**Inline**: one dependency raise and a contained set of call sites (`platform/broker`, plugin drains, relay, capture, request-reply sampling, message service).

## Impact
`pom.xml` (`openbroker.version`); `platform/broker` (`BrokerMessages`, `BrokerRelay`); `feature/plugins/messaging/internal` (`PluginDrains`); `feature/sql` (`CaptureConsumer`); `feature/rr` (`RrSampler`); `feature/messages` (`MessageService`); diagnostics; telemetry export.
