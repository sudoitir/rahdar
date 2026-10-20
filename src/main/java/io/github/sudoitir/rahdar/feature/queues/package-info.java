/**
 * Queue, address and divert lifecycle across a cluster.
 */
@ApplicationModule(
        displayName = "Queues",
        allowedDependencies = {
            "kernel.core",
            "kernel.gate",
            "kernel.plugin",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape",
            "system.artemis"
        })
package io.github.sudoitir.rahdar.feature.queues;

import org.springframework.modulith.ApplicationModule;
