package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.AttributeSchema;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.KindLabel;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Verb;
import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A kind is named for people by its driver, singular and plural, never by a rule of Rahdar's. */
class ClusterKindsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final SystemExtensions extensions = mock(SystemExtensions.class);
    private final ClusterKinds kinds = new ClusterKinds(mock(BrokerSystems.class), gateway, extensions);

    private static Kind kind(String id, ResourceClass resourceClass, KindLabel label) {
        return new Kind(
                KindId.of(id),
                label,
                resourceClass,
                Set.of(Verb.GET),
                Optional.empty(),
                new AttributeSchema(List.of()),
                Set.of());
    }

    private void driverHas(Kind... driverKinds) {
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(connection.resources()).thenReturn(resources);
        when(resources.kinds()).thenReturn(List.of(driverKinds));
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
    }

    @Test
    void kindsCarryTheDriversSingularAndPlural() {
        driverHas(
                kind("kafka.topic", ResourceClass.DESTINATION, KindLabel.of("Topic", "Topics")),
                kind("kafka.group", ResourceClass.SUBSCRIPTION, KindLabel.of("Consumer group", "Consumer groups")));

        assertThat(kinds.kindsOf(CLUSTER))
                .extracting(k -> k.label())
                .containsExactly(new KindName("Topic", "Topics"), new KindName("Consumer group", "Consumer groups"));
    }

    @Test
    void anIdTextIsLabelledByTheClustersKind() {
        driverHas(kind("kafka.group", ResourceClass.SUBSCRIPTION, KindLabel.of("Consumer group", "Consumer groups")));

        ResourceIdTexts.Of ids = new ClusterResourceIdTexts(gateway).of(CLUSTER);

        assertThat(ids.kindLabel("kafka.group/billing")).isEqualTo(new KindName("Consumer group", "Consumer groups"));
        assertThat(ids.kindLabel(null)).isNull();
    }
}
