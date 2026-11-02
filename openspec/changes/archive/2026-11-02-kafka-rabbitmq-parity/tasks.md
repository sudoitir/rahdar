## 1. Alerts

- [x] 1.1 Seed Cluster degraded on Kafka and RabbitMQ, and the HA set on Artemis
- [x] 1.2 Judge Cluster degraded by the system's own health
- [x] 1.3 Refuse Artemis-only rules on other systems with a 409; disable them in the form with the reason
- [x] 1.4 Add consumer-lag (Kafka), queue-depth (RabbitMQ) and cluster-degraded templates

## 2. Flow

- [x] 2.1 Add Kafka and RabbitMQ flow samplers
- [x] 2.2 Add route kinds for bindings, shovels and federation links; make wildcards and delivery depend on the system
- [x] 2.3 Offer Flow on every system

## 3. SQL console

- [x] 3.1 Query Kafka topics and RabbitMQ streams live, evaluating the WHERE clause in Rahdar
- [x] 3.2 Refuse classic and quorum queues and the message index on Kafka and RabbitMQ, with the reason
- [x] 3.3 Offer the console on every system; show the message index settings on Artemis only

## 4. Wording

- [x] 4.1 Name the cluster's system on a failure whose error does not name it
- [x] 4.2 Head a remedy `broker.xml` only for an Artemis snippet
- [x] 4.3 Name Kafka and RabbitMQ in the tagline, the trademark notice and the API descriptions

## 5. CI

- [x] 5.1 Run real-broker tests per system, against the oldest and newest image of each
