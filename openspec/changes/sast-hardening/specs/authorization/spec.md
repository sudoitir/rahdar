## ADDED Requirements

### Requirement: Installation-wide metrics take an installation-wide permission

The system SHALL serve `/actuator/prometheus`, `/actuator/metrics` and `/actuator/metrics/**` only to a caller
holding `settings:read`, because their series name every cluster and every destination and therefore describe more
than any cluster-scoped grant allows. The health endpoints, `/livez` and `/readyz` SHALL stay reachable without
authentication, `/actuator/refresh` SHALL require `settings:write`, and every other `/actuator/**` path SHALL be
refused.

#### Scenario: An anonymous metric read is refused

- **WHEN** a request for `/actuator/prometheus` arrives without a session or a token
- **THEN** the response is `401`

#### Scenario: A token without the permission is refused

- **WHEN** a request for `/actuator/metrics` arrives with a token that does not hold `settings:read`
- **THEN** the response is `403`

#### Scenario: A scraper holding the permission reads the metrics

- **WHEN** a request for `/actuator/prometheus` arrives with a token that holds `settings:read`
- **THEN** the metrics are served

#### Scenario: Health and the probes need no credential

- **WHEN** `/actuator/health`, `/livez` or `/readyz` is requested without a session or a token
- **THEN** the response is `200`
