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
  read of `MessageCount`. Unpaged lists ask for page 1 of size 2147483647: a page and size of -1 return no
  rows on Artemis 2.33.0.
- **A node's version comes with its HA state.** The register and health probes read `Active`, `Started`,
  `Backup`, `ReplicaSync`, `NodeID`, `Clustered` and `Version` in one read instead of `Version` alone.
- **A filtered browse may run over Core** as a browse-only consumer, with the count from `countMessages`; the
  driver chooses the channel.
- **Classes that moved:** the Core transport, notification and TLS tests moved into OpenBroker with the Core
  code; `BrokerAccountRolesBrokerTest` and `TransferPrimitivesTest` moved to `system.artemis`, and the relay's
  cases moved from `TransferPrimitivesTest` to `BrokerRelayTest`, recorded here since, with two cases it lacked
  before: a run stopped before the target commits, and a full target.

## The lifecycle on the SDK's operations (2026-10-20)

Queue and address lifecycle, queue delete's divert removal, and divert creation and deletion moved onto the
SDK's operations. Every request that changes a broker (`createQueue`, `updateQueue`, `destroyQueue`, `createAddress`,
`deleteAddress`, `createDivert`, `destroyDivert`) is the same as before, byte for byte after the JSON is
canonicalised. What differs is reads, and the name of the queue MBean that `pause` and `resume` address:

- **Reads were added.** A queue delete lists the queues bound to its address with `listQueues` (filtered by
  address) instead of reading `QueueNames` and `ConsumerCount`, and counts messages from the same list instead of a
  `MessageCount` read. A divert's forwarding address is checked with `listAddresses` (filtered by name), and the
  driver reads a divert or a queue before it acts on it.
- **A divert to an address that is not there is refused** even when the broker would create the address on first
  use: the SDK's resource listing has no address settings, so the `getAddressSettingsAsJSON` read is gone.
- **`pause` and `resume` name the queue MBean in the broker's key order** (`address=…,broker=…`), the searched
  name, instead of the order Rahdar built.
- **The classes moved:** `QueueDeleteRealBrokerTest` and `BridgeLifecycleRealBrokerTest` to `feature.resources`,
  `DivertLifecycleRealBrokerTest` to `feature.routing`, and `QueueLifecycleRealBrokerTest` is new, recorded here so
  that create, update, pause, resume and an address's lifecycle have a baseline.
