/**
 * What only Apache Kafka has (ADR-0216): the one place Rahdar names the Kafka driver. Kafka is reached
 * through the broker SDK's operations like any system; this module holds what the SDK does not carry
 * (connection options, registration, health) and the {@code /kafka/} namespace.
 */
@ApplicationModule(
        displayName = "Kafka",
        allowedDependencies = {
            "kernel.core",
            "kernel.plugin",
            "platform.broker",
            "platform.clusters",
            "platform.clusters::web",
            "platform.scrape"
        })
package io.github.sudoitir.rahdar.system.kafka;

import org.springframework.modulith.ApplicationModule;
