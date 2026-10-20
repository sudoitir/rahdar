/**
 * What only Apache ActiveMQ Artemis has (ADR-0203): the one place Rahdar reaches the broker SDK's
 * Artemis extension, {@code ArtemisManagement}, and Rahdar's implementations of the system interfaces
 * in {@code platform.broker} (message expiry, counter reset, the default dead-letter destinations).
 * Artemis-only features (broker configuration, setup review, routing) work through {@link
 * io.github.sudoitir.rahdar.system.artemis.ArtemisNodes}. Some shared features still set up Artemis
 * machinery here (diverts, capture and plugin taps, transfer staging, flow routes); {@code
 * BoundaryRulesTest} lists them, and the list only shrinks.
 */
@ApplicationModule(
        displayName = "Artemis",
        allowedDependencies = {"kernel.core", "kernel.plugin", "platform.broker", "platform.clusters", "platform.scrape"
        })
package io.github.sudoitir.rahdar.system.artemis;

import org.springframework.modulith.ApplicationModule;
