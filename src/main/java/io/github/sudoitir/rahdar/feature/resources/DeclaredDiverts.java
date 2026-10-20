package io.github.sudoitir.rahdar.feature.resources;

import java.util.Set;
import java.util.UUID;

/**
 * The divert names a cluster's Rahdar declaration carries (ADR-0067), so a queue delete can
 * say which of the diverts it removes the next apply brings back (ADR-0084 D2).
 *
 * <p>Declared here and implemented by the module that owns the declaration, which already
 * depends on this one: the queues module cannot read the declaration without a cycle.
 */
public interface DeclaredDiverts {

    /** The declared divert names; empty for a cluster with no declaration. */
    Set<String> names(UUID clusterId);
}
