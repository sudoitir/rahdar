# Notification destinations and actuator metrics

## Why

An assessment of the source found two exposures, and a topic could not be created at all.

- The actuator's `prometheus` and `metrics` endpoints answered without authentication on the application port. Their
  series carry every cluster's id and every destination's name, so a caller limited to one queue could read the name
  and traffic of every other one — the scope walk bypassed by an endpoint nobody had gated.
- A notification channel's destination was checked for a scheme and a host and nothing else, and the server fetches
  it. The test button therefore answered questions about the private network, and posted an attacker-influenced body
  to whatever it reached.
- Creating a Kafka topic was refused with `kafka.topic/<name> does not exist`: the create was sent under a
  precondition the driver enforces by reading the topic that does not exist yet. The unit tests replaced the driver,
  so nothing caught it, and no real-broker test covered a create.

## What Changes

- `/actuator/prometheus` and `/actuator/metrics` require `settings:read`. Health, `/livez` and `/readyz` stay public,
  `/actuator/refresh` keeps `settings:write`, and every other actuator path is denied.
- A notification channel's destination must be `https` and must not resolve to a loopback, private, link-local,
  metadata or carrier-grade NAT address. Checked when the channel is saved, when it is tested, and before every
  delivery, so a channel stored earlier is refused too. Email keeps its existing checks: an on-premise relay is
  internal by design.
- Creating a Kafka topic sends no precondition. Its preview etag describes a state that does not exist, so it guards
  nothing, and the re-preview under the caller's etag is what refuses a stale confirmation.

## Impact

- Specs: `alerting`, `authorization`.
- Code: `kernel/security` (the actuator rules), `feature/alerting` (the destination policy and its three call sites),
  `system/kafka` (the create), and the observability guide.
- ADRs: [0227](../../docs/adr/0227-a-notification-destination-is-checked.md),
  [0228](../../docs/adr/0228-the-actuator-metrics-take-settings-read.md).
- An installation that scrapes Rahdar now needs a token holding `settings:read`; a channel pointed inside the network
  stops delivering until it is repointed.
