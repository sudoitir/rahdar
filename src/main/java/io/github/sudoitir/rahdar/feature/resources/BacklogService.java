package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown.Breakdown;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Where a subscription's backlog sits by partition, for the systems whose subscriptions read a partitioned log. */
@Service
@RequiredArgsConstructor
public class BacklogService {

    private final ResourceTargets targets;
    private final SystemExtensions extensions;
    private final PermissionResolver permissions;

    /**
     * The caller must be able to read the subscription, named by the first segment of its path (a group, a queue).
     *
     * @throws io.github.sudoitir.rahdar.kernel.core.ConflictException when the cluster's system has no breakdown
     */
    public Breakdown breakdown(UUID clusterId, String kind, String path) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION);
        String subscription = target.id().path().segments().getFirst();
        if (!permissions.filter(clusterId, ResourceType.SUBSCRIPTION).readable(subscription)) {
            throw new NotFoundException("subscription", target.id().format());
        }
        return extensions.require(clusterId, BacklogBreakdown.class).read(clusterId, target.id());
    }
}
