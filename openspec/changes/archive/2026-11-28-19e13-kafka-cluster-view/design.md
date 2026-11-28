## Context

See the proposal and ADR-0233 for why. Everything the view needs is already in this repository: `KafkaBrokers` reads the nodes and the controller quorum, `KafkaPartitionReads.readAll` drains every partition page, `KafkaPlacement` derives offline replicas and "needs attention", and `KafkaHealthStore` holds the scrape's verdict. No OpenBroker change is needed; `openbroker.version` stays `0.13.0`.

## Goals / Non-Goals

**Goals:**

- One `ClusterView` from one batched read, and one screen that draws it as a graph and states the same facts in words.
- Brokers folded into Cluster; the Brokers screen and `GET /kafka/brokers` deleted.
- The version and structure matrix in ADR-0233, each case stated in words and never counted healthy.
- `kafka_describe_cluster` on the same service, so the tool and the screen cannot disagree.

**Non-Goals:**

- Any write, tracing, Flow, or a partition browser inside the graph.
- A background job or a stored snapshot; the view reads on request, under a heavy-read permit.

## Decisions

### The service

New `src/main/java/io/github/sudoitir/rahdar/system/kafka/KafkaClusterView.java` (`@Service`, `@RequiredArgsConstructor`), the only reader of the new endpoint. It takes `BrokerGateway`, `ClusterAccessGuard`, `ActorResolver` and `HeavyRequests`, and holds the permit in the service (as `AlertService.HISTORY` does) rather than in the controller, so the MCP tool pays the same bound.

Pseudo-shape, in this repository's names:

- `access.requireCluster(clusterId, Permissions.CLUSTER_READ)`.
- `heavy.run(CLUSTER, actors.resolve().displayName(), run -> ...)` with `static final HeavyRequests.Endpoint CLUSTER = new HeavyRequests.Endpoint("cluster reads", 4, Duration.ofSeconds(30), Integer.MAX_VALUE);` — the same bounds as `ClusterController.TOPOLOGY`.
- `connection.nodes().describe(Scope.whole())` through `KafkaFailures.call`, as `KafkaBrokers.read` does today, giving brokers, controller-only nodes, mode, racks, voters and the quorum already assembled there.
- `KafkaGroups.read(connection, ResourceQuery.of(KafkaKinds.PARTITION).project(...))` in the page loop lifted out of `KafkaPartitionReads.readAll`, giving every partition's leader, replicas and ISR.
- Two derivations over the partition list: per broker (`leaders`, `replicasHeld`, `outOfSyncReplicas`, `offlineReplicas`) and per broker pair (`sharedPartitions`, `outOfSyncShared`). `KafkaPlacement.ints`, `integer`, `preferred` and `offline` do the per-partition work; the pair count is a `Map<PairKey, long[]>`, built once.
- Problems: partitions `KafkaPlacement.needsAttention` flags, counted by cause (offline leader, replica out of sync, not on its preferred leader), with at most `TOP` (20) topic names.
- Topic names are withheld unless `access.holds(clusterId, Permissions.DESTINATION_READ)`, following `ClusterService.withheld`: one condition, a note saying so, counts kept.

### The record

DTOs go in `web/KafkaViews.java`, beside `QuorumVoter`/`BrokerQuorum`, which `ClusterView` reuses unchanged. `BrokerRow` and `Brokers` are used only by the endpoint this change deletes, so both go with it; the broker's own figures live on the new `ClusterBroker`.

```java
public record ClusterView(
        String mode,                 // KRaft, ZooKeeper or Unknown - the words the screen shows
        boolean complete,            // false when the driver's 2,000-topic cap was reached
        String reason,               // why it is incomplete; null when complete
        List<ClusterBroker> brokers,
        List<ClusterController> controllers,
        BrokerQuorum quorum,         // reused as is
        List<CoReplication> edges,
        ClusterTotals totals,
        ClusterProblems problems,
        List<String> notes) {}      // ZooKeeper, pre-4.0 fence gap, incomplete snapshot
```

Component records, all in `KafkaViews`:

- `ClusterBroker(String id, String rack, String address, boolean reachable, String reason, long leaders, long replicasHeld, long outOfSyncReplicas, long offlineReplicas, boolean quorumLeader)`
- `ClusterController(String id, Long lag, boolean caughtUp, boolean quorumLeader)` — a controller-only node; `id` is its native id.
- `CoReplication(String a, String b, long sharedPartitions, long outOfSyncReplicas)` — `a` and `b` are broker ids, sorted so the pair is stable.
- `ClusterTotals(long brokers, long brokersReached, long controllers, Long topics, Long partitions, long underReplicated, long offline, long preferredLeaderMismatches, boolean atLeast)` — `topics` and `partitions` are `null` with `atLeast` true when the cap was reached, never an invented total.
- `ClusterProblems(long offline, long underReplicated, long preferredLeaderMismatches, List<String> topics, String topicsWithheld)` — `topics` capped at `TOP`, `topicsWithheld` the note when the caller lacks `destination:read`.

`mode` is taken from the first node's `kafka.cluster_mode` attribute ("kraft" → "KRaft", "zookeeper" → "ZooKeeper", absent/"unknown" → "Unknown"), which is exactly `KafkaBrokers.read`'s current rule; the mapping is kept in the new service and `KafkaBrokers` is deleted.

### The endpoint

New `src/main/java/io/github/sudoitir/rahdar/system/kafka/web/KafkaClusterController.java`, split like `KafkaBrokerController` and `KafkaReplicationController` are, with the same `@RequestMapping("/clusters/{clusterId}/kafka")` and `@Tag(name = "kafka-controller")`:

- `@GetMapping("/cluster")` returns `ClusterView`.

`KafkaBrokerController` keeps `/brokers/{broker}/config`, `:preview` and `:apply` and loses its `brokers` field and its `@GetMapping("/brokers")`. `KafkaViews.Brokers` and `KafkaViews.BrokerRow` are deleted with it.

### The MCP tool

`KafkaMcpToolDefs.ALL` gains one `read("kafka_describe_cluster", ...)` entry with a single optional parameter (none are required but `clusterId`, which every tool takes), and `KafkaMcpReadTools` gains one `@McpTool` method calling `clusterView.read(id)`, wrapped in `McpErrors.guard`. The answer is the `ClusterView` with `problems.topics` capped and `truncated`/note when a cap applied, following the Kafka read tools' habit (`KafkaMcpViews`). Budget: `McpToolSchemaBudgetTest`'s average of 140 is checked after the tool lands; if it fails, the reason comment is extended.

### The web

New `web/src/features/kafka/clusterGraph.ts`, pure and unit-tested:

- `clusterGraph(view: ClusterViewDTO): { nodes: DiagramNode[]; edges: DiagramEdge[]; summary: {...} }`
- One node per broker (`id: broker:<id>`, `kind: 'Broker'` or the controller role, `label: Broker <id>`, `detail: rack · address`, `state: 'warning'` when unreachable, `reason` the cluster's words).
- One node per controller-only node (`id: controller:<id>`), marked quorum leader in its `kind` word.
- One edge per `CoReplication` (`id: a-b`, `label: '412 shared partitions'`, `dashed` when `outOfSyncReplicas > 0`, `weight: sharedPartitions`). Sorted by `sharedPartitions` desc and cut to `GRAPH_EDGE_CAP = 200`; `summary.omittedEdges` says how many were cut. The label is a sibling of `DiagramEdge.label`, which `DiagramView` already renders as the arrow's chip and already folds into the target's accessible name — no new field for it; `weight` is the only addition.
- Deterministic order so the layout signature is stable.

`web/src/features/kafka/ClusterView.tsx` (replaces `BrokersView.tsx`): `Page` → `PageHeader` (title "Cluster", Graph/Table `SegmentedControl` in `actions`) → `Stat` row → `DiagramView` or the broker `DataTable` → `Section`s for the controller quorum (moved from `BrokersView`, unchanged in wording), brokers, partitions needing attention, and the notes. A chosen broker opens an aside panel with its rack, address, reach, leaders, replicas held, replicas out of sync, and a link to `/clusters/{id}/kafka/brokers/{b}/config`.

`ui/DiagramView.tsx` and `ui/diagram.ts` need exactly one addition: `DiagramEdge.weight?: number`, rendered as a CSS custom property on the `BaseEdge` (`style={{ '--edge-weight': weight }}`) so `DiagramView.module.css` can scale `stroke-width`. `label` (the chip), `dashed`, `onSelect`, `nodeActions` (pass none) and the roving focus already exist and are reused as they are; `layoutSignature` is unchanged because a weight does not move a box.

`web/src/features/kafka/api.ts`: `keys.cluster(c)`, `useKafkaCluster(c)` with `refetchInterval: 15_000` (the brokers cadence), `export type ClusterView = Schemas['ClusterView']`. `keys.brokers` and `useKafkaBrokers` go with the endpoint; `CreateTopicForm` is the one caller and reads `useKafkaCluster` instead.

`web/src/features/kafka/feature.ts`: `clusterRoute` at `kafka/cluster` (new, `validateSearch: validateClusterSearch`), the `brokersRoute` deleted, and the nav entry `{ group: 'observe', order: 10, label: 'Cluster', icon: IconTopologyStar3, path: 'kafka/cluster', permission: 'cluster:read' }` in place of Brokers. `ClusterLanding` lands Kafka on Cluster with no code change because it opens the first Observe item.

### Two knock-on edits the plan did not name

- `web/src/features/kafka/CreateTopicForm.tsx` reads `useKafkaBrokers` to bound the replication factor. With `/brokers` gone it reads `useKafkaCluster` and `view.totals.brokersReached` instead — one line each in `CreateTopicForm.tsx`, `CreateTopicForm.test.tsx` and the `destinations.create` slot's props.
- `web/src/features/kafka/BrokerConfigView.tsx` links "All brokers" to `/kafka/brokers`; it now links to `/kafka/cluster`.

## Risks / Trade-offs

- [The graph is empty on a cluster whose partitions could not be read] → The broker boxes still draw from the node read, with no edges and a note saying placement was not read; the table still lists every broker.
- [A large cluster makes the read slow] → One batched read per partition page, bounded by the driver's 2,000-topic cap and the endpoint's permit; the view says "at least" when it hits the cap.
- [Dense graph] → Above 200 co-replication pairs the strongest 200 are drawn and the count left out is stated; the table is complete.
- [The DTO is duplicated between kinds] → `ClusterView` reuses `BrokerQuorum`/`QuorumVoter`; no second quorum shape.

## Migration Plan

No schema change. Removing `GET /clusters/{id}/kafka/brokers` is breaking: the commit carries `!` and a `BREAKING CHANGE:` paragraph naming `GET /clusters/{id}/kafka/cluster` and `kafka_describe_cluster` as the replacements. `web/openapi.json` is regenerated by `OpenApiSnapshotTest` and `web/src/kernel/api/schema.d.ts` by `npm run gen:api`.

## Open Questions

None.



