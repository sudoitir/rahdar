package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog.Accuracy;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A page of groups costs one broker read however many groups it holds; their lag is the scrape's last sample. */
class KafkaGroupsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final QueueSnapshots snapshots = mock(QueueSnapshots.class);
    private final KafkaGroups groups;

    KafkaGroupsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        groups = new KafkaGroups(gateway, mock(ClusterAccessGuard.class), mock(KafkaAdminReads.class), snapshots);
    }

    private static Resource group(String id, long members) {
        return new Resource(
                ResourceId.of("kafka.group", id),
                Attributes.builder()
                        .putRaw(KafkaKinds.GROUP_TYPE, "consumer")
                        .putRaw(KafkaKinds.GROUP_STATE, "stable")
                        .putRaw(KafkaKinds.MEMBERS, members)
                        .build(),
                List.of(),
                List.of(),
                Instant.now());
    }

    private static QueueSnapshot lag(String group, String topic, long records) {
        return new QueueSnapshot(
                CLUSTER,
                UUID.randomUUID(),
                group + "/" + topic,
                topic,
                null,
                true,
                false,
                Instant.now(),
                records,
                0,
                0,
                0,
                0,
                0,
                0,
                "kafka.group-topic",
                Map.of(QueueRow.PATH, group + "/" + topic, QueueRow.BACKLOG_REASON, "an estimate"));
    }

    private static QueueSnapshot noLag(String group, String topic) {
        QueueSnapshot known = lag(group, topic, 0);
        return new QueueSnapshot(
                known.clusterId(),
                known.nodeId(),
                known.queueName(),
                known.address(),
                null,
                true,
                false,
                known.ts(),
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                known.kind(),
                Map.of(QueueRow.PATH, group + "/" + topic));
    }

    @Test
    void aGroupWithNoCommittedOffsetHasNoLagNotZeroLag() {
        List<Resource> page = List.of(group("g1", 0), group("g2", 0));
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(page, Optional.empty()), List.of()));
        when(snapshots.forCluster(CLUSTER))
                .thenReturn(List.of(noLag("g1", "orders"), lag("g2", "orders", 7), noLag("g2", "payments")));

        GroupPage result = groups.list(CLUSTER, null, null, null, null, 10);

        var byId = result.items().stream().collect(Collectors.toMap(r -> r.id(), r -> r));
        assertThat(byId.get("g1").lag().accuracy()).isEqualTo(Accuracy.UNAVAILABLE);
        assertThat(byId.get("g1").lag().value()).isNull();
        assertThat(byId.get("g2").lag().value()).isEqualTo(7);
        assertThat(byId.get("g2").lag().accuracy()).isEqualTo(Accuracy.ESTIMATE);
        assertThat(byId.get("g2").lag().reason()).contains("Excludes 1 of 2 topics");
    }

    @Test
    void aPageOfThreeHundredGroupsIsOneReadAndTheirLagComesFromTheLastSample() {
        List<Resource> page =
                IntStream.range(0, 300).mapToObj(n -> group("g" + n, n % 2)).toList();
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(page, Optional.of("next")), List.of()));
        when(snapshots.forCluster(CLUSTER))
                .thenReturn(List.of(lag("g1", "orders", 10), lag("g1", "payments", 20), lag("g2", "orders", 5)));

        GroupPage result = groups.list(CLUSTER, null, null, null, null, 300);

        verify(resources, times(1)).read(any(ResourceQuery.class));
        verify(resources, never()).read(anyList());
        assertThat(result.items()).hasSize(300);
        assertThat(result.next()).isEqualTo("next");
        var byId = result.items().stream().collect(Collectors.toMap(r -> r.id(), r -> r));
        assertThat(byId.get("g1").lag().value()).isEqualTo(30);
        assertThat(byId.get("g1").lag().accuracy()).isEqualTo(Accuracy.ESTIMATE);
        assertThat(byId.get("g2").lag().value()).isEqualTo(5);
        assertThat(byId.get("g3").lag().accuracy()).isEqualTo(Accuracy.UNAVAILABLE);
    }
}
