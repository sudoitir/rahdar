# Design: Kafka in Rahdar

## Context

Rahdar is broker-neutral:
- ADR-0206: shared API by role, with resources by `<kind>/<path>`;
- ADR-0207: a system namespace for system-only routes, with `SystemGate` and the `system` field on views;
- ADR-0208: `SystemExtension`s for what the SDK lacks;
- ADR-0209: role-named permissions;
- ADR-0210: role-named plugin messaging.

Supporting pieces:
- `system/artemis` is today's only system module.
- Clusters store their `system` in a column.
- Drivers load once through `BrokerDrivers.load()`.
- Labels come from `ResourceIds.label`, which sentence-cases a kind id's local part.
- `capabilityGate.ts` and `useCan` disable what a kind or capability lacks, with the reason.

OpenBroker's Kafka driver (see its `broker-sdk-kafka` capability) supplies:
- the kinds `kafka.topic`, `kafka.partition`, `kafka.group`, `kafka.group-topic`, `kafka.share-group-topic`, `kafka.acl` and `kafka.transactional-id`, each with a label;
- lag as an estimate with a per-partition breakdown;
- purge and seek only on an empty group, truncate with per-group reach, and browse with no group;
- per-message move, copy and delete declared unavailable.

## Goals / Non-Goals

**Goals:**
- Kafka as a first-class system in the estate.
- The Kafka-only screens an operator on call needs.
- No Artemis-shaped assumption left in a shared path.

**Non-Goals:**
- Kafka Streams, Connect, schemas, reassignment and quotas.
- Mobile layouts.

## Decisions

### D1. Registration is per system
- **Rejected: overloading the Jolokia seed-URL form.**

The registration request carries a sealed `connection` by system:
- Artemis keeps its seed URLs and accounts.
- Kafka has bootstrap servers, a security protocol, a SASL mechanism, credentials and an optional TLS reference.

The UI's form switches on the chosen system. The dry run calls the driver's `verify`. Driver options go into `ClusterSpec.options`, and secrets into the sealed store.

### D2. Labels from the SDK
`ResourceIds.label` is removed. `ClusterKinds` sends the driver's `Kind.label` (singular and plural) in `KindView`, and the web uses it everywhere a kind is named. This is a breaking change to the cluster view's JSON, which the commit says (no backward compatibility).

### D3. `system/kafka` module
Backend:
- `KafkaModule`;
- read services over the SDK (groups, lag, configs, ACLs, transactions, quorum);
- previews and runs for offset reset, config change, ACL change and transaction abort.

Each is a gated operation with an `Effect` (AIP-154), so the approval gate and audit apply as they do for shared operations. Anything the SDK does not model goes through `ClusterConnection.unwrap(Admin.class)` inside this module only, as a `SystemExtension` (ADR-0208):
- config sources;
- quorum voter lag.

Tables, if any are needed (for example cached group snapshots for history), are prefixed `kafka_` (ADR-0204).

### D4. API contract
All routes are under `/api/clusters/{c}/kafka/`.

| Method and path | Purpose |
|---|---|
| `GET groups?type&state&cursor` | groups with type, state, members, lag estimate |
| `GET groups/{g}` | partitions with committed, end, lag; members with assignments |
| `POST groups/{g}/offsets:preview` | body `{topics?, to: earliest\|latest\|time\|offset, value?}`; returns per-partition change, skipped or re-read counts, active members, `etag` |
| `POST groups/{g}/offsets:reset` | body is the preview's request plus its `etag`; `409 FAILED_PRECONDITION` when members joined; `412` when stale |
| `GET topics/{t}/config` | settings with value, source and whether sensitive |
| `POST topics/{t}/config:preview`, `:apply` | the same preview and etag pattern |
| `GET acls`, `POST acls:preview`, `:apply` | list, add and remove |
| `GET transactions`, `POST transactions/{id}:abort-preview`, `:abort` | list and abort |
| `GET brokers` | brokers, under-replicated and offline counts, quorum |

Problems follow the existing problem-details shape. Permissions map to the role-named permissions:
- `subscription:write` for offsets;
- `destination:write` for configuration;
- `access:write` for ACLs;
- `cluster:operate` for transactions.

### D5. Shared screens on Kafka
- **Browse** gains a partition and start picker when the destination's kind declares partitions: earliest, latest, offset or time.
- **Purge and truncate** previews render the driver's reach (members, groups and their losses) and use the held destructive confirmation (`rahdar-safe-interactions`).
- **Move, copy and delete** are disabled with the driver's reason.
- **Subscriptions** show lag as an estimate with a breakdown drawer.

### D6. Health in the estate
Health maps from the driver's conditions:
- brokers reachable;
- controller quorum majority;
- under-replicated and offline partitions.

These sit on the same verdict scale as Artemis, so the estate stays comparable.

### D7. MCP
The tools are `kafka_list_groups`, `kafka_describe_group`, `kafka_preview_offset_reset`, `kafka_reset_offsets`, `kafka_topic_config`, `kafka_set_topic_config`, `kafka_list_acls` and `kafka_abort_transaction`. They are registered only for Kafka clusters and held like their UI counterparts. `McpToolSchemaBudgetTest` is raised as needed, with a comment (standing decision), and shared enums move into `McpToolCatalog`.

### D8. Dev stack and demo
- The dev compose adds one `apache/kafka` broker.
- The demo compose adds a three-broker KRaft cluster.
- `scripts/demo-seed.sh` registers it, creates topics, and starts traffic with two groups, one of which lags, and a share group.

### D9. ADRs
- Cluster registration is per system (D1).
- Kind labels come from the SDK (D2).
- The Kafka namespace and its operations (D3, D4).

## Risks / Trade-offs

- [Lag polling on large clusters is expensive] → Group offsets are read in batches through one Admin call per cycle, on the existing scrape schedule, and the screens show the sample's age.
- [Offset reset is irreversible in effect] → It needs an empty group, a preview with an etag, the held confirmation and the approval gate, and it is audited with before and after so an operator can reset back.
- [A shared screen still assumes Artemis] → The reviewer's pass and the screenshot sweep cover every shared screen on a Kafka cluster.
