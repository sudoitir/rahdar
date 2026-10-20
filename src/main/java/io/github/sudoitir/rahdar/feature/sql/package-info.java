/**
 * SQL over queues, the message index and message capture.
 */
@ApplicationModule(
        displayName = "SQL console",
        allowedDependencies = {
            "feature.messages",
            "feature.queues",
            "feature.routing",
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
            "platform.scrape"
        })
package io.github.sudoitir.rahdar.feature.sql;

import org.springframework.modulith.ApplicationModule;
