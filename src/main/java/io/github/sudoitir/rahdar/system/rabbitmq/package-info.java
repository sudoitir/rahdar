/**
 * What only RabbitMQ has (ADR-0223): the one place Rahdar names the RabbitMQ driver's connection shape. RabbitMQ is
 * reached through the broker SDK's operations like any system; this module holds what the SDK does not carry
 * (connection options, registration, health) and the {@code /rabbitmq/} namespace.
 */
@ApplicationModule(
        displayName = "RabbitMQ",
        allowedDependencies = {
            "kernel.core",
            "kernel.plugin",
            "platform.broker",
            "platform.clusters",
            "platform.clusters::web"
        })
package io.github.sudoitir.rahdar.system.rabbitmq;

import org.springframework.modulith.ApplicationModule;
