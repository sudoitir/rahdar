package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeRepository;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationRepository;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.AccessCheck;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginDrains;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.ReservedNames;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plugin messaging refuses a name Rahdar reserves in any segment of a subscription, and a name the cluster's system
 * keeps for itself (a Kafka cluster's internal topics), before anything is stored (ADR-0111).
 */
class PluginReservedNamesTest {

    private static final String KIND = "kafka.group-topic";

    private final UUID cluster = UUID.randomUUID();
    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final ClusterKinds kinds = mock(ClusterKinds.class);
    private final SystemExtensions extensions = mock(SystemExtensions.class);
    private final PluginMessagingService service = new PluginMessagingService(
            registrations,
            mock(RegistrationNodeRepository.class),
            mock(AccessCheck.class),
            mock(PluginMessagingReconciler.class),
            mock(PluginDrains.class),
            clusters,
            kinds,
            extensions,
            mock(BrokerMessages.class),
            audit,
            mock(ActorResolver.class),
            mock(TransactionTemplate.class),
            Clock.systemUTC());

    private void kafkaCluster() {
        when(clusters.cluster(cluster)).thenReturn(Optional.of(mock(RegisteredCluster.class)));
        when(kinds.subscriptionKinds(cluster)).thenReturn(List.of(KIND));
        ReservedNames internal = new ReservedNames() {
            @Override
            public String system() {
                return "kafka";
            }

            @Override
            public Optional<String> reason(String name) {
                return name.startsWith("__")
                        ? Optional.of("'" + name + "' is one of Kafka's internal topics.")
                        : Optional.empty();
            }
        };
        when(extensions.forCluster(cluster, ReservedNames.class)).thenReturn(Optional.of(internal));
    }

    private RegistrationSpec spec(String... path) {
        return new RegistrationSpec(
                "work", cluster, SubscriptionId.of(KIND, path), RegistrationMode.CONSUME, 1, UUID.randomUUID());
    }

    @Test
    void aSystemsInternalTopicIsRefusedWhicheverSegmentNamesIt() {
        kafkaCluster();

        RegistrationSpec offsets = spec("billing", "__consumer_offsets");
        RegistrationSpec shareGroup = spec("__share_group_state", "orders");
        assertThatThrownBy(() -> service.register("acme-x", offsets))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("internal topics");
        assertThatThrownBy(() -> service.register("acme-x", shareGroup))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("internal topics");
        verifyNoInteractions(registrations, audit);
    }

    @Test
    void aNameRahdarReservesIsRefusedInTheTopicSegmentToo() {
        kafkaCluster();

        RegistrationSpec capture = spec("billing", "rahdar.capture.x");
        assertThatThrownBy(() -> service.register("acme-x", capture))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("reserved");
        verifyNoInteractions(registrations, audit);
    }

    @Test
    void aSendToASystemsInternalTopicIsRefusedAndAnOrdinaryOneIsNot() {
        kafkaCluster();
        when(clusters.cluster(any())).thenReturn(Optional.of(mock(RegisteredCluster.class)));

        assertThat(service.sendDenial(cluster, ResourceRef.destination("__transaction_state"), UUID.randomUUID()))
                .get()
                .asString()
                .contains("internal topics");
    }
}
