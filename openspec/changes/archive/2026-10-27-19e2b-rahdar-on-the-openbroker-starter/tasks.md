Rules: narrow tests only (`systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- ./mvnw -o test -Dtest=…`), at most two heavy commands at once; read narrowly; commits per `.claude/rules/05-commits.md`.

## 1. Build
- [x] 1.1 Raise `openbroker.version` to `0.4.0` and add `openbroker-spring-boot-starter`. Check: `./mvnw -q -DskipTests compile`

## 2. Wiring
- [x] 2.1 Remove `BrokerDriversConfiguration` and `BrokerTls`; `BrokerGateway` uses `OpenBrokerConnectOptions` and `SslBundleTls`, and `RahdarCallInterceptor` is the `CallInterceptor` bean. Check: the gateway tests
- [x] 2.2 A `ConnectOptionsCustomizer` applies `BrokerTimeouts` and identity propagation. Check: a test that the options carry the live timeouts and run a callback without the caller's context
- [x] 2.3 Remove `WithoutPropagation`; `BrokerTelemetry` keeps the `BrokerLinks` listener joined with the starter's. `application.yml` sets `openbroker.tracing.inject-context: false` and `openbroker.spring-connections.enabled: false`, and the spool directory. Check: the telemetry test (a send is a span, the message is untouched, the meters are tagged with the cluster) and a context test that `spring.kafka.bootstrap-servers` creates no connection

## 3. Documents
- [x] 3.1 ADR-0212, the stale Jolokia wording of the broker timeouts in `BrokerProperties`, `application.yml` and Settings, and the `19e4-kafka` proposal's dependencies. Check: `openspec validate 19e2b-rahdar-on-the-openbroker-starter --strict`
