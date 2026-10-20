/**
 * Flow: which clients produce to which addresses, how those route into queues, and who
 * consumes them, with rates sampled only while someone is watching (ADR-0081).
 */
@ApplicationModule(
        displayName = "Flow",
        allowedDependencies = {
            "feature.queues",
            "kernel.core",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters",
            "platform.scrape"
        })
package io.github.sudoitir.rahdar.feature.flow;

import org.springframework.modulith.ApplicationModule;
