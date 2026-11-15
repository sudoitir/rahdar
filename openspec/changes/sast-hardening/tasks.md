# Tasks

## Actuator metrics

- [x] `/actuator/prometheus`, `/actuator/metrics` and `/actuator/metrics/**` require `settings:read`
- [x] Every other `/actuator/**` path is denied; health and the probes stay public, `refresh` keeps `settings:write`
- [x] `EndpointProtectionTest` proves an anonymous read of both endpoints is `401`
- [x] `site/src/guide/observability.md` shows the scrape credential

## Notification destinations

- [x] `DestinationPolicy` refuses a non-`https` URL, userinfo, and any address that is loopback, any-local,
      link-local, site-local, unique-local, multicast or carrier-grade NAT
- [x] Checked when a channel is saved (`ChannelConfigValidator`), when it is tested
      (`NotificationChannelService`), and before every delivery (`AlertDispatcher`)
- [x] A refused delivery is recorded dead with the reason, and a refused test is a result rather than an error
- [x] `DestinationPolicyTest` covers every refused range, and the validator's tests still pass
- [x] Email keeps its port, security and recipient checks and gains no destination policy

## Kafka topic create

- [x] The create sends `Precondition.none()`; the re-preview under the caller's etag refuses a stale confirmation
- [x] `KafkaTopicCreateRealBrokerTest` creates a topic against a real broker and asserts it exists

## Build

- [x] The OpenTelemetry SDK is raised past the advisory that fails the scan, with its logback appender moved with it
- [x] The plugin template builds against a published Rahdar release
