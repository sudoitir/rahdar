Rules:
- Heavy commands run in `systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- …`, at most two at once.
- Read narrowly. Run only the tests a step touches; CI runs the rest.

## 1. Dependency raise
- [ ] 1.1 Raise `openbroker.version` to `0.2.0` and fix compilation at every call site in the design's table. Check: `./mvnw -q -DskipTests compile`

## 2. Plugin consumers on processors
- [ ] 2.1 `PluginDrains` opens SDK processors (lanes, group ordering, one unsettled message per lane, system retry). Check: the plugin messaging test classes pass unchanged

## 3. Relay, capture, sampling and sends
- [ ] 3.1 `BrokerRelay` and `CaptureConsumer` on the transacted guarantee with the joined sender. Check: the relay and capture tests
- [ ] 3.2 `RrSampler`, `BrokerMessages` and `MessageService` on the new receiver and sender options; in-doubt and resent receipts surface to callers and audit. Check: the message send and browse tests, plus a new in-doubt test

## 4. Recovery and telemetry
- [ ] 4.1 A link listener records state changes with the serving node in diagnostics and metrics. Check: a test that restarts the test broker under a plugin consumer
- [ ] 4.2 The SDK's OpenTelemetry observer when telemetry export is on. Check: the telemetry export test asserts messaging spans

## 5. Finish
- [ ] 5.1 The ADR, and the docs pages that describe plugin consumer behaviour and diagnostics. Check: the docs build
- [ ] 5.2 Push; CI green and the Sonar list empty. Check: the PR's checks

## Workflow follow-up
- Merge, `/opsx:archive`.
