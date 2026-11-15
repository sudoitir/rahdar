# ADR-0228: The actuator's metrics take the installation-wide settings read permission

- **Status**: accepted
- **Date**: 2026-11-15
- **Deciders**: Mahdi Amirabdollahi
- **Related**: [ADR-0047](0047-two-configuration-planes.md), [ADR-0113](0113-plugins-publish-metrics-through-metric-sources.md), [ADR-0038](0038-dynamic-permissions-and-scope-walk.md)

## Context

`/actuator/prometheus` and `/actuator/metrics` were exposed on the application port and fell through to
`anyRequest().permitAll()`, so anyone who could reach the port read them without signing in. The series are
tagged with every cluster's id and every destination's name (`rahdar_rr_latency_seconds{cluster,address}`, job
and broker meters by node), so they answer questions the scope walk exists to refuse: a caller with a grant on
one queue reads the name and traffic of all of them. That is enough to map a multi-tenant deployment and to pick
a target for a later message injection or a denial of service.

## Decision

1. **`/actuator/prometheus`, `/actuator/metrics` and `/actuator/metrics/**` require `settings:read`**, through the
   same `PermissionResolver` the rest of the application uses. A scraper presents a bearer API token that holds
   it.
2. **They stay on the application port.** A separate management port is not introduced: with
   `management.server.port` set, Boot runs the actuator in a child context, the application's
   `SecurityFilterChain` does not apply there, and a fresh auto-configured chain would make `/actuator/refresh`
   anonymous again unless the rule were duplicated. The probe paths would move too, taking the container
   healthchecks, the compose files, the HAProxy check and the failover script with them.
3. **Every other `/actuator/**` path is denied.** Health and the probes stay public because a load balancer and
   an orchestrator carry no credential; `refresh` keeps its `settings:write` rule; nothing else is served.

## Consequences

- A scrape without a token gets 401, and a token without `settings:read` gets 403. The leak the assessment
  describes is closed, for both endpoints, on the only port that serves them.
- Prometheus needs a credential. `site/src/guide/observability.md` documents the `authorization` block, and a
  token rotation reaches the scraper through its credentials file.
- The metrics no longer work as an unauthenticated health signal for an external uptime check. `/livez` and
  `/readyz` remain public and are what such a check should use.
- An operator whose only reason to read metrics is dashboards must now hold, or lend a token, `settings:read`.
  That coupling is deliberate: the data is installation-wide, so the permission is too.
- The separate-port option is still available to a deployment that wants network isolation; the gate stays as
  defence in depth when it is added.

## Alternatives considered

- **Only moving to a management port.** The report's suggestion, and it would leave the endpoints unauthenticated
  for anyone on that network segment, while reopening `refresh` in the child context unless carefully duplicated.
- **Removing `metrics` from the exposure.** `/actuator/metrics` is what an operator reads when a dashboard
  disagrees with reality. Gating costs one rule; removing costs a diagnostic.
- **Spring authorities instead of Rahdar permissions.** It would put a second authorization model beside the
  scope walk. Using `settings:read` keeps one model and one audit path for a refusal.
