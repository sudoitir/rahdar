package io.github.sudoitir.rahdar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for Rahdar — a cluster-wide management and observability
 * console for Apache ActiveMQ Artemis.
 *
 * <p>Product layers 0–8 are in place: the Jolokia broker client and capability
 * probe, cluster registration and topology discovery, HA / split-brain
 * detection, cross-node views over SSE, message operations with audit, the Core
 * client and request-reply tracing, metrics and charts, alerting, and
 * governance (auth, RBAC, environments). Planned work is queued as numbered
 * changes in {@code openspec/changes/}; the living specs are under {@code openspec/specs/}.
 */
@SpringBootApplication(
        scanBasePackages = {
            "io.github.sudoitir.rahdar.app",
            "io.github.sudoitir.rahdar.kernel",
            "io.github.sudoitir.rahdar.platform",
            "io.github.sudoitir.rahdar.system"
        })
// The kernel is what every module stands on, so each module's own test bootstraps it (ADR-0069).
@Modulithic(
        sharedModules = {
            "kernel.audit",
            "kernel.core",
            "kernel.gate",
            "kernel.jobs",
            "kernel.plugin",
            "kernel.replica",
            "kernel.security",
            "kernel.settings",
            "kernel.stream"
        })
@ConfigurationPropertiesScan
@EnableScheduling
public class RahdarApplication {

    public static void main(String[] args) {
        SpringApplication.run(RahdarApplication.class, args);
    }
}
