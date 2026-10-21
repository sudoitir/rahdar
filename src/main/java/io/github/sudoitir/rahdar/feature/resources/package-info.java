/**
 * Cross-node live views, connection control, and queue and address lifecycle.
 */
@ApplicationModule(
        displayName = "Resources",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.gate",
            "kernel.plugin",
            "kernel.security",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.rahdar.feature.resources;

import org.springframework.modulith.ApplicationModule;
