# ADR-0204: The schema is broker-neutral, and a system prefixes its own tables

- **Status**: accepted
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

Rahdar's tables were named in Artemis's terms: `queue_snapshot`, `broker_node.artemis_node_id`,
`jolokia_url` and `core_url`, `ha_role` with `STANDALONE`, queue and address columns everywhere, a
routing type on every row. With every broker reached through OpenBroker (ADR-0203), those names would
be wrong for the next system and would leak Artemis into modules that are meant to be shared. Until the
first stable release a schema change edits each module's baseline and operators recreate the database
(ADR-0192), so a rename costs no migration.

## Decision

Shared modules store what they keep in the broker SDK's vocabulary; what only one system has is stored
under that system's name.

- **Clusters and nodes.** `cluster.system` names the system a cluster runs. A node has
  `native_node_id`, `ha_group` and `ha_designation` (PRIMARY, BACKUP or NONE); its addresses are rows of
  `broker_node_endpoint` (plane, protocol, url, and whether the address was a seed, derived,
  discovered or set by an operator). Data URLs are stored ready to dial. A cluster's stored broker
  accounts are named by plane (`MANAGEMENT`, `DATA`), not by protocol.
- **Destinations and subscriptions.** Columns that held an address hold a `destination`; columns that
  held a queue hold a `subscription` (`subscription_snapshot`, `message_index`, flow edges, bulk items,
  plugin registrations, transfer runs, request-reply flows, governance findings and rules). A
  destination may be absent: a standalone subscription has none.
- **Standard metrics.** The scrape cache and `metric_sample` use the SDK's metric keys
  (`openbroker.backlog`, …). A sample's subject is `subject_kind` and `subject_path`; a plugin's own
  metric has the kind `rahdar.plugin`.
- **Native detail in `native_attributes`.** What a system says beyond the standard attributes, such
  as an Artemis queue's routing type, is a `jsonb` of native attribute names
  (`artemis.routing_type`), not a column.
- **Kinds and classes.** A snapshot row records the SDK kind (`artemis.queue`); a sampled route
  records its SDK route class (binding, forward, link) beside its native kind.
- **A system's own tables carry its prefix.** Tables of a module that exists for one system are named
  after it: broker configuration's `artemis_config_*` and setup review's `artemis_setup_*`.

## Consequences

- A second system adds no shared columns; its specifics go in `native_attributes` or its own
  prefixed tables.
- Reading a native attribute means a JSON lookup; Rahdar reads only the routing type this way, and
  only where it addresses an Artemis queue.
- REST field names, MCP arguments, permission identifiers (`queue:*`, `address:*`), the plugin
  contract's `ResourceKind` (QUEUE, ADDRESS) and the kinds stored with team patterns, shares and API
  token grants keep Artemis's nouns for now. They are one public vocabulary and are renamed together in
  roadmap change 19d, so the stored kinds stay aligned with the permissions they scope.
- Operators recreate the database for this release.

## Alternatives considered

- **Generic key/value columns for everything.** Flexible, but loses typed columns, indexes and the
  constraints `SchemaLintTest` checks.
- **One column per system attribute (`routing_type` for Artemis, `partition` for Kafka, …).** Clear
  for one system, but every driver would widen shared tables with columns most rows leave empty.
- **Rename the stored resource kinds now, ahead of the permissions.** It would leave team patterns
  saying SUBSCRIPTION while the permission they grant says `queue:read`, a mixed vocabulary for one
  release.
