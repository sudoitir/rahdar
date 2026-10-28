# Technical glossary

Rahdar's shared code, REST API, MCP tools, plugin contract and shared screens speak OpenBroker's
vocabulary. A broker system's own words (an Artemis address, a Kafka topic) appear only as a
resource's **kind label**, on system-only screens, and inside that system's module.

| Term | Definition | Use When | Avoid |
| --- | --- | --- | --- |
| system | The broker product a cluster runs, named by its driver id (`artemis`). | Choosing system-only routes, tools and screens. | broker type, vendor, flavour |
| role | The neutral class every resource belongs to, OpenBroker's `ResourceClass` (destination, subscription, route, connection, channel, consumer, producer, …). | Naming shared routes, tools, permissions and screens. | resource class (in UI copy), type |
| kind | A system's own resource type with a role, written `<system>.<name>` (`artemis.queue`). | Filtering by a native type, or showing which native type a row is. | kind as a synonym for role |
| kind label | A kind's native name for people (`queue`, `address`, `topic`). | The kind column and badges on shared screens. | inventing a neutral label |
| resource id | A resource's canonical text, `<kind>/<segment>[/<segment>…]` with each segment percent-encoded (`artemis.queue/orders`). | Addressing one resource in REST, MCP, the plugin contract and links. | queue name, address name, FQQN |
| destination | Where messages are sent: an Artemis address, a Kafka topic, a RabbitMQ exchange. | Sending, routing, destination patterns. | address, topic, exchange (outside a kind label) |
| subscription | A backlog consumers read from, which can be browsed, counted, purged and seeked: an Artemis queue, a Kafka group on one topic. | Browsing, message actions, lifecycle, depth and backlog. | queue (outside a kind label) |
| route | An edge that moves messages: a binding, a divert, a bridge, a shovel. | Routing views and route permissions. | divert, bridge (outside a kind label) |
| channel | A lane multiplexed inside a connection: an Artemis session, an AMQP channel. | The shared list of client lanes. | session (outside a kind label) |
| send target | Where a send goes: a destination, or one subscription directly where the system supports it. | Plugin and REST sends. | address::queue |
| message reference | The SDK's handle on one message in one subscription, used to act on it. | Message actions in REST, MCP and plugins. | message id alone |
| custom method | A verb on one resource beyond create, read, update and delete, written `POST …/{id}:<verb>` (AIP-136). | Pause, resume, purge, seek, truncate, reset counters. | action endpoints, `/pause` sub-resources |
| etag | The state stamp of a previewed operation; the held action runs only if the resource still matches it (AIP-154). | The approval gate's `Effect`. | state key |
| resource type | The two roles a plugin permission or a team pattern can scope: destination and subscription. | Plugin `PermissionDef`, team patterns, shares and token grants. | resource kind (old name) |
| broker link | A long-lived sender, receiver or processor lane the broker SDK opens and recovers, with a state (open, recovering, failed, closed) and the node serving it. | Diagnostics, link metrics, recovery. | connection (a link is one use of a connection), session |
| in doubt | The outcome of a send whose confirmation never came back: the message may or may not be on the broker, and a resend may duplicate it. Neither a success nor a failure. | Send results in REST (`202`, `inDoubt`), MCP, the plugin contract (`MessageInDoubtException`) and the audit outcome `IN_DOUBT`. | unknown, maybe sent, failed |
| lag (estimate) | How many records a consumer group has not read yet, summed over its partitions. It is an estimate when a partition has no committed offset or could not be read, shown as `≈ 12 400` with "estimate" in its accessible name, and `Unavailable` with a reason when it cannot be read at all; it is never 0 for unknown. | Group and subscription lists, the backlog column, MCP and REST answers. | exact lag, depth (for a partitioned log) |
| backlog accuracy | How far a subscription's backlog figure can be trusted: `EXACT`, `ESTIMATE` or `UNAVAILABLE`, with a reason and whether a per-partition breakdown can be read. | The backlog column, the estate, the breakdown drawer, `GET …/backlog`. | confidence, quality |
| consumer group | A set of consumers that share the partitions of the topics they read and commit how far they got: a Kafka group. A subscription on a partitioned log is one group reading one topic. | Kafka screens, tools and permissions. | subscription (for the whole group), consumer (for one member) |
| classic group | A consumer group whose members are assigned partitions by the group protocol of the clients, with offsets committed per partition. | The group type column and filter. | old group |
| share group | A Kafka 4 group whose members share the records of a topic without owning partitions; it has no committed offsets, so its offsets cannot be reset and its lag is not a lag. | The group type column; why a reset is disabled. | queue group |
| partition | One ordered log of a topic, read from an offset. A record is addressed by its partition and offset. | Browsing, lag, reset and truncate. | shard |
| offset reset | Moving where a consumer group reads next in each of its partitions, previewed with the records it skips or reads again, and allowed only on an empty group. A custom method, `POST …/groups/{group}/offsets:reset`. | The reset flow, REST, MCP and the audit. | seek (for a group), rewind |
| truncate | Deleting a destination's records before a cut, an offset per partition or a time, for every reader. A custom method, `POST …/{id}:truncate`, gated by `destination:truncate`; it differs from purge, which empties one subscription's unread records. | Kafka topics and any kind with the truncate verb. | purge a topic, trim |
| advertised listener | The address a Kafka broker tells clients to use. It must be reachable from Rahdar, or the connection check names that broker as not reached even though the bootstrap server answered. | Registration and its failures. | broker address, listener URL |
| ZooKeeper mode | A Kafka cluster that keeps its metadata in ZooKeeper instead of KRaft. Rahdar reads its version only as a lower bound and cannot read its controller quorum, which it states as unknown, never as healthy. | Version display, brokers page, health. | legacy mode |
