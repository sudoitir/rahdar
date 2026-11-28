# ADR-0233: A Kafka cluster is one read-only view with a co-replication graph

- **Status**: accepted
- **Date**: 2026-11-27
- **Deciders**: Mahdi Amirabdollahi

## Context

Kafka's first Observe view is Brokers: two static tables and a description list. An operator can list the brokers and read the quorum. They cannot see the cluster as one picture - where replicas sit, which brokers hold replicas of the same partitions, which partitions are sick - without opening Replication and then every topic in turn. The Artemis cluster has that picture (Topology: one derivation, drawn as a graph, listed as a table, with a panel for the chosen node). ADR-0230 recorded the gap in its own consequences: the cluster graph is a later change.

Everything the picture needs already exists. `KafkaBrokers` reads the brokers and the controller quorum, `KafkaPartitionReads` reads each partition's leader, replicas and in-sync set, and `KafkaHealth` carries the scrape's verdict. The driver reports what the cluster says about itself: its mode (`kafka.cluster_mode`, one of `kraft`, `zookeeper`, `unknown`), each broker's rack, each voter's lag, and, from Kafka 4.0, fenced brokers. No OpenBroker change is needed.

Two constraints decide the shape. A cluster is read as one (ADR-0224): a listing answers for the whole cluster, and a view assembled from several calls is several moments in time that disagree with each other. And reading placement means reading every partition, which on a large cluster is one batched broker read per page and a driver cap of 2,000 topics (`Topology.MAX_TOPICS`).

## Decision

**One view, read once.** `GET /clusters/{id}/kafka/cluster` answers one `ClusterView` from one batched read: the nodes described as a whole, the topics listed, and every partition page drained through the loop `KafkaPartitionReads` already had. The Cluster view replaces Brokers - it is the first Observe view and the cluster's landing page - and `GET /kafka/brokers` and its screen are deleted. There is no second, cheaper broker read for the same facts.

**The graph is brokers, and its edges are co-replication.** One box per node that serves clients, plus one box per controller-only node; the quorum leader is marked by a role word, not a colour. Two brokers are joined by an edge when they hold replicas of the same partition. The edge's label is the number of partitions they share, its thickness that number, and it is dashed when at least one of those shared replicas is out of sync. A controller-only node has no edges: a controller holds no partition.

A bipartite broker-to-topic graph was rejected: it is literal placement, and past a few dozen topics it is unreadable unless most of it is hidden, which makes the picture a partial truth that reads as the whole. A brokers-only graph with quorum edges was rejected: placement would live only in the words, and the graph would add nothing over the table.

**The box and the words are one derivation.** The brokers table under the graph is the same `ClusterView`. It gains the replicas each broker holds, the ones out of sync and the ones offline, and a link to that broker's configuration. The words below it are Sections: the controller quorum moved from the Brokers screen, and the partitions that need attention with a link to Replication. Nothing on the view writes: no action, no menu, no trace, not Flow.

**What the cluster cannot show stays visible and is never counted healthy.** The view carries a note for each case, and a figure it cannot know is absent with the reason, never zero:

- **ZooKeeper mode** (the driver supports 2.8 and later): there is no quorum to read. The quorum section says the controller is kept in ZooKeeper and cannot be read, and it is not counted as a majority or a healthy quorum. The graph is unaffected.
- **KRaft before 4.0** (metadata level below 22): the quorum reads, but the cluster does not list fenced brokers, so a fenced broker is absent from the node list. The view states in words that a fenced broker is not reported before Kafka 4.0, so its absence is never read as healthy. The driver already declares `kafka.fenced-brokers` unavailable with that reason, and the view repeats it.
- **Mode unknown** (the metadata version could not be read): the mode is `unknown`, the quorum is shown as unknown, and neither is presented as healthy - the rule `KafkaHealth` already applies.
- **Dedicated controllers**: a node whose functions include `CONTROLLER` and not `BROKER` is a controller box, not a broker, and is not counted among the brokers.
- **Incomplete snapshot**: the driver describes at most 2,000 topics. When the partition read covers that many distinct topics the totals say **at least** and `complete` is false with the reason. A cluster that genuinely has exactly 2,000 topics is told "at least" too; the figure is a floor, never a guess.
- **Dense graphs**: above 200 co-replication pairs the graph draws the 200 with the most shared partitions and says how many it left out. The table still lists every broker.

**Permissions.** The aggregate needs `cluster:read`, the permission of the screen's own navigation row (ADR-0216). Topic **names** in the partitions that need attention need `destination:read`; without it the counts stay and the names are withheld with a note saying so - the `withheld` pattern `ClusterService` already uses, one condition and no new permission.

**It is a heavy read, and one MCP tool reads it.** The endpoint holds a permit like `/topology` (ADR-0196): it walks every partition of the cluster. `kafka_describe_cluster` calls the same service under the same `cluster:read`, so the tool and the screen cannot disagree.

## Consequences

- An operator reads the cluster, its placement and its problems on one screen, from one snapshot, and cannot write anything there.
- Removing `GET /kafka/brokers` is a breaking API change. Nothing is kept for compatibility before the stable release (`36-stable-release`); the change carries `!` and a `BREAKING CHANGE:` footer.
- Every read of the view reads every partition of the cluster. The cost is bounded by the driver's 2,000-topic cap and the endpoint's permit, not by what the operator is looking at. A cluster past that cap gets a floor, not a total.
- The graph is a summary of placement, not a partition browser. A partition is opened on its topic's page or on Replication; the Cluster view links to both.
- `KafkaBrokers` is absorbed by the new service. Its derivation, not its endpoint, is what the view keeps.
- Above 200 pairs the drawing is a sample. That ceiling is the price of drawing a complete graph of brokers; a cluster where it is reached has more brokers than an operator can read at once anyway.

## Alternatives considered

- **A broker-to-topic bipartite graph.** Rejected. Unreadable past a few dozen topics without a hide-cap, and the hide-cap makes the picture a partial truth that reads as the whole.
- **Brokers only, with quorum edges.** Rejected. Placement would be in the words alone, and the graph would say nothing the table does not.
- **Reusing the Artemis `TopologyCanvas`.** Rejected. Its layout is Artemis's pair grammar - serving above, standby below, one axis per logical node. Kafka has no pairs, so this would be a second grammar inside a component whose geometry is Artemis's (ADR-0056).
- **One endpoint per panel (brokers, quorum, edges, problems).** Rejected. Four round trips and four moments where the picture contradicts itself and the words beside it.
- **Reading placement from the scrape's stored snapshot.** Rejected. The scrape's partition state is per node and up to a tier interval old; a cluster view that says "now" would be saying it about the past.
- **A new permission for the graph.** Rejected. It shows the cluster's brokers and partitions, which `cluster:read` and `destination:read` already name; a third name would split the same authority.

