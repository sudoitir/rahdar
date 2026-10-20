/**
 * Request-reply expectations, correlation, latency and timeouts.
 */
@ApplicationModule(
        displayName = "Request-reply tracing",
        allowedDependencies = {
            "feature.messages",
            "feature.sql",
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.lifecycle",
            "kernel.lifecycle::partition",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.governance",
            "platform.mcp",
            "platform.scrape"
        })
package io.github.sudoitir.rahdar.feature.rr;

import org.springframework.modulith.ApplicationModule;
