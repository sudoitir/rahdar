/**
 * Divert and bridge views, and divert creation and deletion.
 */
@ApplicationModule(
        displayName = "Routing",
        allowedDependencies = {
            "feature.resources",
            "kernel.audit",
            "kernel.core",
            "kernel.plugin",
            "kernel.security",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp"
        })
package io.github.sudoitir.rahdar.feature.routing;

import org.springframework.modulith.ApplicationModule;
