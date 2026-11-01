package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.AttributeSchema;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.KindLabel;
import io.github.sudoitir.openbroker.model.Placement;
import io.github.sudoitir.openbroker.model.PlacementRole;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.feature.resources.SubscriptionConfigurationReader.PlacementView;
import io.github.sudoitir.rahdar.feature.resources.SubscriptionConfigurationReader.SubscriptionConfiguration;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueSettings;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionConfigurationReaderTest {

    private static final KindId KIND = KindId.of("test.quorum-queue");
    private static final ResourceId ID = ResourceId.of(KIND.value(), "/", "orders");

    private final UUID cluster = UUID.randomUUID();
    private final Resources resources = mock(Resources.class);
    private final SystemExtensions extensions = mock(SystemExtensions.class);
    private SubscriptionConfigurationReader reader;

    @BeforeEach
    void setUp() {
        ClusterConnection connection = mock(ClusterConnection.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(cluster)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(resources.kinds())
                .thenReturn(List.of(new Kind(
                        KIND,
                        new KindLabel("Quorum queue", "Quorum queues"),
                        ResourceClass.SUBSCRIPTION,
                        Set.of(),
                        Optional.empty(),
                        new AttributeSchema(List.of()),
                        Set.of())));
        when(extensions.forCluster(cluster, QueueSettings.class)).thenReturn(Optional.empty());
        reader = new SubscriptionConfigurationReader(
                mock(QueueLocator.class),
                mock(ClusterDirectory.class),
                gateway,
                extensions,
                mock(ClusterAccessGuard.class));
    }

    private void found(Resource... items) {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(items), Optional.empty()), List.of()));
    }

    @Test
    void aSystemWithoutASettingsReaderReportsTheResourceAndWhereItLives() {
        found(new Resource(
                ID,
                Attributes.builder()
                        .putRaw("test.leader", "rabbit@a")
                        .putRaw("test.delivery_limit", 20L)
                        .putRaw("test.durable", true)
                        .putRaw("test.nested", List.of("ignored"))
                        .build(),
                List.of(),
                List.of(
                        new Placement(
                                new NodeId("rabbit@a"),
                                Optional.empty(),
                                PlacementRole.LEADER,
                                Optional.of(true),
                                Attributes.empty()),
                        new Placement(
                                new NodeId("rabbit@b"),
                                Optional.empty(),
                                PlacementRole.FOLLOWER,
                                Optional.empty(),
                                Attributes.empty())),
                Instant.EPOCH));

        SubscriptionConfiguration read = reader.read(cluster, ID);

        assertThat(read.destination()).isNull();
        assertThat(read.nodes()).isEmpty();
        assertThat(read.kindLabel().singular()).isEqualTo("Quorum queue");
        assertThat(read.nativeAttributes())
                .containsEntry("test.leader", "rabbit@a")
                .containsEntry("test.delivery_limit", "20")
                .containsEntry("test.durable", "true")
                .doesNotContainKey("test.nested");
        assertThat(read.placements())
                .containsExactly(
                        new PlacementView("rabbit@a", "leader", true), new PlacementView("rabbit@b", "follower", null));
    }

    @Test
    void aQueueTheSystemDoesNotListIsNotFound() {
        found();

        assertThatThrownBy(() -> reader.read(cluster, ID)).isInstanceOf(NotFoundException.class);
    }
}
