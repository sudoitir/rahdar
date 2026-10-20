/**
 * Plugin administration (ADR-0099, ADR-0103): upload, review, activate, update, roll back,
 * disable, uninstall and purge, the installer tier and step-up re-authentication that gate them,
 * and the audit trail of every step.
 */
@ApplicationModule(
        displayName = "Plugins",
        allowedDependencies = {
            "feature.messages",
            "feature.queues",
            "feature.sql",
            "kernel.audit",
            "kernel.audit::web",
            "kernel.core",
            "kernel.gate",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.plugin::descriptor",
            "kernel.plugin::host",
            "kernel.plugin::trust",
            "kernel.plugin::validation",
            "kernel.security",
            "kernel.settings",
            "platform.broker",
            "platform.clusters"
        })
package io.github.sudoitir.rahdar.feature.plugins;

import org.springframework.modulith.ApplicationModule;
