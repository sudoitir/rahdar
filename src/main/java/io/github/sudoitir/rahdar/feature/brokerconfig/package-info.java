/**
 * Declared broker configuration, drift, apply and config diff.
 */
@ApplicationModule(
        displayName = "Broker configuration",
        allowedDependencies = {
            "feature.alerting",
            "feature.queues",
            "kernel.audit",
            "kernel.core",
            "kernel.jobs",
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.mcp",
            "platform.scrape",
            "system.artemis"
        })
package io.github.sudoitir.rahdar.feature.brokerconfig;

import org.springframework.modulith.ApplicationModule;
