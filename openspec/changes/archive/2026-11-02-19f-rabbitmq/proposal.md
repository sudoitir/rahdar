## Why
RabbitMQ is the most widely run AMQP broker. An estate that runs it next to Artemis and Kafka should see all three
in one place, with the same honest gating, previews, approvals and audit. OpenBroker 0.9.0 brings a RabbitMQ
driver; Rahdar registers its clusters, shows them on the shared screens through their kinds, and adds the screens
only RabbitMQ has.

## What Changes
- Register a RabbitMQ cluster from its management URLs and, optionally, its AMQP endpoints, with one account;
  the connection check reaches every management URL and every AMQP endpoint and names each failure.
- A `system/rabbitmq` module and a `features/rabbitmq` screen set: nodes, virtual hosts, bindings, policies,
  permissions, shovels and federation links. Every change is previewed, held, gated by the approval policy and
  audited.
- On the shared screens, exchanges are destinations, the three queue types are subscriptions, and consumers,
  connections and channels are clients. Browse is offered on streams only; a classic or quorum queue says why and
  is never read.
- A cluster read as one (Kafka, RabbitMQ) is changed as one: the shared delete, purge and bulk paths run through
  the driver's whole-cluster operations, which also lets Kafka topics be deleted from the shared screens.
- **BREAKING**: the gated parameters of `subscription.delete`, `subscription.purge` and `destination.delete` carry
  the resource id; the approval request shows whether a recreated resource is told apart.
- A send that no queue receives fails `not-routed`.

## Capabilities
### New Capabilities
- `system-rabbitmq`: registering and managing RabbitMQ clusters.
### Modified Capabilities
- `cluster-registration`, `queue-lifecycle`, `message-operations`, `estate-view`, `authorization`,
  `operator-ui`, `plugin-messaging`, `mcp-server`.

## Out of scope
- Creating shovels, federation upstreams and users (they carry secrets the SDK cannot yet accept write-only).
- Virtual host scoping in team access patterns.
- RabbitMQ write tools in MCP.
