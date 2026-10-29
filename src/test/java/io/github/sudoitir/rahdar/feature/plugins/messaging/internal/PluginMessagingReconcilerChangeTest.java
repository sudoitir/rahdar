package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeRepository;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationRepository;
import io.github.sudoitir.rahdar.feature.plugins.messaging.PluginMessagingProperties;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A change whose cluster lock is not free in time is still made, and left to the next scheduled pass. */
class PluginMessagingReconcilerChangeTest {

    @Test
    void aChangeIsMadeWithoutThePassWhenTheLockIsNotFreeInTime() {
        UUID cluster = UUID.randomUUID();
        ClusterLock lock = mock(ClusterLock.class);
        when(lock.runWhenFree(eq(cluster), eq(ClusterLock.Scope.PLUGIN_MESSAGING), eq(Duration.ofSeconds(10)), any()))
                .thenReturn(false);
        RegistrationRepository registrations = mock(RegistrationRepository.class);
        PluginMessagingReconciler reconciler = new PluginMessagingReconciler(
                registrations,
                mock(RegistrationNodeRepository.class),
                mock(ClusterDirectory.class),
                mock(BrokerConnections.class),
                lock,
                mock(ClusterOwnership.class),
                mock(RahdarInstance.class),
                mock(PluginTap.class),
                mock(PluginDrains.class),
                mock(PluginHandlers.class),
                mock(AccessCheck.class),
                mock(AuditService.class),
                new PluginMessagingProperties(Duration.ofSeconds(10), 10),
                Clock.systemUTC());
        AtomicInteger made = new AtomicInteger();

        reconciler.change(cluster, made::incrementAndGet);

        assertThat(made).hasValue(1);
        verifyNoInteractions(registrations);
    }
}
