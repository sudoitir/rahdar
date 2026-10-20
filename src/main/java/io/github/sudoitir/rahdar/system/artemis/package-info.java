/**
 * What only Apache ActiveMQ Artemis has (ADR-0203): the one place Rahdar reaches the broker SDK's
 * Artemis extension, {@code ArtemisManagement}. Artemis-only features (broker configuration, setup
 * review, diverts and bridges, the capability probe and its broker.xml remedies) work through {@link
 * io.github.sudoitir.rahdar.system.artemis.ArtemisNodes}; shared features never do.
 */
@ApplicationModule(
        displayName = "Artemis",
        allowedDependencies = {"kernel.core", "kernel.plugin", "platform.broker", "platform.clusters", "platform.scrape"
        })
package io.github.sudoitir.rahdar.system.artemis;

import org.springframework.modulith.ApplicationModule;
