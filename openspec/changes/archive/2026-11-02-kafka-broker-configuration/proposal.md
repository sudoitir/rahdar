# Kafka broker configuration

## Why

Operators could see and change a Kafka topic's configuration in Rahdar, but not a broker's. Tuning a dynamic broker
setting, or checking which value a broker runs with and where it comes from, meant going back to the Kafka CLI.

## What Changes

- Each broker's configuration, and the cluster-wide default, can be viewed: each value with its source, and the
  settings that only take effect after a restart.
- A dynamic setting can be changed. The change is previewed as a diff and confirmed, then applied with the preview's
  etag, held by the approval gate like other changes, and audited.
- A setting that only changes on a restart is refused with the reason, and a secret setting is refused.

## Impact

- Spec: `system-kafka`.
- Code: `system/kafka` (broker configs, gated operation `broker.update-config`) and the web `kafka` feature (broker
  configuration view).
- Needs OpenBroker 0.10.0 (`kafka.broker-config`).
