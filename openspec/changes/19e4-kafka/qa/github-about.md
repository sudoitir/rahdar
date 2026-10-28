# GitHub "About" for sudoitir/rahdar (proposed)

Applied by the lead after the merge, with `gh repo edit`. Nothing here has been applied.

## Description (229 characters)

> Open-source operations console for Apache Artemis and Apache Kafka: every cluster in one estate, consumer lag shown as the estimate it is, previews before anything destructive, and an approval gate for people and AI assistants.

Current: "Cluster-wide management and observability for Apache ActiveMQ Artemis".

## Homepage

`https://rahdar.dev` (unchanged)

## Topics (19)

Kept: `activemq-artemis`, `activemq`, `artemis`, `messaging`, `monitoring`, `observability`, `mcp`,
`self-hosted`, `sre`, `java`

Added: `kafka`, `apache-kafka`, `kafka-ui`, `kafka-console`, `kafka-consumer-lag`, `message-broker`,
`broker-management`, `operations-console`, `mcp-server`

Removed: `artemis-mcp` and `activemq-mcp` (too narrow now that the MCP server covers Kafka; `mcp` and
`mcp-server` cover them).

Each added topic describes something that ships: a Kafka console with consumer-group lag, offset reset,
topic configuration, ACLs and transactions; management of brokers across two systems; an MCP server.
RabbitMQ is not a topic until it ships.

## Command

```bash
gh repo edit sudoitir/rahdar \
  --description "Open-source operations console for Apache Artemis and Apache Kafka: every cluster in one estate, consumer lag shown as the estimate it is, previews before anything destructive, and an approval gate for people and AI assistants." \
  --homepage https://rahdar.dev \
  --add-topic kafka,apache-kafka,kafka-ui,kafka-console,kafka-consumer-lag,message-broker,broker-management,operations-console,mcp-server \
  --remove-topic artemis-mcp,activemq-mcp
```
