package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Nodes;
import io.github.sudoitir.openbroker.model.Placement;
import io.github.sudoitir.openbroker.model.PlacementRole;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionEntity;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The scrape keeps a RabbitMQ cluster's stored nodes true and reads its queues in one pass. */
class RabbitMqScrapeTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final NodeStateRecorder recorder = mock(NodeStateRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final RabbitMqHealthStore healthStore = mock(RabbitMqHealthStore.class);
    private final RabbitMqConnectionRepository connections = mock(RabbitMqConnectionRepository.class);
    private final AmqpProbe probe = mock(AmqpProbe.class);
    private final MutableClock clock = new MutableClock();
    private final RabbitMqScrape scrape = new RabbitMqScrape(
            gateway, clusters, recorder, meters, audit, actors, clock, healthStore, connections, probe);
    private final Resources resources = mock(Resources.class);

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static ClusterNode stored(String name) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(UUID.randomUUID());
        when(node.getNativeNodeId()).thenReturn(name);
        when(node.getName()).thenReturn(name);
        return node;
    }

    private void storedAre(ClusterNode... nodes) {
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(nodes));
    }

    private static NodeDescription rabbit(String name, String clusterName) {
        return new NodeDescription(
                NodeId.of(name),
                name,
                new Membership(Optional.ofNullable(clusterName), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(Endpoint.of(Plane.DATA, "amqp", name + ":5672")),
                Optional.empty(),
                List.of(),
                Attributes.empty(),
                Optional.empty());
    }

    private static NodeDescription rabbit(String name) {
        return rabbit(name, "rabbit");
    }

    private void clusterReports(NodeDescription... nodes) {
        ClusterConnection connection = mock(ClusterConnection.class);
        Nodes described = mock(Nodes.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.nodes()).thenReturn(described);
        when(connection.resources()).thenReturn(resources);
        when(described.describe(Scope.whole()))
                .thenReturn(Partial.complete(
                        List.of(nodes),
                        Arrays.stream(nodes).map(NodeDescription::node).toList()));
    }

    private ClusterHealth lastVerdict() {
        ArgumentCaptor<ClusterHealth> verdict = ArgumentCaptor.forClass(ClusterHealth.class);
        verify(healthStore, atLeastOnce()).write(verdict.capture(), any());
        return verdict.getAllValues().getLast();
    }

    @Test
    void aNodeThatJoinedIsAddedByItsNativeNameAndAddress() {
        ClusterNode a = stored("rabbit@a");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a));
        clusterReports(rabbit("rabbit@a"), rabbit("rabbit@b"));

        scrape.nodes(CLUSTER, 7);

        verify(recorder).applyTierA(eq(a.getId()), any(), eq(7L));
        verify(recorder)
                .adopt(CLUSTER, "rabbit@b", "rabbit@b", List.of(Endpoint.of(Plane.DATA, "amqp", "rabbit@b:5672")));
    }

    @Test
    void aNodeTheClusterNoLongerListsIsMarkedAndKeepsItsLastState() {
        ClusterNode a = stored("rabbit@a");
        ClusterNode c = stored("rabbit@c");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a, c));
        clusterReports(rabbit("rabbit@a"));

        scrape.nodes(CLUSTER, 8);

        verify(recorder).recordNodeError(eq(c.getId()), any(), eq(BrokerConnectionException.Kind.UNREACHABLE));
        verify(recorder, never()).applyTierA(eq(c.getId()), any(), anyLong());
        verify(recorder, never()).adopt(any(), any(), any(), any());
    }

    @Test
    void aNodeTheClusterStopsListingIsRetiredAfterTheGracePeriodAndItsLeavingIsAudited() {
        ClusterNode a = stored("rabbit@a");
        ClusterNode c = stored("rabbit@c");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a, c));
        clusterReports(rabbit("rabbit@a"));
        AuditEvent event = mock(AuditEvent.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(event);

        scrape.nodes(CLUSTER, 1);
        clock.advance(RabbitMqScrape.RETIRE_AFTER.minusSeconds(1));
        scrape.nodes(CLUSTER, 2);
        verify(recorder, never()).retire(any());

        clock.advance(Duration.ofSeconds(1));
        scrape.nodes(CLUSTER, 3);

        verify(recorder).retire(c.getId());
        verify(recorder, never()).retire(a.getId());
        verify(audit)
                .begin(
                        any(),
                        eq("NODE_LEFT_CLUSTER"),
                        eq("NODE"),
                        eq("rabbit@c"),
                        eq(CLUSTER),
                        any(),
                        any(),
                        eq(false));
        verify(audit).succeed(event, 1);
    }

    @Test
    void aFailedRetirementIsAuditedAsFailedAndThrown() {
        ClusterNode a = stored("rabbit@a");
        ClusterNode c = stored("rabbit@c");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a, c));
        clusterReports(rabbit("rabbit@a"));
        AuditEvent event = mock(AuditEvent.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(event);
        UUID leaving = c.getId();
        doThrow(new IllegalStateException("db down")).when(recorder).retire(leaving);
        scrape.nodes(CLUSTER, 1);
        clock.advance(RabbitMqScrape.RETIRE_AFTER);

        assertThatThrownBy(() -> scrape.nodes(CLUSTER, 2)).isInstanceOf(IllegalStateException.class);

        verify(audit).fail(event, "db down");
    }

    @Test
    void aNodeListedAgainBeforeTheGracePeriodEndsStartsOverAndAnEmptyAnswerRetiresNothing() {
        ClusterNode a = stored("rabbit@a");
        ClusterNode c = stored("rabbit@c");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a, c));
        clusterReports(rabbit("rabbit@a"));
        scrape.nodes(CLUSTER, 1);
        clock.advance(Duration.ofMinutes(9));
        clusterReports(rabbit("rabbit@a"), rabbit("rabbit@c"));
        scrape.nodes(CLUSTER, 2);
        clock.advance(Duration.ofMinutes(9));
        clusterReports(rabbit("rabbit@a"));
        scrape.nodes(CLUSTER, 3);
        clock.advance(Duration.ofMinutes(20));
        clusterReports();
        scrape.nodes(CLUSTER, 4);

        verify(recorder, never()).retire(any());
    }

    @Test
    void aClusterThatCannotBeReadMarksEveryNodeAndPublishesCritical() {
        ClusterNode a = stored("rabbit@a");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a));
        ClusterConnection connection = mock(ClusterConnection.class);
        Nodes described = mock(Nodes.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.nodes()).thenReturn(described);
        BrokerException refused = new BrokerException(
                Status.UNAVAILABLE,
                ErrorInfo.of(ErrorReasons.UNREACHABLE),
                Dispatch.NOT_SENT,
                NodeId.of("rabbit@a"),
                "connection refused");
        when(described.describe(Scope.whole())).thenThrow(refused);

        assertThatThrownBy(() -> scrape.nodes(CLUSTER, 2)).isInstanceOf(RuntimeException.class);

        verify(recorder).recordNodeError(eq(a.getId()), any(), any());
        assertThat(lastVerdict().level()).isEqualTo(Level.CRITICAL);
    }

    @Test
    void aCycleIsOneDriverReadOfTheWholeClusterWhateverItsSize() {
        clusterReports(rabbit("rabbit@a"), rabbit("rabbit@b"), rabbit("rabbit@c"));
        storedAre(stored("rabbit@a"), stored("rabbit@b"), stored("rabbit@c"));

        scrape.nodes(CLUSTER, 1);
        scrape.nodes(CLUSTER, 2);

        assertThat(meters.get("rahdar.rabbitmq.scrape.rounds")
                        .tag("cluster", CLUSTER.toString())
                        .tag("call", "nodes")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void theHealthVerdictIsWrittenWhenItChangesAndOtherwiseOnlyOnTheHeartbeat() {
        storedAre(stored("rabbit@a"));
        clusterReports(rabbit("rabbit@a"));

        scrape.nodes(CLUSTER, 1);
        scrape.nodes(CLUSTER, 2);
        verify(healthStore, times(1)).write(any(), any());

        clock.advance(RabbitMqScrape.HEALTH_HEARTBEAT);
        scrape.nodes(CLUSTER, 3);
        verify(healthStore, times(2)).write(any(), any());

        clusterReports(rabbit("rabbit@a"), rabbit("rabbit@b"));
        scrape.nodes(CLUSTER, 4);
        verify(healthStore, times(3)).write(any(), any());
    }

    @Test
    void aClusterThatReportsAnotherNameIsNotReadFurtherUntilItIsTheRegisteredOneAgain() {
        ClusterNode a = stored("rabbit@a");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a));
        when(clusters.registeredIdentity(CLUSTER)).thenReturn(Optional.of("rabbit"));
        clusterReports(rabbit("rabbit@a", "other"), rabbit("rabbit@b", "other"));

        scrape.nodes(CLUSTER, 1);

        verify(recorder, never()).applyTierA(any(), any(), anyLong());
        verify(recorder, never()).adopt(any(), any(), any(), any());
        ArgumentCaptor<String> words = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNodeError(eq(a.getId()), words.capture(), eq(BrokerConnectionException.Kind.UNREACHABLE));
        assertThat(words.getValue())
                .contains("it reports cluster name other, registered as rabbit")
                .contains("check and save the connection again");
        assertThat(lastVerdict().level()).isEqualTo(Level.CRITICAL);

        // Another replica reads the same words from the stored node, and the sweep stops on them.
        when(a.getLastError()).thenReturn(words.getValue());
        assertThat(RabbitMqClusterIdentity.recorded(List.of(a))).hasValue(words.getValue());
        assertThatThrownBy(() -> scrape.subscriptions(CLUSTER)).isInstanceOf(IllegalStateException.class);

        when(a.getLastError()).thenReturn(null);
        clusterReports(rabbit("rabbit@a", "rabbit"));
        scrape.nodes(CLUSTER, 2);

        verify(recorder).applyTierA(eq(a.getId()), any(), eq(2L));
    }

    // ---- the AMQP endpoints ----------------------------------------------------------------------------------

    private void amqpEndpoints(String entries) {
        RabbitMqConnectionEntity connection = mock(RabbitMqConnectionEntity.class);
        when(connection.getAmqpEndpoints()).thenReturn(entries);
        when(connections.findById(CLUSTER)).thenReturn(Optional.of(connection));
    }

    @Test
    void endpointsNobodyAcceptsOnWhileManagementAnswersAreAWarningAndProbedOnlyNowAndThen() {
        storedAre(stored("rabbit@a"));
        clusterReports(rabbit("rabbit@a"));
        amqpEndpoints("amqp://mq1:5672,amqp://mq2:5672");
        when(probe.unreachable("mq1:5672")).thenReturn(Optional.of("REFUSED"));
        when(probe.unreachable("mq2:5672")).thenReturn(Optional.of("TIMEOUT"));

        scrape.nodes(CLUSTER, 1);
        scrape.nodes(CLUSTER, 2);

        assertThat(lastVerdict().level()).isEqualTo(Level.DEGRADED);
        assertThat(lastVerdict().notes()).anyMatch(n -> n.contains("mq1:5672 REFUSED, mq2:5672 TIMEOUT"));
        verify(probe, times(1)).unreachable("mq1:5672");

        clock.advance(RabbitMqScrape.DATA_PLANE_PROBE);
        scrape.nodes(CLUSTER, 3);
        verify(probe, times(2)).unreachable("mq1:5672");
    }

    @Test
    void oneEndpointThatAcceptsIsEnoughAndNoEndpointsIsNothingToProbe() {
        storedAre(stored("rabbit@a"));
        clusterReports(rabbit("rabbit@a"));
        amqpEndpoints("amqp://mq1:5672,amqp://mq2:5672");
        when(probe.unreachable("mq1:5672")).thenReturn(Optional.of("REFUSED"));
        when(probe.unreachable("mq2:5672")).thenReturn(Optional.empty());

        scrape.nodes(CLUSTER, 1);

        assertThat(lastVerdict().notes()).noneMatch(n -> n.contains("AMQP"));

        scrape.forget(CLUSTER);
        when(connections.findById(CLUSTER)).thenReturn(Optional.empty());
        scrape.nodes(CLUSTER, 2);
        verify(probe, times(1)).unreachable("mq1:5672");
    }

    // ---- the queues ------------------------------------------------------------------------------------------

    private static Resource queue(String kind, String name, Attributes attributes, Placement... placements) {
        return new Resource(
                ResourceId.of(kind, "/", name),
                attributes,
                List.of(),
                List.of(placements),
                Instant.parse("2026-11-01T10:00:00Z"));
    }

    private static Attributes backlog(long messages) {
        return Attributes.builder()
                .put(MetricKeys.BACKLOG, MetricValue.estimate(messages))
                .build();
    }

    private static Placement member(String node, PlacementRole role, boolean online) {
        return new Placement(NodeId.of(node), Optional.empty(), role, Optional.of(online), Attributes.empty());
    }

    private void queuesAre(Resource... queues) {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(queues), Optional.empty()), List.of()));
    }

    @Test
    void aQueueIsCarriedByTheNodeThatLeadsOrHostsItElseTheFirstStoredNode() {
        ClusterNode a = stored("rabbit@a");
        ClusterNode b = stored("rabbit@b");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(a, b));
        clusterReports(rabbit("rabbit@a"), rabbit("rabbit@b"));
        queuesAre(
                queue(
                        "rabbitmq.classic-queue",
                        "c",
                        backlog(4),
                        Placement.instance(NodeId.of("rabbit@b"), Attributes.empty())),
                queue(
                        "rabbitmq.quorum-queue",
                        "q",
                        backlog(9),
                        member("rabbit@b", PlacementRole.LEADER, true),
                        member("rabbit@a", PlacementRole.FOLLOWER, true)),
                queue("rabbitmq.classic-queue", "x", backlog(1)));

        List<QueueRow> rows = scrape.subscriptions(CLUSTER);

        assertThat(rows).extracting(QueueRow::nodeId).containsExactly(b.getId(), b.getId(), a.getId());
        assertThat(rows).extracting(QueueRow::messageCount).containsExactly(4L, 9L, 1L);
        assertThat(rows.getFirst().attributes()).containsEntry(QueueRow.BACKLOG_PRECISION, "ESTIMATE");
        assertThat(rows.getFirst().attributes().get(QueueRow.BACKLOG_REASON)).contains("sampled");
    }

    @Test
    void aStreamHasNoBacklogAndSaysWhy() {
        storedAre(stored("rabbit@a"));
        clusterReports(rabbit("rabbit@a"));
        queuesAre(queue("rabbitmq.stream", "s", backlog(1_000_000), member("rabbit@a", PlacementRole.LEADER, true)));

        QueueRow stream = scrape.subscriptions(CLUSTER).getFirst();

        assertThat(stream.messageCount()).isNull();
        assertThat(stream.backlogKnown()).isFalse();
        assertThat(stream.attributes())
                .containsEntry(
                        QueueRow.BACKLOG_REASON,
                        "A stream keeps its messages until retention removes them; it has no backlog. Its size is"
                                + " on the queue's panel.");
    }

    @Test
    void theQueuePassFeedsTheNextNodeVerdict() {
        storedAre(stored("rabbit@a"));
        clusterReports(rabbit("rabbit@a"));
        queuesAre(
                queue(
                        "rabbitmq.quorum-queue",
                        "lost",
                        Attributes.empty(),
                        member("rabbit@a", PlacementRole.LEADER, true),
                        member("rabbit@b", PlacementRole.FOLLOWER, false),
                        member("rabbit@c", PlacementRole.FOLLOWER, false)),
                queue(
                        "rabbitmq.quorum-queue",
                        "electing",
                        Attributes.empty(),
                        member("rabbit@a", PlacementRole.LEADER, false),
                        member("rabbit@b", PlacementRole.FOLLOWER, true),
                        member("rabbit@c", PlacementRole.FOLLOWER, true)),
                queue(
                        "rabbitmq.stream",
                        "behind",
                        Attributes.empty(),
                        member("rabbit@a", PlacementRole.LEADER, true),
                        member("rabbit@b", PlacementRole.FOLLOWER, true),
                        member("rabbit@c", PlacementRole.FOLLOWER, false)),
                queue(
                        "rabbitmq.quorum-queue",
                        "unknown",
                        Attributes.empty(),
                        new Placement(
                                NodeId.of("rabbit@a"),
                                Optional.empty(),
                                PlacementRole.LEADER,
                                Optional.empty(),
                                Attributes.empty())),
                queue(
                        "rabbitmq.quorum-queue",
                        "fine",
                        Attributes.empty(),
                        member("rabbit@a", PlacementRole.LEADER, true),
                        member("rabbit@b", PlacementRole.FOLLOWER, true)),
                new Resource(
                        ResourceId.of("rabbitmq.classic-queue", "/", "mirrored"),
                        Attributes.empty(),
                        List.of(new Condition(
                                ConditionTypes.UNDER_REPLICATED, ConditionStatus.TRUE, "", "", Optional.empty())),
                        List.of(),
                        Instant.parse("2026-11-01T10:00:00Z")));

        scrape.subscriptions(CLUSTER);
        scrape.nodes(CLUSTER, 1);

        ClusterHealth verdict = lastVerdict();
        assertThat(verdict.level()).isEqualTo(Level.CRITICAL);
        assertThat(verdict.replicationBehind()).isTrue();
        assertThat(verdict.notes())
                .anyMatch(n -> n.contains("/lost") && n.contains("lost the majority"))
                .anyMatch(n -> n.contains("/electing") && n.contains("no leader online"))
                .anyMatch(n -> n.contains("/behind") && n.contains("under-replicated"))
                .anyMatch(n -> n.contains("1 classic queues have mirrors"))
                .noneMatch(n -> n.contains("/fine") || n.contains("/unknown"));
    }

    @Test
    void theQueuesAreReadAPageAtATimeAndAClusterWithNoStoredNodeIsNotFound() {
        when(clusters.nodes(CLUSTER)).thenReturn(List.of());
        assertThatThrownBy(() -> scrape.subscriptions(CLUSTER)).isInstanceOf(RuntimeException.class);

        storedAre(stored("rabbit@a"));
        clusterReports(rabbit("rabbit@a"));
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(
                        Partial.complete(
                                new ResourcePage(
                                        List.of(queue("rabbitmq.classic-queue", "one", backlog(1))),
                                        Optional.of("next")),
                                List.of()),
                        Partial.complete(
                                new ResourcePage(
                                        List.of(queue("rabbitmq.classic-queue", "two", backlog(2))), Optional.empty()),
                                List.of()));

        assertThat(scrape.subscriptions(CLUSTER)).hasSize(2);
        assertThat(meters.get("rahdar.rabbitmq.scrape.rounds")
                        .tag("call", "subscription-pages")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void forgettingAClusterDropsItsMetersAndWhatItRemembers() {
        meters.counter("rahdar.rabbitmq.scrape.rounds", "cluster", CLUSTER.toString(), "call", "nodes");

        scrape.forget(CLUSTER);

        assertThat(meters.find("rahdar.rabbitmq.scrape.rounds").meters()).isEmpty();
    }
}
