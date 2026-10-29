# ADR-0213: A registration change is converged under the cluster lock before the call returns

- **Status**: accepted
- **Date**: 2026-10-28
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0111](0111-plugin-scoped-beans-and-plugin-messaging.md): "a registration takes effect on the next pass"

## Context

`PluginMessaging.register` and `unregister` stored the change, then asked for a pass over the cluster
with `ClusterLock.runIfHeld`, which skips when the lock is taken. The lock is taken whenever another pass
is running, on this replica or another: the scheduled pass, or another plugin call's. So a change made
while a pass ran was left to the next scheduled pass, ten seconds away by default. A tap installed that
late never saw the messages sent in between, and a plugin that registers one registration after another
kept the lock busy, so the next calls were skipped too.

`unregister` also deleted the registration outside the lock. A pass already running still held it in its
list, wrote its per-node state, failed on the foreign key, and gave up the whole pass: every other
registration on the cluster waited for the next one.

## Decision

A registration change and the pass that converges it run together under the cluster's
`PLUGIN_MESSAGING` lock (`PluginMessagingReconciler.change`). A caller waits for a pass already running
rather than skipping (`ClusterLock.runWhenFree`), for up to one reconcile interval, the longest the
scheduled pass would have kept it waiting anyway. When `register` returns, the registration is converged on
every serving node; when `unregister` returns, its taps are gone. If the lock is not free within that time
the change is stored without it, a warning says so, and the next scheduled pass converges it, as before.

Stopping a registration's drains stays outside the lock: it waits for the plugin's handlers, and a
handler may itself register or unregister.

## Consequences

- A plugin may rely on a registration being in place when `register` returns, as the per-node state it
  returns already reports. A registration a node refuses is still reported per node, not thrown.
- A pass never writes state for a registration a change removed, so one plugin's `unregister` no longer
  costs every other registration on the cluster a pass.
- `register` and `unregister` can now wait for another pass, typically a second, at most one reconcile
  interval. Each still runs a full pass over the cluster, so a plugin making many changes in a row pays one
  pass per change.

## Alternatives considered

- **Keep skipping, and run the pass again when the running one ends.** A pass on another replica cannot
  be told, and a change made there would still wait for the next scheduled pass.
- **Converge only the changed registration.** The pass also removes orphans and stops drains nobody
  wants; a partial pass would need a second code path for both.
