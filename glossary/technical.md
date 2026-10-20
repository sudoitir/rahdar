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
