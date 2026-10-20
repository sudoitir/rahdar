/**
 * Cross-node live views and connection control.
 */
@ApplicationModule(
        displayName = "Resources",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.plugin",
            "kernel.security",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape",
            "system.artemis"
        })
package io.github.sudoitir.rahdar.feature.resources;

import org.springframework.modulith.ApplicationModule;
