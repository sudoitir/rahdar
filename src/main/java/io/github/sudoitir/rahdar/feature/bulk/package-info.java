/**
 * Bulk queue operations: one operation over a set of queues frozen at preview, executed one queue at a time
 * through the single-queue commands (ADR-0093).
 */
@ApplicationModule(
        displayName = "Bulk operations",
        allowedDependencies = {
            "feature.messages",
            "feature.resources",
            "feature.resources :: web",
            "kernel.audit",
            "kernel.core",
            "kernel.gate",
            "kernel.jobs",
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.replica",
            "kernel.security",
            "kernel.settings",
            "kernel.stream",
            "platform.broker",
            "platform.clusters"
        })
package io.github.sudoitir.rahdar.feature.bulk;

import org.springframework.modulith.ApplicationModule;
