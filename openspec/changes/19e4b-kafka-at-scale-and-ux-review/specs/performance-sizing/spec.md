## ADDED Requirements

### Requirement: A Kafka estate at scale stays within its budgets
For a Kafka cluster of about 3,000 partitions and 300 groups, the Kafka list and detail APIs SHALL answer within 500 ms at the 95th percentile, the scrape SHALL make a bounded number of batched broker rounds per cycle independent of the number of rows, finish within its interval, and never call a broker once per row. The measured figures SHALL be recorded in the guide.

#### Scenario: Measuring a large cluster
- **WHEN** the measurement script runs against the seeded large Kafka cluster
- **THEN** every budget is met and the figures are recorded

### Requirement: Storage growth does not follow message traffic
For a Kafka estate under steady traffic, the rows Rahdar stores per hour SHALL depend on the number of destinations, subscriptions and intervals, not on the number of messages, and every store SHALL keep a bounded retention.

#### Scenario: An hour of heavy traffic
- **WHEN** a seeded estate runs an hour of traffic at thousands of records a second
- **THEN** the broker-event and metric stores grow by a figure proportional to subscriptions and intervals, and the measured growth is recorded
