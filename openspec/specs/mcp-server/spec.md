# mcp-server Specification

## Purpose
Defines the Model Context Protocol surface Rahdar exposes so an agent can diagnose and tune
a cluster on an operator's behalf — a small set of intent-shaped primitives that inherit the
same identity, permissions, safety and audit guarantees as the human-facing API, rather than
a machine-readable mirror of it.

## Requirements

### Requirement: The surface is a small set of intent-shaped primitives

The system SHALL expose an MCP endpoint whose primitives are shaped by operator intent, not
by the internal API's endpoint list. Operations that share a shape SHALL collapse behind a
discriminator argument rather than becoming separate primitives, and the value set of a
discriminator SHALL be validated by the system rather than enumerated in full in the
primitive's schema.

The surface SHALL declare only the protocol capabilities it implements.

#### Scenario: One primitive covers a family of reads

- **WHEN** a client lists the available primitives
- **THEN** resource kinds that share a listing shape are reachable through a single
  primitive taking a kind argument, not one primitive per kind

#### Scenario: An invalid discriminator value is rejected as a fixable error

- **WHEN** a primitive is invoked with a discriminator value the system does not support
- **THEN** the call fails with a message naming the values that are supported

#### Scenario: Undeclared capabilities are not advertised

- **WHEN** a client reads the server's declared capabilities
- **THEN** only capabilities the system actually implements are declared

### Requirement: The listed surface fits a stated size budget

The system SHALL keep the cost of discovering and using the surface bounded, because a
client pays for the full primitive listing on every connection and for every row of every
result. The published primitive listing SHALL stay within a budget the system enforces
automatically, and no single primitive's schema SHALL exceed a per-primitive share of it.

Result payloads SHALL be projections owned by this surface. They SHALL NOT be the same
representations the human-facing UI consumes, so that the surface's contract cannot drift
by following a UI need.

#### Scenario: The listing budget is enforced, not documented

- **WHEN** the surface's primitive listing grows past the stated budget
- **THEN** the system's own verification fails

#### Scenario: Listing is deterministic

- **WHEN** the primitive listing is requested twice from differently-connected clients
- **THEN** the listing and its ordering are identical

### Requirement: Every list result is capped and ordered

Every primitive returning a list SHALL accept a limit, SHALL apply a low default when none
is given, and SHALL enforce a maximum that a caller cannot exceed. Results SHALL be ordered
deterministically. A result truncated by a cap SHALL say so.

Large payloads SHALL NOT be embedded in a result. A message body SHALL be a second,
explicit fetch rather than inline content of a listing.

#### Scenario: Limit above the maximum is clamped

- **WHEN** a caller requests more rows than the enforced maximum
- **THEN** at most the maximum is returned and the response states it was capped

#### Scenario: Bodies are fetched, not listed

- **WHEN** a page of messages is browsed
- **THEN** headers are returned with an indication that bodies are retrieved separately

### Requirement: MCP calls authenticate as a personal API token and carry that identity

The MCP endpoint SHALL require authentication and SHALL accept the same personal API token
credential as the rest of the API. An unauthenticated call SHALL be refused before any
primitive executes.

An authenticated call SHALL execute under the token owner's identity, and every permission
check, cluster-scope check and audit attribution that applies to the equivalent human-facing
operation SHALL apply identically. The surface SHALL NOT introduce a second credential
store, a second authorization model, or any permission that exists only for MCP. A token's
tool allow-list and the installation's read-only mode only narrow what the token's grants
already permit.

Every tool call, reads included, SHALL produce an audit event naming the token's owner, the
token, the tool, the target cluster when there is one, and the outcome. The audit events of the
operations a call performs SHALL be attached to that call's event.

#### Scenario: Unauthenticated call is refused

- **WHEN** the MCP endpoint is called with no credential
- **THEN** the request is rejected as unauthenticated and no primitive runs

#### Scenario: A token's power is bounded by its owner

- **WHEN** a primitive is invoked with a token whose effective grants do not permit the
  underlying operation
- **THEN** the call fails and no broker or Rahdar state is changed

#### Scenario: Mutations are audited under the owner with the key attributed

- **WHEN** a mutating primitive completes
- **THEN** an audit event exists attributing the action to the token's owner and naming the
  token used, indistinguishable in coverage from the same action performed in the UI

#### Scenario: Read call

- **WHEN** an agent reads through a tool
- **THEN** the audit trail records the owner, token, tool name and outcome

### Requirement: Mutating primitives dry-run by default and require explicit confirmation

Every mutating primitive SHALL default to a dry run that reports the count that would be
affected and changes nothing. Acting for real SHALL require the caller to both request a
real run and supply a confirmation argument that matches the subject of the operation —
the protocol-level stand-in for the held confirmation the UI requires and an MCP client
cannot perform.

The confirmation gate SHALL be independent of the bulk-cap override. The two SHALL be
separate arguments, each defaulting to the safe value, and satisfying one SHALL NEVER
satisfy the other.

#### Scenario: Default invocation changes nothing

- **WHEN** a destructive primitive is invoked without arguments beyond its subject
- **THEN** an affected-count estimate is returned, no broker state changes, and an audit
  event is recorded marking the run as a dry run

#### Scenario: A real run without a matching confirmation is refused

- **WHEN** a destructive primitive is asked for a real run with a missing or mismatched
  confirmation argument
- **THEN** the call fails with a message stating what the confirmation must equal, and
  nothing is changed

#### Scenario: Confirmation does not lift the bulk cap

- **WHEN** a confirmed real run would affect more rows than the configured bulk cap and the
  cap override was not requested
- **THEN** the call fails naming the cap and the count, and nothing is changed

### Requirement: Failures are returned as results a model can act on

An operation that fails while executing SHALL be returned as a failed result carrying a
message stating what to do differently, never a raw internal error message or stack trace.
A malformed call, an unknown primitive, or an unknown resource address SHALL be returned as
a protocol-level error rather than an empty successful result.

#### Scenario: A recoverable failure explains the recovery

- **WHEN** a call fails because a limit, a cap, or a confirmation requirement was not met
- **THEN** the failure message names the constraint and the value needed to satisfy it

#### Scenario: Unknown primitive is a protocol error

- **WHEN** a client invokes a primitive that does not exist
- **THEN** the call is rejected as an invalid request, not as a failed result

#### Scenario: Internal detail is never surfaced

- **WHEN** an unexpected internal failure occurs during a call
- **THEN** the returned message contains no stack trace and no raw internal exception text

### Requirement: Denials never reveal what the caller may not see

A denial for a cluster-addressed operation SHALL be indistinguishable from a denial for a
cluster that does not exist, so that a caller cannot use the surface to enumerate cluster
identifiers. Such a denial SHALL NOT name the permission that was missing.

A denial for an operation that is not cluster-addressed has no identifier to protect and
SHALL name the missing permission, so that a caller learns what its key cannot do rather
than retrying blindly.

#### Scenario: No grant on a cluster is indistinguishable from no such cluster

- **WHEN** a cluster-addressed primitive is invoked for a cluster the caller holds no grant on
- **THEN** the failure is worded so that the existence of that cluster is not disclosed, and
  no permission name appears in the message

#### Scenario: A global denial names the permission

- **WHEN** a primitive requiring a global permission is invoked without it
- **THEN** the failure names the permission the key lacks

### Requirement: The surface publishes what the caller can see and do

The system SHALL expose, as read-only context requiring no arguments, the catalogue of
clusters the caller may see, what the caller's own credential is permitted to do, and per
cluster its topology and the broker capabilities that connection can reach.

Where a capability is unavailable because the broker connection lacks it, the published
capability context SHALL say so explicitly and include the exact broker configuration needed
to enable it. A capability SHALL NEVER be silently absent.

#### Scenario: The catalogue is filtered to the caller

- **WHEN** the cluster catalogue is read with a token granted on one cluster
- **THEN** only that cluster appears

#### Scenario: Missing broker capability is stated with its remedy

- **WHEN** capability context is read for a cluster whose connection cannot reach a feature
- **THEN** that feature is reported unavailable together with the broker configuration that
  would enable it

### Requirement: Prompts orchestrate and never act

The system SHALL publish diagnostic runbooks as prompts that sequence the surface's read
primitives, named by role (for example `investigate_subscription` and
`before_you_purge`, which takes a subscription). A prompt SHALL be stateless, SHALL NOT
perform any operation itself, and a runbook covering a destructive operation SHALL direct
the caller to dry-run and report to a human rather than to act.

#### Scenario: A destructive runbook stops before acting

- **WHEN** the runbook for a purge is retrieved
- **THEN** it prescribes a dry run, verification of consumer state, and a report to a human,
  and does not prescribe a confirmed run

### Requirement: Resource lifecycle is exposed as one guarded tool

The system SHALL expose subscription and destination lifecycle to an MCP client as a
single tool, `resource_lifecycle`, discriminated by the kind of operation, rather than as
one tool per verb, so that the surface stays within the tool-count budget the MCP
capability sets. The tool SHALL name its target as a resource argument holding the
canonical resource id, and SHALL be refused for a kind that does not support the
operation.

The tool SHALL default to previewing rather than acting. Turning the preview off
SHALL additionally require a confirmation argument that exactly matches the name of
the subscription or destination being operated on, so that a client which ignores every
protocol hint still cannot destroy a resource by accident.

The tool SHALL delegate to the same service the HTTP API uses, inheriting its
permission checks, its safety cap, and its audit trail. It SHALL NOT implement
authorization, capping, or auditing of its own.

The per-node outcome SHALL be returned to the client in full, so that an agent can
tell a fully applied command from a partially applied one.

#### Scenario: The default is a preview

- **WHEN** an MCP client invokes the lifecycle tool without specifying otherwise
- **THEN** the operation is previewed and no broker is mutated

#### Scenario: Acting without a matching confirmation is refused

- **WHEN** an MCP client turns off the preview for a destructive kind and supplies a
  confirmation that does not match the target's name
- **THEN** the call is refused and nothing is mutated

#### Scenario: The tool inherits the caller's permissions

- **WHEN** an MCP client whose token lacks the destroy permission invokes a destroy
- **THEN** the call is refused on the same basis as the equivalent HTTP request, and
  reveals no more about the cluster than that request would

#### Scenario: A partial application is legible to the agent

- **WHEN** a lifecycle tool call applies on some nodes and fails on others
- **THEN** the result reports the per-node outcome rather than a single success or
  failure

### Requirement: Request-reply tracing is reachable through one tool

The request-reply tool SHALL expose flows, statistics, declared expectations and
**diagnostics** behind a single discriminator argument, and SHALL NOT add a tool
per mode. The diagnostics mode SHALL return the same account of why tracing is or
is not producing flows that the interface presents.

#### Scenario: An assistant asks why there are no flows

- **WHEN** a client calls the request-reply tool with the diagnostics mode
- **THEN** it receives the sampler's account per traced destination and the ranked
  reasons with their remedies

#### Scenario: The listing cost is unchanged

- **WHEN** the tool listing is generated
- **THEN** adding the diagnostics mode has added no tool and no argument, and the
  listing budget still passes

### Requirement: Cluster health states its own freshness and clock confidence

The cluster-health result SHALL state when it was assembled, and SHALL include the
system's current verdict on the clocks involved — the verdict, the largest measured
offset and its uncertainty, and which nodes are affected.

#### Scenario: A model can tell how stale an answer is

- **WHEN** a client receives a cluster-health result
- **THEN** it carries the time the answer was assembled

#### Scenario: A model is told the numbers may be measured against a wrong clock

- **WHEN** the system's clock verdict is anything other than agreement
- **THEN** the cluster-health result carries that verdict and the affected nodes

### Requirement: The tool surface is discoverable without being exhaustively listed

The system SHALL present its MCP tool surface so that a client can identify the
right operation without every operation's full detail being carried in the tool
listing that is sent before any question is asked.

Discovery MAY be progressive — detail fetched when it is needed rather than
published up front — but SHALL NOT be mandatory: a client that reads only the tool
listing SHALL still be able to reach every capability the surface offers, even if
it does so less efficiently.

The system SHALL NOT present a capability it has as absent. Where the set of listed
tools is narrower than the set of capabilities, the listing SHALL name where the
remainder can be discovered, so that a client is never left to conclude that an
operation does not exist when it does.

#### Scenario: A minimal client still reaches every capability

- **WHEN** a client uses only the tool listing, without fetching any discovery
  resource
- **THEN** every capability the surface offers remains reachable

#### Scenario: A narrowed listing says where the rest is

- **WHEN** the tool listing presents fewer entries than the surface has operations
- **THEN** it names how the remaining operations are discovered

#### Scenario: Discovery does not change what is permitted

- **WHEN** a client discovers an operation through any route
- **THEN** its permission check, safety cap and audit trail are the ones the
  equivalent HTTP request would get

### Requirement: Progressive detail is reachable without optional protocol features

The primary route to the detail the tool schemas omit SHALL be one that every MCP
host implements. Resources are an optional server capability that a client is never
obliged to read, so the system SHALL NOT depend on a resource being fetched for its
surface to be usable.

The system SHALL expose that detail as a tool, returning an index of the operations
the surface offers when asked for no particular topic, and one tool's accepted
values, body shapes and semantics when asked for that tool.

Where the same detail is also published as a resource, that resource SHALL be
generated from the same source as the tool, and SHALL be a mirror on which nothing
depends.

#### Scenario: A host that never reads resources still gets the detail

- **WHEN** a client fetches no resource at all
- **THEN** it can still obtain every operation's accepted values and body shapes
  through the tool surface alone

#### Scenario: A rejected argument names the discovery route

- **WHEN** a call is rejected because a discriminator or required argument was
  wrong
- **THEN** the rejection names both the values it would have accepted and where the
  full detail can be read

### Requirement: Tools group only within one posture and one target

Operations SHALL be presented as one tool only when they share both a single honest
set of tool annotations and a single target with a common argument core.

A tool's annotations SHALL describe every operation it can perform, so a tool that
can destroy data SHALL declare itself destructive. Consequently a read-only
operation SHALL NOT be reachable through a tool that can also destroy data, because
a host gates a whole tool on that declaration.

A tool SHALL NOT present operations whose arguments are disjoint such that its
schema cannot be used without first fetching discovery detail, because that makes
progressive discovery mandatory.

#### Scenario: A read is not hidden behind a destructive tool

- **WHEN** an operation only reads
- **THEN** it is reachable through a tool that declares itself read-only

#### Scenario: A tool's annotations cover its most dangerous operation

- **WHEN** a tool can perform an operation that destroys data
- **THEN** it declares itself destructive, whatever else it can also do

### Requirement: The described surface is generated, never restated by hand

Every description of the tool surface the system publishes — the tool schemas, the
discovery tool, any discovery resource, and the instructions given to a host at
initialisation — SHALL be derived from one catalogue.

The system SHALL fail its build when a registered tool is absent from that
catalogue, so that a description of the surface cannot silently disagree with the
surface.

#### Scenario: A new tool cannot ship undescribed

- **WHEN** a tool is registered but not present in the catalogue
- **THEN** the build fails

#### Scenario: The initialisation instructions match the registered tools

- **WHEN** a host reads the instructions given at initialisation
- **THEN** every registered tool is named there

### Requirement: Connection control is exposed as one destructive tool

The system SHALL expose closing a connection, a channel, and a destination's consumers
to an MCP client as a single tool discriminated by the kind of operation, declared
as destructive and as not idempotent, and naming its target as a resource argument.

The tool SHALL default to previewing for the kind that affects an unbounded number
of connections, and turning the preview off SHALL require a confirmation argument
matching the client identifier or destination being targeted.

The tool SHALL delegate to the same service the HTTP API uses and SHALL NOT
implement authorization, capping, or auditing of its own.

An agent SHALL be able to tell from the result whether a connection was closed or
was already gone.

#### Scenario: The tool declares what it does

- **WHEN** an MCP client lists the available tools
- **THEN** the connection-control tool is declared destructive and not idempotent

#### Scenario: Acting without a matching confirmation is refused

- **WHEN** an MCP client turns off the preview and supplies a confirmation that does
  not match the target
- **THEN** the call is refused and no connection is closed

#### Scenario: An already-gone target is distinguishable

- **WHEN** an MCP client closes a connection that has already disconnected
- **THEN** the result reports it as already gone rather than as closed or as an error

### Requirement: Routes are discoverable and mutable through the existing tool shapes

The system SHALL make routes available to an MCP client through the existing resource
listing tool, `list_resources`, by its role and kind arguments rather than by adding a
tool per resource, so the surface stays within its tool-count budget.

The system SHALL expose divert creation and deletion as one guarded mutating tool that
is offered only for Artemis clusters, previewing by default, requiring a confirmation
matching the divert's name before acting.

The result of creating a divert SHALL state in words that the divert persists on the
broker and is absent from the configuration that broker will next deploy, so that an
agent reporting back to a human conveys the consequence and not only the success. It
SHALL NOT describe the divert as temporary or as one that is lost when the broker
restarts.

#### Scenario: Routing is listed through the existing tool

- **WHEN** an MCP client lists resources with the route role
- **THEN** the cluster's routes of every kind are returned, each with its kind label, without a new tool having been added

#### Scenario: A created divert reports the drift it creates

- **WHEN** an MCP client creates a divert
- **THEN** the result states that the divert persists on the broker and is absent from the
  configuration that broker will next deploy

### Requirement: Capture state is readable through MCP, and capture is not created through it

The system SHALL expose the capture subscriptions of a cluster, with their per-node state,
what they hold, and their estimated loss, to an MCP client through the existing resource
listing tool by extending its resource-kind discriminator.

The system SHALL NOT expose creating a capture subscription as an MCP tool. Capture mutates
broker routing and begins storing application payload, and the disclosure that governs it —
what will be created, on which nodes, and what will be retained — is a decision for a human
at the interface that states it, not a tool call an agent can make on their behalf.

#### Scenario: Capture state is listable

- **WHEN** an MCP client lists resources of the capture-subscription kind
- **THEN** the cluster's capture subscriptions are returned with their per-node state and what they hold

#### Scenario: Capture cannot be created through MCP

- **WHEN** an MCP client looks for a tool that creates a capture subscription
- **THEN** no such tool exists

### Requirement: Broker configuration is readable and applicable through guarded tools

The system SHALL expose a cluster's declared configuration, its drift, its fragment and
its apply history to an MCP client through one read-only tool with a kind discriminator,
and SHALL expose declaring and applying through one mutating tool with an operation
discriminator. These tools (`artemis_config`, `artemis_config_change` and
`artemis_config_diff`), and the setup review tool, exist for Artemis clusters only: they
SHALL be offered only to a caller who can read a cluster of that system, and a call that
names a cluster of another system SHALL return a tool error that says the tool applies to
Artemis clusters and which system the cluster runs.

The mutating tool SHALL dry-run by default. A dry-run apply SHALL return the plan — the
steps per node, the hazards in words with their identifiers, and the canary — and SHALL
state which identifiers a real run must acknowledge. A real run SHALL require a
confirmation matching the cluster's name and every high-class hazard identifier the plan
named, and SHALL be refused, listing what is missing, otherwise. A halted run's result
SHALL say in one sentence which node and step stopped it, what was not attempted, that
nothing was rolled back, and that re-running converges.

The permission check, the step cap, the canary-and-halt behaviour and the audit trail
SHALL be the ones the equivalent HTTP request would get.

Every kind of the read tool SHALL answer for a cluster backed by a real broker, and the
declare → plan → apply → converge loop SHALL be proven against one: a simulated broker
can only demonstrate the system's half of the contract, never that the broker accepts
what the planner sends or reports it back.

#### Scenario: The loop converges against a real broker

- **WHEN** a client declares a setting, applies it, and plans again
- **THEN** the apply is verified by a read-back the broker answered and the second plan has no steps

#### Scenario: A dry run names what to acknowledge

- **WHEN** an MCP client previews applying a declaration whose plan carries a message-loss hazard
- **THEN** the result lists the hazard with its identifier and states that a real run must include it

#### Scenario: A halted run is legible to the agent

- **WHEN** a real run halts on the second node
- **THEN** the result states the node and step, the nodes not attempted, that nothing was rolled back, and that re-running converges

#### Scenario: A configuration tool on another system's cluster

- **WHEN** a client calls a configuration tool with a cluster that is not Artemis
- **THEN** the call returns a tool error naming the system the cluster runs, and nothing is changed

### Requirement: The agent surface reflects the installation's enabled features

The agent surface SHALL offer only tools, catalogue entries and help text contributed by enabled features and active plugins, and offered to the caller by its token's tool allow-list and the installation's read-only mode. A tool not offered SHALL NOT be listed, SHALL NOT be described in the help tool or the tool catalogue resource, and SHALL NOT be referenced by the server instructions the caller receives. A runbook prompt whose steps name a tool of a disabled feature SHALL omit those steps and say that the feature is disabled on this installation. When a plugin is activated, updated or deactivated, its tools SHALL join, change in, or leave the listed surface and the catalogue without a restart. The server instructions SHALL state that installed plugins may add tools and that the help tool lists them.

#### Scenario: A disabled feature's tools are absent

- **WHEN** the tool list and the tool catalogue are read on an installation with the SQL feature disabled
- **THEN** no SQL tool is listed or described, and the catalogue matches the listed tools exactly

#### Scenario: A restricted token sees only its tools everywhere

- **WHEN** a token restricted to two tools reads the tool list, the help tool, the tool catalogue and the server instructions
- **THEN** only those two tools and the help tool are named, and the catalogue matches the listed tools exactly

#### Scenario: A runbook states a missing feature instead of naming its tool

- **WHEN** a runbook prompt is retrieved whose steps would use a disabled feature's tool
- **THEN** the prompt omits that step and states the feature is disabled on this installation

#### Scenario: An activated plugin's tools are listed and described

- **WHEN** a plugin contributing one tool is activated
- **THEN** the tool list and the help tool include it without a restart, and the catalogue still matches the listed tools exactly

#### Scenario: A tool call during a plugin update is answered

- **WHEN** a plugin tool is called while its plugin is in brief maintenance
- **THEN** the call returns an error result stating the plugin is updating and when to retry

### Requirement: The agent surface itself can be disabled

The agent surface SHALL be a feature that an operator can disable at startup. When it is disabled, its endpoint SHALL NOT exist, and a request to it SHALL receive `404`. API tokens SHALL continue to authenticate the REST API.

#### Scenario: Disabled agent surface has no endpoint

- **WHEN** the agent surface is disabled and a client with a valid API token calls its endpoint
- **THEN** the response is `404`

#### Scenario: Tokens still work for the REST API

- **WHEN** the agent surface is disabled
- **THEN** a valid API token still authenticates REST API requests within its grants

### Requirement: Message content through the agent surface is governed like the API

A message body or property returned through the agent surface SHALL be governed by the same content policy, and for the same token identity, as the equivalent API read. A value masked, dropped or withheld for that identity SHALL be marked as such in the result, so the model cannot mistake a marker for data.

#### Scenario: An assistant receives masked content

- **WHEN** an assistant using a token without clear access fetches one message body that contains a card number
- **THEN** the result shows the card number partially masked and identifies it as a masked payment card number

#### Scenario: A credential never reaches an assistant

- **WHEN** an assistant using a token holding clear access fetches a message with an `Authorization` property
- **THEN** the result shows the credential dropped

### Requirement: Subscription diagnosis reports the same verdict as the console

The agent surface's subscription diagnosis SHALL report the shared consumer-health verdict and
its evidence as structured values, rather than deriving its own trend or slow-consumer
judgement. A verdict that could not be computed SHALL be reported as such.

#### Scenario: A trend is a value, not a sentence

- **WHEN** an agent diagnoses a subscription
- **THEN** the depth trend is returned as a direction and a rate, not as prose a model must
  parse

#### Scenario: The agent reports the broker's verdict where there is one

- **WHEN** the broker has reported a slow consumer on the diagnosed subscription
- **THEN** the diagnosis reports that verdict and the named consumer, in preference to a
  derived one

#### Scenario: Insufficient sampling is stated

- **WHEN** an agent diagnoses a subscription with too few samples to derive a rate
- **THEN** the diagnosis states that the verdict could not be computed, rather than
  reporting the subscription as healthy

### Requirement: A token can be restricted to named MCP tools
A token SHALL optionally carry an allow-list of MCP tool names; calls to other tools SHALL be
denied exactly as a call to a tool that does not exist, without revealing them. The help tool
SHALL always be offered. An empty allow-list SHALL mean every tool the token's grants permit.

#### Scenario: Tool outside the list
- **WHEN** a restricted token calls another tool
- **THEN** the call is denied as an unknown tool and audited

#### Scenario: Discovery
- **WHEN** a restricted token lists tools
- **THEN** only allowed tools and the help tool are listed

### Requirement: MCP can be made read-only for the whole installation
An administrator SHALL be able to set the agent surface read-only for the whole installation; a
mutating call SHALL then be refused with a result stating the installation is read-only. A
read-only token SHALL remain a token granted only read permissions, with no second read-only flag.

#### Scenario: Global read-only
- **WHEN** global read-only is on
- **THEN** no token can perform a mutating primitive, even with a dry-run confirmation

#### Scenario: Read-only token
- **WHEN** a token granted only read permissions attempts a mutation through MCP
- **THEN** it is refused by its grants and audited

#### Scenario: Mutating tools hidden
- **WHEN** global read-only is on and an agent lists tools
- **THEN** mutating tools are not offered

### Requirement: MCP tools speak the SDK's vocabulary
MCP tool names, arguments and prompts SHALL use roles, kinds and canonical resource names. A tool argument naming a resource SHALL hold its canonical resource id. Tools that exist for one system only SHALL be offered only for clusters of that system.

#### Scenario: An agent lists resources
- **WHEN** an agent calls `list_resources` with a role
- **THEN** it receives resources of every connected system with that role, with native kinds

#### Scenario: An Artemis-only tool on another system
- **WHEN** an agent works on a cluster that is not Artemis
- **THEN** the Artemis configuration tools are not offered for it

#### Scenario: A system tool when no cluster of that system is readable
- **WHEN** a caller can read no cluster of a system
- **THEN** that system's tools are absent from the listing and the catalogue, as for a disabled feature

### Requirement: Kafka operations are MCP tools
The MCP server SHALL offer, for Kafka clusters only, tools to list groups with lag, describe a group, preview and reset offsets, read and change topic configuration, list ACLs, list transactions (the coordinator's open ones, and on request the hanging transactions found on partitions) and abort a transaction or a hanging transaction. A mutating tool SHALL return its preview first, need the matching permission, be held by the approval gate like its UI counterpart, and be audited.

#### Scenario: An assistant resets offsets
- **WHEN** an assistant calls the reset tool for an empty group
- **THEN** it first receives the preview with the per-partition change, and the reset runs only after confirmation and any approval the policy requires

#### Scenario: A Kafka tool on an Artemis cluster
- **WHEN** an assistant calls a Kafka tool for an Artemis cluster
- **THEN** the call is refused as unsupported on that system

#### Scenario: A read-only token
- **WHEN** an assistant whose token is read-only calls the offset reset tool
- **THEN** the call is refused and nothing is previewed or held
