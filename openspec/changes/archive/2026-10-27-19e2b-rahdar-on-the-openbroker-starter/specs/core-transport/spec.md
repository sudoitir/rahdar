## ADDED Requirements

### Requirement: Broker messaging metrics are recorded once per cluster
Rahdar SHALL record broker messaging metrics (operation durations, sent and consumed messages, processing duration and link recoveries) as meters tagged with the cluster, exported with its other metrics, and SHALL record each operation once. Spans SHALL go to the trace export.

#### Scenario: A send is measured once
- **WHEN** Rahdar sends a message to a cluster with metrics and tracing on
- **THEN** one sent-messages count and one operation duration are recorded, tagged with that cluster, and the send is one span

### Requirement: Broker callbacks never carry the caller's context
Rahdar SHALL run broker callbacks (plugin consumers, relays, completions) without the context of the thread that started them, so that an operator's identity or trace never reaches a long-lived consumer.

#### Scenario: A consumer started from an operator's request
- **WHEN** an operator's request starts a plugin consumer and a message arrives later
- **THEN** the handler runs with no security context and no observation from that request

### Requirement: The product's own clusters are the only connections
Rahdar SHALL connect only to clusters registered in the product. Spring configuration for Kafka or Artemis SHALL never create a broker connection.

#### Scenario: Spring Kafka properties are present
- **WHEN** Rahdar starts with `spring.kafka.bootstrap-servers` set
- **THEN** no broker connection exists until a cluster is registered
