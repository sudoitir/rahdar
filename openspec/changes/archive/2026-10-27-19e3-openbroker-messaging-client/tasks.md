Rules:
- Heavy commands run in `systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G -- …`, at most two at once.
- Read narrowly. Run only the tests a step touches; CI runs the rest.

## 1. Dependency raise
- [x] 1.1 Raise `openbroker.version` to `0.3.0`, add `openbroker-opentelemetry`, and migrate every call site in the design's table. Check: `BrokerRelayTest`, `RelayedMessagesTest`, `CaptureConsumerTest`, `BrokerMessagesTest`, `TimestampSelectorBrokerTest`, `DataPlaneCheckTest`, `DataPlaneTlsPerClusterTest`

## 2. Plugin consumers on processors
- [x] 2.1 `PluginDrains` opens SDK processors (lanes, group ordering, one unsettled message per lane, system retry, counted release for a rejection) and stops without waiting on itself. Check: the plugin messaging test classes pass unchanged, plus a self-stop test that fails without the guard

## 3. Relay, capture and sends
- [x] 3.1 `BrokerRelay` and `CaptureConsumer` on the transacted guarantee; capture carries on through an aborted or in-doubt commit. Check: the relay and capture tests
- [x] 3.2 An unconfirmed send is in doubt in REST (202), the console, MCP, the plugin API and the audit (`IN_DOUBT`). Check: `BrokerMessagesInDoubtTest`, `MessageServiceTest`, `PluginSendInDoubtTest`, `AuditServiceTransactionTest`, `MessageMutationControllerTest`, the web send-dialog and audit tests, the OpenAPI snapshot

## 4. Recovery and telemetry
- [x] 4.1 `BrokerLinks` records link state with the node and owner as health, metrics and logs. Check: `BrokerLinksRecoveryTest` cuts the connection under a processor
- [x] 4.2 The SDK's OpenTelemetry observer, never writing into a message. Check: `BrokerTelemetryTest`

## 5. Finish
- [x] 5.1 ADR-0211; the plugin, diagnostics and observability guides; the glossary. Check: `openspec validate --strict`
- [x] 5.2 Push; CI green and the Sonar list empty. Check: the PR's checks
