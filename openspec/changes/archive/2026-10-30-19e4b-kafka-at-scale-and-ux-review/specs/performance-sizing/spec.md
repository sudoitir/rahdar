## ADDED Requirements

### Requirement: A Kafka estate at scale stays within its budgets
For a Kafka cluster of about 3,000 partitions and 300 groups, the Kafka list and detail APIs SHALL answer within 500 ms at the 95th percentile. A list asked for more rows than its maximum SHALL answer at most 500 rows and at most 1 MiB. The scrape SHALL make one batched broker round per cycle per cluster for the nodes, one for the partitions, and one subscription sweep per tier-B cycle, independent of the number of rows, SHALL finish within its interval, and SHALL never call a broker once per row. The measured figures SHALL be recorded in the sizing guide and in the Kafka guide, each with the profile, the machine, the version, and the date.

#### Scenario: Measuring a large cluster
- **WHEN** the measurement script runs against the seeded large Kafka cluster
- **THEN** every budget is met and the figures are recorded in the sizing guide and the Kafka guide

### Requirement: Storage growth does not follow message traffic
For a Kafka estate under steady traffic, the metric rows Rahdar stores per hour SHALL be at most one per metric per subscription per heartbeat, and SHALL NOT grow with the number of messages or with a scrape faster than the heartbeat. The broker-event store SHALL stay within its row quota. Every store SHALL keep a bounded retention. The measured growth SHALL be recorded in the sizing guide.

#### Scenario: An hour of heavy traffic
- **WHEN** a seeded estate runs an hour of traffic at thousands of records a second
- **THEN** the metric store grows by at most one row per metric per subscription per heartbeat, the broker-event store stays within its row quota, and the measured growth is recorded

### Requirement: One unreachable cluster does not stall the scrape
A Kafka cluster that never answers SHALL NOT stall the scrape of the other clusters, and the scrape SHALL read a cluster's subscriptions once per tick.

#### Scenario: A blackholed cluster
- **WHEN** one Kafka cluster does not answer a subscription read and another cluster does
- **THEN** the cluster that answers is scraped within the scrape's time limit and the cluster that does not is marked not answering

#### Scenario: Subscriptions are read once
- **WHEN** a tier-B tick scrapes a cluster
- **THEN** that cluster's subscriptions are read once

### Requirement: One scrape tick does not grow with the subscriptions
The server-sent event for one scrape tick SHALL NOT grow with the number of subscriptions on the cluster. Its size on the seeded estate SHALL be recorded in the sizing guide.

#### Scenario: A tick over a large estate
- **WHEN** the measurement records the server-sent events of one scrape tick on the seeded estate
- **THEN** the payload does not grow with the subscription count and the size is recorded
