package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Nodes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyAcquired;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScrapeSchedulerTest {

    private static final String GOOD = "http://good:8161/console/jolokia";
    private static final String BAD = "http://bad:8161/console/jolokia";
    private static final String STALLED = "http://stalled:8161/console/jolokia";

    @Mock
    ClusterDirectory clusters;

    @Mock
    ClusterService clusterService;

    @Mock
    BrokerGateway gateway;

    @Mock
    ClusterConnection connection;

    @Mock
    Nodes nodes;

    @Mock
    Resources resources;

    @Mock
    NodeStateRecorder persist;

    @Mock
    QueueSnapshotUpsert upsert;

    @Mock
    MetricSampleWriter metrics;

    @Mock
    BrokerNotifications coreSubscriptions;

    @Mock
    SettingsService settings;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    ClusterOwnership ownership;

    ScrapeCycle scrapeCycle;
    ScrapeScheduler scheduler;
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ScrapeScheduler.class)).addAppender(logs);
        when(ownership.owns(any())).thenReturn(true);
        when(gateway.cluster(any())).thenReturn(connection);
        when(connection.nodes()).thenReturn(nodes);
        when(connection.resources()).thenReturn(resources);
        scrapeCycle = new ScrapeCycle(persist);
        scheduler = new ScrapeScheduler(
                settings,
                clusters,
                ownership,
                clusterService,
                gateway,
                scrapeCycle,
                persist,
                upsert,
                metrics,
                new StreamSignals(Mockito.mock(SseHub.class)),
                coreSubscriptions,
                eventPublisher);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ScrapeScheduler.class)).detachAppender(logs);
    }

    private static Scope scope(BrokerNodeEntity node) {
        return Scope.node(BrokerGateway.idOf(node.getId()));
    }

    /** A primary that is serving, as one HA read describes it. */
    private static Partial<List<NodeDescription>> primary(BrokerNodeEntity node) {
        NodeId id = BrokerGateway.idOf(node.getId());
        NodeDescription d = new NodeDescription(
                id,
                "node-id-1",
                new Membership(Optional.empty(), Designation.PRIMARY, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(),
                Optional.of("2.57.0"),
                List.of(new Condition(ConditionTypes.STARTED, ConditionStatus.TRUE, "", "", Optional.empty())),
                Attributes.empty(),
                Optional.empty());
        return Partial.complete(List.of(d), List.of(id));
    }

    private static BrokerException failure(Status status, String reason, String message, Map<String, String> metadata) {
        return new BrokerException(status, ErrorInfo.of(reason, metadata), Dispatch.NOT_SENT, null, message);
    }

    private static BrokerException unreachable(String message) {
        return failure(Status.UNAVAILABLE, ErrorReasons.UNREACHABLE, message, Map.of());
    }

    /** A page of {@code count} queues, continuing when {@code next} is present. */
    private static Partial<ResourcePage> queues(int count, Optional<String> next) {
        List<Resource> items = IntStream.range(0, count)
                .mapToObj(i -> new Resource(
                        ResourceId.of("artemis.queue", "q" + i),
                        Attributes.empty(),
                        List.of(),
                        List.of(),
                        Instant.now()))
                .toList();
        return new Partial<>(new ResourcePage(items, next), Map.of(), Map.of());
    }

    private static BrokerNodeEntity node(UUID clusterId, String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(clusterId, name, "PRIMARY", null);
        n.attachSeedUrl(url);
        setId(n, UUID.randomUUID());
        return n;
    }

    private static ClusterEntity cluster(String name) {
        ClusterEntity c = new ClusterEntity(name, null, null);
        setId(c, UUID.randomUUID());
        return c;
    }

    /** The JPA {@code @GeneratedValue} id is only set on persist; these plain-Mockito tests need one up front. */
    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void discoveryRunsForEveryClusterAndOneFailureDoesNotStopAnother() {
        ClusterEntity broken = cluster("broken");
        ClusterEntity fine = cluster("fine");
        when(clusters.clusters()).thenReturn(List.of(broken, fine));
        doThrow(new IllegalStateException("boom")).when(clusterService).rediscover(broken.getId());

        scheduler.discovery();

        verify(clusterService).rediscover(broken.getId());
        verify(clusterService).rediscover(fine.getId());
    }

    @Test
    void tierARefreshesEachManageableNodeOncePerTickWithTheCycleNumber() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity a = node(clusterId, "a", GOOD);
        BrokerNodeEntity b = node(clusterId, "b", GOOD);

        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(a, b));
        when(nodes.describe(scope(a))).thenReturn(primary(a));
        when(nodes.describe(scope(b))).thenReturn(primary(b));

        scheduler.tierA();

        verify(persist, times(2)).applyTierA(any(), any(), eq(1L));
        verify(persist, never()).recordNodeError(any(), anyString(), any());
    }

    @Test
    void tiersAAndBLeaveAClusterThisReplicaDoesNotOwnAlone() {
        when(clusters.owned()).thenReturn(List.of());
        when(clusters.clusters()).thenReturn(List.of(cluster("c")));

        scheduler.tierA();
        scheduler.tierB();

        verify(gateway, never()).cluster(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void takingAClusterOverScrapesItAtOnce() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity a = node(clusterId, "a", GOOD);
        when(clusters.nodes(any())).thenReturn(List.of(a));
        when(nodes.describe(scope(a))).thenReturn(primary(a));

        scheduler.onDutyAcquired(new ClusterDutyAcquired(cluster.getId()));

        verify(persist, timeout(5000)).applyTierA(any(), any(), eq(1L));
        verify(eventPublisher, timeout(5000)).publishEvent(any(ScrapeTierCompleted.class));
    }

    @Test
    void aStalledNodeDoesNotDelayTheProbeOfItsSiblings() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity stalled = node(clusterId, "stalled", STALLED);
        BrokerNodeEntity healthy = node(clusterId, "healthy", GOOD);
        CountDownLatch release = new CountDownLatch(1);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(stalled, healthy));
        when(nodes.describe(scope(stalled))).thenAnswer(call -> {
            release.await();
            return primary(stalled);
        });
        when(nodes.describe(scope(healthy))).thenReturn(primary(healthy));
        try {
            scheduler.tierA();
            scheduler.tierA();

            verify(persist).applyTierA(eq(healthy.getId()), any(), eq(1L));
            verify(persist).applyTierA(eq(healthy.getId()), any(), eq(2L));
            verify(nodes, times(1)).describe(scope(stalled));
        } finally {
            release.countDown();
        }
        verify(persist, timeout(5000)).applyTierA(eq(stalled.getId()), any(), eq(1L));
    }

    @Test
    void aTierAReadThatFailedInsideAnOkResponseLeavesTheNodeAsItWas() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(nodes.describe(scope(n)))
                .thenThrow(failure(
                        Status.INTERNAL,
                        ErrorReasons.BAD_RESPONSE,
                        "The HA read answered with an error: javax.management.InstanceNotFoundException",
                        Map.of(BrokerFailures.NATIVE_ERROR, "javax.management.InstanceNotFoundException")));

        scheduler.tierA();

        verify(persist, never()).applyTierA(any(), any(), anyLong());
        verify(persist, never()).recordNodeError(any(), anyString(), any());
    }

    @Test
    void aFailedQueueListingLeavesTheNodeReachable() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.clusters()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(resources.read(any(ResourceQuery.class)))
                .thenThrow(
                        failure(Status.INTERNAL, ErrorReasons.BAD_RESPONSE, "Operation listQueues failed", Map.of()));

        scheduler.tierB();
        scheduler.tierC();

        verify(upsert, never()).upsertBatch(any());
        verify(persist, never()).recordNodeError(any(), anyString(), any());
    }

    @Test
    void aFailureToPersistWhatWasScrapedLeavesTheNodeReachable() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(resources.read(any(ResourceQuery.class))).thenReturn(queues(1, Optional.empty()));
        doThrow(new IllegalStateException("database down")).when(upsert).upsertBatch(any());

        scheduler.tierB();

        verify(upsert).upsertBatch(any());
        verify(persist, never()).recordNodeError(any(), anyString(), any());
    }

    @Test
    void aNodeThatKeepsFailingTheSameWayIsLoggedOnceWithItsCauseAndAgainWhenTheReasonChanges() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", BAD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(nodes.describe(scope(n))).thenThrow(unreachable("Nothing is listening at bad:8161."));

        scheduler.tierA();
        scheduler.tierA();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("bad", "Nothing is listening at bad:8161.");

        Mockito.reset(nodes);
        when(nodes.describe(scope(n))).thenThrow(unreachable("The name 'bad' does not resolve."));
        scheduler.tierA();
        scheduler.tierA();
        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1)).contains("does not resolve");
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void losingAClusterDropsItsSubscriptionsCycleAndSignals() {
        UUID clusterId = UUID.randomUUID();
        scrapeCycle.next(clusterId);

        scheduler.onDutyReleased(new ClusterDutyReleased(clusterId));

        assertThat(scrapeCycle.current(clusterId)).isZero();
        verify(coreSubscriptions, timeout(5000)).forget(clusterId);
    }

    @Test
    void aClusterLostWhileATierARanLeavesNoSubscriptionBehind() {
        ClusterEntity cluster = cluster("c");
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(any())).thenReturn(List.of());
        when(ownership.owns(any())).thenReturn(false);

        scheduler.tierA();

        verify(coreSubscriptions).forget(cluster.getId());
    }

    @Test
    void aFailingNodeIsRecordedAndDoesNotAbortItsSiblings() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity good = node(clusterId, "good", GOOD);
        BrokerNodeEntity bad = node(clusterId, "bad", BAD);

        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(bad, good));
        when(nodes.describe(scope(bad))).thenThrow(unreachable("Nothing answered at this address."));
        when(nodes.describe(scope(good))).thenReturn(primary(good));

        scheduler.tierA();

        verify(persist, times(1)).applyTierA(any(), any(), eq(1L));
        verify(persist, times(1)).recordNodeError(any(), anyString(), eq(BrokerConnectionException.Kind.UNREACHABLE));
    }

    @Test
    void aProbeTheBrokerRefusedForItsCredentialsIsRecordedAsThatKind() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity node = node(clusterId, "refused", BAD);

        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(node));
        when(nodes.describe(scope(node)))
                .thenThrow(failure(Status.UNAUTHENTICATED, ErrorReasons.CREDENTIALS_REJECTED, "401", Map.of()));

        scheduler.tierA();

        verify(persist, times(1))
                .recordNodeError(any(), anyString(), eq(BrokerConnectionException.Kind.CREDENTIALS_REJECTED));
    }

    @Test
    void aProbeRahdarHeldBackItselfNeverMarksTheNodeUnreachable() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(nodes.describe(scope(n)))
                .thenThrow(failure(
                        Status.RESOURCE_EXHAUSTED,
                        ErrorReasons.RATE_LIMITED,
                        "Rahdar is calling this node as fast as its rate ceiling allows.",
                        Map.of()));

        scheduler.tierA();

        verify(persist, never()).recordNodeError(any(), anyString(), any());
        assertThat(warnings()).anyMatch(w -> w.contains("calling this node as fast as"));
    }

    @Test
    void aSlowNodesReadStillTakesPartInTheCorroborationOfItsOwnCycle() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity slow = node(clusterId, "slow", STALLED);
        BrokerNodeEntity healthy = node(clusterId, "healthy", GOOD);
        CountDownLatch release = new CountDownLatch(1);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(slow, healthy));
        when(nodes.describe(scope(slow))).thenAnswer(call -> {
            release.await();
            return primary(slow);
        });
        when(nodes.describe(scope(healthy))).thenReturn(primary(healthy));
        try {
            scheduler.tierA();

            // The pass has moved on, but the cycle is not judged while one of its reads is still out.
            verify(persist, never()).recordSplitBrain(any(), any());
            verify(eventPublisher, never()).publishEvent(any(ScrapeTierCompleted.class));
        } finally {
            release.countDown();
        }
        verify(persist, timeout(5000)).recordSplitBrain(eq(cluster.getId()), any());
        verify(eventPublisher, timeout(5000)).publishEvent(any(ScrapeTierCompleted.class));
    }

    @Test
    void whatWentWrongWithANodeIsLoggedAgainOnceItsClusterHasBeenHandedBackAndForth() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", BAD);
        when(clusters.owned()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(nodes.describe(scope(n))).thenThrow(unreachable("Nothing answered at this address."));

        scheduler.tierA();
        scheduler.tierA();
        assertThat(warnings()).hasSize(1);

        scheduler.onDutyReleased(new ClusterDutyReleased(clusterId));
        scheduler.tierA();

        assertThat(warnings()).hasSize(2);
    }

    @Test
    void oneClusterFailingDoesNotStopAnother() {
        UUID clusterAId = UUID.randomUUID();
        UUID clusterBId = UUID.randomUUID();
        ClusterEntity clusterA = cluster("a");
        ClusterEntity clusterB = cluster("b");
        BrokerNodeEntity nodeA = node(clusterAId, "na", BAD);
        BrokerNodeEntity nodeB = node(clusterBId, "nb", GOOD);

        when(clusters.owned()).thenReturn(List.of(clusterA, clusterB));
        when(clusters.nodes(clusterA.getId())).thenReturn(List.of(nodeA));
        when(clusters.nodes(clusterB.getId())).thenReturn(List.of(nodeB));
        when(nodes.describe(scope(nodeA))).thenThrow(unreachable("Nothing answered at this address."));
        when(nodes.describe(scope(nodeB))).thenReturn(primary(nodeB));

        scheduler.tierA();

        verify(persist, times(1)).recordNodeError(any(), anyString(), any());
        verify(persist, times(1)).applyTierA(any(), any(), anyLong());
    }

    @Test
    void tierCUpsertsThePageWritesSamplesAndReapsWhenTheSweepCompletes() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);

        when(clusters.clusters()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        // One page, with nothing after it.
        when(resources.read(any(ResourceQuery.class))).thenReturn(queues(1, Optional.empty()));

        scheduler.tierC();

        verify(upsert, times(1)).upsertBatch(any());
        verify(metrics, times(1)).appendQueueSamples(any());
        verify(upsert, times(1)).reapStale(any(), any());
    }

    @Test
    void tierCWalksEveryPageOfANodeInOneTickThenReaps() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);

        when(clusters.clusters()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        // Two pages, both read in this one tick.
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(queues(200, Optional.of("2")))
                .thenReturn(queues(1, Optional.empty()));

        scheduler.tierC();

        verify(upsert, times(2)).upsertBatch(any());
        verify(upsert, times(1)).reapStale(any(), any());
    }

    @Test
    void aWalkThatFailsPartWayReapsNothing() {
        UUID clusterId = UUID.randomUUID();
        ClusterEntity cluster = cluster("c");
        BrokerNodeEntity n = node(clusterId, "n", GOOD);

        when(clusters.clusters()).thenReturn(List.of(cluster));
        when(clusters.nodes(cluster.getId())).thenReturn(List.of(n));
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(queues(200, Optional.of("2")))
                .thenThrow(
                        failure(Status.INTERNAL, ErrorReasons.BAD_RESPONSE, "Operation listQueues failed", Map.of()));

        scheduler.tierC();

        verify(upsert, times(1)).upsertBatch(any());
        verify(upsert, never()).reapStale(any(), any());
    }

    // The trigger itself — that a changed interval is honoured without a restart —
    // is covered by DynamicTriggersTest, where the shared helper now lives.
}
