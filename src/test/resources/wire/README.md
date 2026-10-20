# Wire recordings

`baseline/` holds, per test class, the distinct normalised requests that class's broker tests send:
Jolokia requests with their headers and body, and the Core consumers the broker reports. Compare a new run
with it after any change to how Rahdar reaches a broker, and on every OpenBroker upgrade:

```bash
./mvnw test -Dtest=<broker tests> -Drahdar.wire.record=/path/to/run
scripts/wire-compare.sh /path/to/run
```

Scrape ticks, notification subscriptions and sampled consumers depend on timing, and the broker is shared
between test classes, so read each "missing" line before calling it a change. A "new" line is a change until
it is explained here or the baseline is re-recorded with it.

## The move onto OpenBroker (2026-10-20)

The baseline was re-recorded after Rahdar moved onto the OpenBroker SDK. Compared with the recording taken
before the move, every request still carries the same method, path, headers, HTTP version and credentials.
The bodies differ in these ways, each deliberate:

- **A queue's MBean is found by a search the first time it is used.** Rahdar used to build the name from the
  scraped address and routing type; the driver searches `address=*,routing-type=*,queue="<name>"` once per
  queue and node, keeps the answer, and forgets it when the queue is deleted or reported gone.
- **MBean names list their keys in the broker's order.** The searched name comes back as
  `address=…,broker=…,component=…`; it is the same MBean.
- **A queue the scrape has not reached is found with `listQueues`** filtered by name, instead of a pattern
  read of `MessageCount`.
- **A node's version comes with its HA state.** The register and health probes read `Active`, `Started`,
  `Backup`, `ReplicaSync`, `NodeID`, `Clustered` and `Version` in one read instead of `Version` alone.
- **A filtered browse may run over Core** as a browse-only consumer, with the count from `countMessages`; the
  driver chooses the channel.
- **Classes that moved:** the Core transport, notification and TLS tests moved into OpenBroker with the Core
  code; `BrokerAccountRolesBrokerTest` and `TransferPrimitivesTest` moved to `system.artemis`.
