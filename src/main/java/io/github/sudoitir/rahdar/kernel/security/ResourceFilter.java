package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which queues or addresses of one cluster the caller may read, and what else they may do with each,
 * decided once per request for a list. A caller who reads every resource of the cluster through a
 * grant skips the per-name check, so the common administrator pays nothing for it
 * ({@link #everything()}); everyone else is matched name by name against their teams and shares.
 *
 * <p>Built by {@link PermissionResolver#filter}; it holds the principal it was built for, so it must
 * not outlive the request.
 */
@PluginApi
public final class ResourceFilter {

    private final PermissionResolver resolver;
    private final RahdarPrincipal principal;
    private final UUID clusterId;
    private final ResourceType kind;
    private final String readAction;
    private final List<String> actions;
    private final Set<String> wholeCluster;

    ResourceFilter(
            PermissionResolver resolver,
            RahdarPrincipal principal,
            UUID clusterId,
            ResourceType kind,
            String readAction,
            List<String> actions,
            Set<String> wholeCluster) {
        this.resolver = resolver;
        this.principal = principal;
        this.clusterId = clusterId;
        this.kind = kind;
        this.readAction = readAction;
        this.actions = actions;
        this.wholeCluster = wholeCluster;
    }

    /** Whether every resource of the cluster is readable, so no row needs checking. */
    public boolean everything() {
        return wholeCluster.contains(readAction);
    }

    /** Whether the caller may read the resource of this name. */
    public boolean readable(String name) {
        return everything()
                || (name != null && resolver.can(principal, clusterId, new ResourceRef(kind, name), readAction));
    }

    /**
     * The actions of this filter's kind that the caller holds on the named resource, in a stable order,
     * for a row the console gates its buttons from. Meant for the rows of one page, not for a whole list.
     */
    public List<String> allowedActions(String name) {
        ResourceRef ref = new ResourceRef(kind, name);
        return actions.stream()
                .filter(a -> wholeCluster.contains(a) || (name != null && resolver.can(principal, clusterId, ref, a)))
                .toList();
    }
}
