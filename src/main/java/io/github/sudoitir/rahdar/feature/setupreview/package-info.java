/**
 * Setup review: each cluster's HA, clustering, durability and message-safety configuration,
 * checked against a catalogue of known mistakes over one batched read per node (ADR-0106).
 */
@ApplicationModule(
        displayName = "Setup review",
        allowedDependencies = {
            "feature.alerting",
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
            "system.artemis"
        })
package io.github.sudoitir.rahdar.feature.setupreview;

import org.springframework.modulith.ApplicationModule;
