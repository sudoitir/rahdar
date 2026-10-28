---
title: MCP server
description: Rahdar speaks the Model Context Protocol, so an assistant can answer questions against your real clusters under the same grants and the same audit trail as a person.
---

# MCP server

Rahdar speaks the [Model Context Protocol](https://modelcontextprotocol.io), so
an assistant can answer *"why is `ORDERS.DLQ` backed up"* against your real
clusters instead of guessing.

The surface is **intent-shaped** tools — `diagnose`,
`message_action`, `artemis_config_change` — not a mirror of the REST API. A mirror would
spend the model's context on plumbing and leave it to assemble the diagnosis;
the tools are shaped like the questions instead
([ADR-0045](/reference/adr/0045-mcp-server-is-a-capability-surface)).

## Get a key

Sign in → avatar menu → **Account** → **API keys** → **New key** → choose when
it expires, the scope and permissions it carries, and optionally the **MCP tools**
it may call → copy the value. It is shown once.

- **Expiry.** Every key expires, at most the installation's maximum lifetime
  after it was minted (90 days by default, *Settings → API tokens → Maximum
  lifetime*). Lowering the maximum shortens existing keys at once.
- **Tools.** A key restricted to named tools is offered only those, plus
  `rahdar_help`. The other tools are not listed or described to it, and calling
  one answers exactly as a tool that does not exist.
- **Rotation.** **Rotate** issues a new secret and keeps the old one working
  for the rotation overlap (24 hours by default), so an assistant's
  configuration can be updated without an outage. The key keeps its permissions
  and its expiry.
- **Limits.** A key may make 600 requests a minute with 8 in flight, and one
  user 1,200 a minute across all their keys. Every response carries
  `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`, and an excess
  request gets `429` with `Retry-After`.
- **Usage** shows each key's requests per day, with how many were denied,
  limited or failed. Administrators with *See and revoke every user's API
  tokens* see every key under **Admin → API keys**, where unused keys are
  flagged and any key can be revoked
  ([ADR-0136](/reference/adr/0136-token-rotation-lifetime-cap-limits-and-usage)).

## Connect

`POST /mcp`, on the same origin as the UI, with the key as a bearer token.

```json
{
  "mcpServers": {
    "rahdar": {
      "type": "http",
      "url": "https://rahdar.example.com/mcp",
      "headers": { "Authorization": "Bearer as_..." }
    }
  }
}
```

Smoke-test it without a client:

```bash
curl -s https://rahdar.example.com/mcp \
  -H "Authorization: Bearer as_..." \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

`scripts/mcp-config-e2e.sh` drives the whole configuration loop over this wire —
handshake, `tools/list`, declare, plan, refuse a stale plan, apply, converge,
and every read kind — against the dev stack, and removes what it applied so it
can be run again.

## What is there

| Kind | Name | For |
|---|---|---|
| Tool | `rahdar_help` | the catalogue itself: every tool, its posture and its parameters |
| Tool | `diagnose` | a cluster (HA role per node, split-brain, replication lag, firing alerts) or one subscription end to end |
| Tool | `list_resources` | destinations, subscriptions, routes, connections, channels, consumers and producers, by `role`, each with its id and kind |
| Tool | `metric_series` | a bucketed timeseries for one metric |
| Tool | `artemis_config_diff` | every node's configuration against the majority: the keys that drift and the nodes that differ |
| Tool | `artemis_config` | the cluster's declaration, its drift per node, the `broker.xml` fragment, or past applies |
| Tool | `browse_messages` | headers of a subscription, or one body by id |
| Tool | `trace_request_reply` | flows, latency and timeout statistics, configured expectations |
| Tool | `activity_log` | broker events, or Rahdar's own audit trail |
| Tool | `message_action` | move / retry / delete / expire / purge |
| Tool | `resource_lifecycle` | create, update, pause, resume or destroy a subscription or destination |
| Tool | `artemis_divert_lifecycle` | create or delete a divert |
| Tool | `artemis_config_change` | declare a configuration, or apply it canary-first with hazards acknowledged by id |
| Tool | `connection_action` | close a connection, channel, consumer or a destination's consumers |
| Tool | `send_message` | enqueue one message |
| Tool | `kafka_list_groups`, `kafka_describe_group` | consumer groups with state, members and lag, and one group's partitions and members |
| Tool | `kafka_preview_offset_reset`, `kafka_reset_offsets` | what a reset would move, and the reset itself, run against the preview |
| Tool | `kafka_topic_config`, `kafka_set_topic_config` | a topic's settings and where each comes from, and changing them |
| Tool | `kafka_list_acls` | the cluster's ACLs |
| Tool | `kafka_abort_transaction` | abort a transaction, after its preview |
| Tool | `setup_review` | the latest configuration review of a cluster |
| Tool | `alert_rule` / `rahdar_setting` | alert rules; operational settings |
| Resource | `rahdar://clusters`, `rahdar://permissions`, `rahdar://tools` | what this key can see and do |
| Resource | `cluster://{id}/topology`, `cluster://{id}/capabilities`, `cluster://{id}/nodes/{nodeId}/settings` | nodes; what the connection supports, with the `broker.xml` to enable what it does not; one node's effective settings |
| Prompt | `triage_cluster`, `investigate_subscription`, `before_you_purge`, `tune_scrape_load` | runbooks |

## Resources, roles and systems

A tool names a resource by its **canonical id**, such as `artemis.queue/orders`, the
`id` that `list_resources` returns on every row, with the `kind` and `kindLabel` the
system gives it. The arguments are named for what the resource is: `subscription`,
`destination`, `resource` (a lifecycle target) and `target`. `list_resources` takes the
`role` to list, one of `destination`, `subscription`, `route`, `connection`, `channel`,
`consumer` and `producer`, and optionally one `kind` of it.

A tool that exists for one broker system carries that system's id as a prefix:
`artemis_config`, `artemis_config_change`, `artemis_config_diff`,
`artemis_divert_lifecycle`, the eight `kafka_*` tools, and `setup_review`. It is offered only to a key that can read
a cluster of that system, and a call that names a cluster of another system is a tool
error that says which system the cluster runs.

## The safety contract

An assistant with a management API is exactly where a safety model needs to be
boring and explicit. This one is:

- **A key never exceeds its owner.** Grants are intersected with the owner's
  *live* grants on every call, so narrowing a person narrows their keys at once
  ([ADR-0046](/reference/adr/0046-mcp-authenticates-with-existing-api-tokens)).
- **Mutations dry-run by default.** A real destructive run additionally requires
  `confirm` to equal the subscription's or destination's own name — separate from the bulk-cap
  `override`, which answers a different question and is never satisfied by
  `confirm`.
- **A Kafka change is previewed first, and held.** `kafka_preview_offset_reset` returns what each partition
  would skip or read again, who still holds the group and an `etag`; `kafka_reset_offsets`,
  `kafka_set_topic_config` and `kafka_abort_transaction` run only against the `etag` of their preview, are
  refused as stale if the state moved, and are held for approval wherever the approval gate is on. Like every
  mutating tool they are hidden when the installation is read-only.
- **A configuration apply is the same contract at cluster scale.** The dry run
  returns the plan, its hazards and the exact `acknowledge` ids a real run must
  echo; the real run needs `confirm` to equal the cluster's name and
  `expectedPlanHash` to equal the hash it previewed, goes canary first and halts
  at the first failure. See [Broker configuration](/guide/broker-configuration).
- **Every call is audited**, reads included, as `MCP_TOOL_CALL` under the owner
  with the key's name attached (`ada [token: laptop-agent]`), naming the tool,
  the cluster and the outcome. The rows a mutation writes, dry runs included,
  hang under it ([ADR-0137](/reference/adr/0137-one-gate-on-the-mcp-transport)).
- **The installation can be made read-only.** *Settings → MCP server →
  Read-only* hides every mutating tool and refuses it for every
  key, whatever its `confirm`. A single read-only key is simply a key granted
  only read permissions.
- **A cluster the key holds no grant on** comes back as *"no such cluster, or
  this key has no grant on it"* — naming no permission and confirming no id.

## Discovery

`rahdar_help` is a tool, not an optional resource, and one catalogue generates
the schemas, the help text, the resource and the server instructions — so they
cannot disagree ([ADR-0054](/reference/adr/0054-mcp-discovery-is-a-tool-not-a-resource)).
