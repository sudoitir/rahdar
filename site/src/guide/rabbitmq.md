---
title: RabbitMQ
description: What Rahdar shows and does for a RabbitMQ cluster - nodes, virtual hosts, bindings, policies, permissions, shovels and federation - and how the shared screens behave on queues, streams and exchanges.
---

# RabbitMQ

Register a RabbitMQ cluster as described below and it joins the estate beside any Artemis and Kafka clusters,
with health judged on the same scale. Its own screens are in the cluster's navigation, and appear only for a
RabbitMQ cluster. The screens every system shares work on RabbitMQ too, with the differences below. Which
RabbitMQ releases are supported is on [Supported broker versions](/guide/supported-versions).

Rahdar's words for RabbitMQ follow the same vocabulary as everywhere else: an **exchange** is a destination, and
a **queue** (classic, quorum or stream) is a subscription. Every kind carries its native name, so a row says
"Quorum queue" or "Stream" rather than a neutral word.

## Register a cluster

Choose RabbitMQ on **Clusters → Register cluster** (`/clusters/new?system=rabbitmq`). You give:

- **Management URLs**, one or more, such as `https://rabbit-1:15671`. Rahdar tries them in order. A path prefix is
  allowed, and a URL never carries a user name or password.
- **AMQP endpoints**, optional, such as `amqps://rabbit-1:5671`. They are what browsing a stream, sending a
  message and plugin deliveries use. A cluster registered without them is managed but has no data plane.
- **One account**, used for both. A user name and password, or a token (Rahdar sends it as a bearer token on the
  management API and as the password, with an empty user name, over AMQP, which is how RabbitMQ's OAuth 2 plugin
  takes it).
- A **TLS bundle** if the certificates are not trusted by the JVM, the **virtual host of the event exchange**
  (default `/`) and the **watch interval** (default 10 seconds).

**Check connection** verifies every endpoint on its own, so "15672 on rabbit-2 refused" is shown even when
rabbit-1 answered. It reports the lowest node version, whether the cluster has one node or several, each node
by its RabbitMQ name (`rabbit@host`) with whether it is running, its alarms and any network partition, and what
the account may do: management read and write, the event exchange, browsing, shovels, federation and streams,
each with the remedy when it cannot. Registration stops when no management URL answers, and says why:

| The check says | It means | What to do |
| --- | --- | --- |
| Not a RabbitMQ management API | Something answered, but it is not the management plugin. | Enable `rabbitmq_management`, or check the path prefix. |
| Authorization | The account signed in but may not read the cluster. | Give it the `monitoring` tag: `rabbitmqctl set_user_tags <user> monitoring`. |
| Data plane unreachable | AMQP endpoints were given and none accepts a connection. | Fix them, or register without AMQP endpoints to manage only. |
| Version not supported | A node is older than 3.13. | See [Supported broker versions](/guide/supported-versions). |

A cluster where only some endpoints answer registers after a warning that names the ones that did not. A
RabbitMQ cluster is registered once, by the cluster name its nodes report.

## Nodes

**Observe → Nodes** is the landing view. It lists each node with its version, whether it is running, its uptime,
its listeners and its enabled plugins. A **memory or disk alarm** is flagged on the node that raised it, because
it blocks every publisher in the cluster. A **network partition** is flagged on the node that sees one.

## Virtual hosts

**Configuration → Virtual hosts** lists each virtual host with its description, tags, default queue type, queue
and message counts and limits. Create, change and delete one from the page. Every change is **previewed first**;
deleting a virtual host destroys every queue, exchange and message in it, and its preview names them. A delete
is confirmed by pressing and holding, is held for approval where the gate is on, and runs against the state the
preview read: if it changed since, it is refused as stale and you preview again.

## Bindings

**Messaging → Bindings** lists the bindings from an exchange to a queue or to another exchange, with the routing
key and arguments, filtered by virtual host, source or destination. A binding is created and removed through
the same preview, hold and audit.

## Policies

**Configuration → Policies** lists each policy with its pattern, what it applies to, its priority and its
definition, and says how many queues and exchanges the pattern matches now. The preview of a set or clear lists
the matches, **as Rahdar matches them** (a regular expression over the listings), and those that another policy
of higher priority already wins. An operator policy is shown beside the policies and marked.

## Permissions

**Configuration → Permissions** lists each user's configure, write and read patterns per virtual host, and its
topic permissions. Users are listed by name and tags; a password hash never reaches the browser. Setting or
clearing a permission previews the user's rights **before and after**.

## Shovels and federation

**Messaging → Shovels and federation** lists dynamic shovels with their state, source and destination, and
federation links with their upstream and status. Addresses are shown with credentials removed. A dynamic shovel
can be deleted after its preview; a shovel defined in the node's configuration file is refused, with the reason.
Without the plugin the page says it is unavailable and which plugin enables it.

## The shared screens on RabbitMQ

| Screen | On RabbitMQ |
| --- | --- |
| Subscriptions | Classic, quorum and stream queues. Create one from the page: virtual host, name, type with a line on what each type means, durable, arguments, a delivery limit for quorum queues and a maximum age for streams. |
| Destinations | Exchanges, with their type and whether they are durable or internal. Names starting `amq.` cannot be deleted. **Publish** sends through an exchange with a routing key. |
| Consumers, Connections, Channels | Work unchanged. A connection can be closed; a channel cannot, and says so. |
| Metrics, Alerts | The same series and rules as on other systems. |
| Estate, health | See [Health](#health). |

Producers are not listed: RabbitMQ does not expose them. Views that exist only for Artemis or Kafka, such as
topology, flow, consumer groups or ACLs, are not shown for a RabbitMQ cluster. Consumer health in triage still
reads Artemis consumers only and shows that as unknown for RabbitMQ.

### Backlog

A queue's backlog is the number of ready messages. Management statistics are sampled, so it is an **estimate**
and says so. A **stream** keeps every message until retention removes it, so its size is not work waiting: its
backlog is shown as **Unavailable**, with that reason, and never tops a backlog sort. Its size is on the queue's
panel.

### Browsing messages

Only a **stream** can be browsed, because only a stream is a log. It is read from one position, by offset,
starting at the first message or the end, and reading never removes a message. A **classic or quorum queue
cannot be browsed**: RabbitMQ can only hand a queue's messages to a consumer, which would take them from the
queue's other readers, so Rahdar does not do it. The page says so, with the reason, and Send and Purge stay.

### Sending, and `not-routed`

**Send** on a queue publishes through the default exchange and always reaches it. **Publish** on an exchange takes
a routing key. When no queue is bound with a key that matches, RabbitMQ drops the message, and Rahdar says so as
**not routed**: "No queue received the message: the exchange has no binding that matches routing key 'k'.
Nothing was stored." The dialog links to Bindings filtered by that exchange.

### Deleting and purging

Deleting or purging a queue and deleting an exchange act on the whole cluster at once and are previewed with
what they cost (messages lost, consumers cut, bindings removed). Purge keeps unacknowledged messages.

### A resource recreated with the same configuration

RabbitMQ does not tell a recreated resource apart from the one that was there. A preview of a delete, purge or
policy change is bound to the resource's **configuration**, not to its incarnation. If it is deleted and
created again with the same configuration before the change runs, the change applies to the new one. The
confirmation, and the request an approver reads, carry that sentence.

## Health

Health is judged from the last read of the cluster and shown on the same scale as other systems.

| Level | When |
| --- | --- |
| Critical | No management URL answers. A node reports a network partition. A memory or disk alarm is raised. A quorum queue or stream has lost its majority of members. |
| Degraded | A node is not running. A quorum queue or stream is under-replicated. Classic queue mirrors are not synchronised (3.13). AMQP endpoints were given and none accepts a connection. |

What could not be read yet, such as queue replicas before the first read, is a note and never counted as healthy.
A read older than two minutes is shown as unknown, with when it was taken. If the cluster now reports a different
name than the one registered, health stops and says so; save the connection again to claim it if it was renamed.

## Permissions

| Permission | Allows | Built-in roles |
| --- | --- | --- |
| `namespace:read` | See virtual hosts. | Viewer, operator, admin |
| `namespace:write` | Create and change virtual hosts. | Operator, admin |
| `namespace:delete` | Delete a virtual host. | Admin |
| `policy:read` | See policies. | Viewer, operator, admin |
| `policy:write` | Set and clear policies. | Operator, admin |
| `access-rule:read` | See users and permissions. | Viewer, operator, admin |
| `access-rule:write` | Set and clear permissions. | Admin |

Creating a queue or exchange, and publishing, use `subscription:create`, `destination:create` and the send
permission, as on other systems. Bindings and shovels use `route:write`. See
[Teams and permissions](/guide/teams-and-permissions) for how roles carry them.

## Plugins the cluster needs

| Plugin | Needed for | Without it |
| --- | --- | --- |
| `rabbitmq_management` | Everything. **Required.** | The check fails with "not a RabbitMQ management API". |
| `rabbitmq_event_exchange` | Live notifications. Optional. | Events are found by polling on the watch interval instead. |
| `rabbitmq_shovel` and `rabbitmq_shovel_management` | The shovels list. Optional. | The page says shovels are unavailable and names the plugins. |
| `rabbitmq_federation` and `rabbitmq_federation_management` | Federation links. Optional. | The page says federation is unavailable and names the plugins. |
| `rabbitmq_stream` | Browsing streams. Optional. | Streams cannot be browsed; the registration check says why. |

## MCP

An assistant can read a RabbitMQ cluster with six read-only tools: `rabbitmq_nodes`, `rabbitmq_list_vhosts`,
`rabbitmq_list_bindings`, `rabbitmq_list_policies`, `rabbitmq_list_permissions` and `rabbitmq_list_shovels`
(which also lists federation links). They are offered only to a key that can read a RabbitMQ cluster. Changes
stay in the UI and the API. See [MCP](/guide/mcp).
