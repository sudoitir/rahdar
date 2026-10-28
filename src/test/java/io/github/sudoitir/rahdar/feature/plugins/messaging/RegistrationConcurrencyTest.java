package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeRepository;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationRepository;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.AccessCheck;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginDrains;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionTemplate;

/** A registration's concurrency is checked before anything is stored, audited or looked up (ADR-0112). */
class RegistrationConcurrencyTest {

    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final PluginMessagingService service = new PluginMessagingService(
            registrations,
            mock(RegistrationNodeRepository.class),
            mock(AccessCheck.class),
            mock(PluginMessagingReconciler.class),
            mock(PluginDrains.class),
            mock(ClusterDirectory.class),
            mock(ClusterKinds.class),
            mock(BrokerMessages.class),
            audit,
            mock(ActorResolver.class),
            mock(TransactionTemplate.class),
            Clock.systemUTC());

    @ParameterizedTest
    @CsvSource({
        "CONSUME, 0, 'concurrency is 1 to 32 messages at once per node, not 0'",
        "CONSUME, 33, 'concurrency is 1 to 32 messages at once per node, not 33'",
        "CONSUME, -1, 'not -1'",
        "TAP, 2, 'its concurrency is 1, not 2'",
        "TAP, 0, 'its concurrency is 1, not 0'"
    })
    void anOutOfRangeConcurrencyIsRefusedWithTheReason(RegistrationMode mode, int concurrency, String reason) {
        RegistrationSpec spec = new RegistrationSpec(
                "work",
                UUID.randomUUID(),
                SubscriptionId.of("artemis.queue", "ORDERS"),
                mode,
                concurrency,
                UUID.randomUUID());
        assertThatThrownBy(() -> service.register("acme-x", spec))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining(reason);
        verifyNoInteractions(registrations, audit);
    }
}
