package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationRepository;
import io.github.sudoitir.rahdar.feature.sql.CaptureCoverage;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrokerTime;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link RrSampler} node fan-out (design.md D7). The sampler used to browse only the
 * first active node, so in a multi-primary cluster the traffic on the others was
 * never read.
 */
class RrSamplerTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static BrokerNodeEntity node(String name, String coreUrl) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(CLUSTER, name, "PRIMARY", name);
        n.applyManualDataUrl(coreUrl);
        n.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", true, "2.44.0", name), 1L, Instant.now());
        return identified(n);
    }

    /** As the directory hands nodes out: each with its id. */
    private static BrokerNodeEntity identified(BrokerNodeEntity n) {
        try {
            Field id = BrokerNodeEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(n, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return n;
    }

    /** Messages whose browse answers by the browsed node's Core URL and the queue, as these tests name them. */
    private static BrokerMessages browsing(
            List<BrokerNodeEntity> nodes, BiFunction<String, String, List<BrowsedMessage>> byCoreUrlAndQueue) {
        BrokerMessages messages = mock(BrokerMessages.class);
        when(messages.browse(eq(CLUSTER), any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    UUID nodeId = invocation.getArgument(1);
                    String coreUrl = nodes.stream()
                            .filter(n -> n.getId().equals(nodeId))
                            .findFirst()
                            .orElseThrow()
                            .getDataUrl();
                    List<BrowsedMessage> found = byCoreUrlAndQueue.apply(coreUrl, invocation.getArgument(2));
                    return new BrowsePage(found, (long) found.size());
                });
        return messages;
    }

    private static BrowsedMessage message(long id, String correlationId) {
        return new BrowsedMessage(
                id,
                0,
                true,
                4,
                0L,
                0L,
                0L,
                null,
                correlationId,
                null,
                null,
                null,
                BodyEncoding.TEXT,
                Compression.NONE,
                null,
                false,
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    /**
     * An expectation as it comes back from the repository — with an id.
     *
     * <p>The sampler keys its per-expectation rate limit and health on that id, and
     * it only ever iterates saved rows, so an unsaved entity is not a state it can
     * encounter.
     */
    private static RrExpectationEntity saved(RrExpectationEntity e) {
        try {
            Field id = RrExpectationEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(e, UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    private record Fixture(RrSampler sampler, List<Observation> seen, RrSamplerHealth health) {}

    @SuppressWarnings("unchecked")
    private static Fixture samplerOver(
            List<BrokerNodeEntity> nodes, RrExpectationEntity expectation, BrokerMessages transport) {
        return samplerOver(nodes, expectation, transport, true);
    }

    @SuppressWarnings("unchecked")
    private static Fixture samplerOver(
            List<BrokerNodeEntity> nodes, RrExpectationEntity expectation, BrokerMessages transport, boolean owned) {
        RrExpectationRepository expectations = mock(RrExpectationRepository.class);
        when(expectations.findByEnabledTrue()).thenReturn(List.of(expectation));

        ClusterDirectory nodeRepo = mock(ClusterDirectory.class);
        when(nodeRepo.nodes(CLUSTER)).thenReturn(List.copyOf(nodes));

        List<Observation> seen = new ArrayList<>();
        ObjectProvider<RrObservationSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn((RrObservationSink) seen::add);

        ReplyDestinationResolver resolver = mock(ReplyDestinationResolver.class);
        when(resolver.resolve(eq(CLUSTER), any()))
                .thenReturn(new ReplyDestinationResolver.Resolution(expectation.getReplyDestinations(), false, false));

        ClockOffsetService clocks = mock(ClockOffsetService.class);
        when(clocks.brokerTime()).thenReturn(BrokerTime.identity());

        // The queue name and routing type come from the last scrape now, rather than
        // being assumed equal to the address and ANYCAST.
        QueueTargetResolver queueTargets = mock(QueueTargetResolver.class);
        when(queueTargets.resolve(eq(CLUSTER), any(), any()))
                .thenAnswer(inv -> Optional.of(new QueueTargetResolver.QueueTarget(inv.getArgument(2), "ANYCAST")));

        RrSamplerHealth health = new RrSamplerHealth(Clock.systemUTC());

        ObjectProvider<CaptureCoverage> coverage = mock(ObjectProvider.class);
        var captureCoverage = mock(CaptureCoverage.class);
        when(coverage.getIfAvailable()).thenReturn(captureCoverage);

        ClusterOwnership ownership = mock(ClusterOwnership.class);
        when(ownership.owns(CLUSTER)).thenReturn(owned);

        return new Fixture(
                new RrSampler(
                        expectations,
                        nodeRepo,
                        ownership,
                        transport,
                        provider,
                        resolver,
                        clocks,
                        queueTargets,
                        health,
                        coverage),
                seen,
                health);
    }

    @Test
    void oneFailingNodeDoesNotCostTheOthersTheirTick() {
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));

        List<BrokerNodeEntity> nodes =
                List.of(node("a-broken", "core://broken:61616"), node("b-healthy", "core://healthy:61616"));
        BrokerMessages transport = browsing(nodes, (coreUrl, queue) -> {
            if ("core://broken:61616".equals(coreUrl)) {
                throw new IllegalStateException("node unreachable");
            }
            return List.of(message(1L, "corr-1"));
        });

        Fixture f = samplerOver(nodes, expectation, transport);

        f.sampler().tick();

        // The failing node is first by name, which is exactly the ordering that used
        // to decide the single node the sampler would use.
        assertThat(f.seen()).hasSize(1);
        assertThat(f.seen().getFirst()).isInstanceOf(Observation.RequestSeen.class);
    }

    @Test
    void everyServingNodeIsBrowsedForEveryResolvedReplyDestination() {
        RrExpectationEntity expectation = saved(new RrExpectationEntity(
                CLUSTER, "rr.request", List.of("rr.reply.a", "rr.reply.b"), null, null, 10, false));

        List<String> browsed = new ArrayList<>();
        List<BrokerNodeEntity> nodes = List.of(node("n1", "core://one:61616"), node("n2", "core://two:61616"));
        BrokerMessages transport = browsing(nodes, (coreUrl, queue) -> {
            browsed.add(coreUrl + " " + queue);
            return List.of();
        });

        Fixture f = samplerOver(nodes, expectation, transport);

        f.sampler().tick();

        assertThat(browsed)
                .containsExactlyInAnyOrder(
                        "core://one:61616 rr.request",
                        "core://one:61616 rr.reply.a",
                        "core://one:61616 rr.reply.b",
                        "core://two:61616 rr.request",
                        "core://two:61616 rr.reply.a",
                        "core://two:61616 rr.reply.b");
    }

    @Test
    void aClusterThisReplicaDoesNotOwnIsNotSampled() {
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));
        BrokerMessages transport = mock(BrokerMessages.class);
        Fixture f = samplerOver(List.of(node("broker-1", "core://broker-1:61616")), expectation, transport, false);

        f.sampler().tick();

        verifyNoInteractions(transport);
        assertThat(f.health().forExpectation(expectation.getId())).isEmpty();
    }

    @Test
    void aNodeWithNoCoreUrlOrALastErrorIsNotBrowsed() {
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));

        BrokerNodeEntity errored = node("n-errored", "core://errored:61616");
        errored.recordError(Instant.now(), "last scrape failed", null);
        BrokerNodeEntity noCore = identified(BrokerNodeEntity.fromSeed(CLUSTER, "n-no-core", "PRIMARY", "n-no-core"));
        noCore.applyHaState(
                new HaObservation(true, "STARTED", "PRIMARY", true, "2.44.0", "n-no-core"), 1L, Instant.now());

        List<String> browsed = new ArrayList<>();
        List<BrokerNodeEntity> nodes = List.of(errored, noCore, node("n-ok", "core://ok:61616"));
        BrokerMessages transport = browsing(nodes, (coreUrl, queue) -> {
            browsed.add(coreUrl);
            return List.of();
        });

        Fixture f = samplerOver(nodes, expectation, transport);

        f.sampler().tick();

        assertThat(browsed).containsExactly("core://ok:61616");
    }

    @Test
    void aClusterWithNoCoreEndpointSaysSoInsteadOfDoingNothingQuietly() {
        // The loop over serving nodes was simply empty: nothing sampled, nothing
        // logged, and an operator staring at an empty Flows tab with no way to tell
        // that from "no traffic".
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));
        BrokerNodeEntity noCore = node("broker-1", null);
        Fixture f = samplerOver(List.of(noCore), expectation, mock(BrokerMessages.class));

        f.sampler().tick();

        RrSamplerHealth.ExpectationHealth health =
                f.health().forExpectation(expectation.getId()).orElseThrow();
        assertThat(health.nodesSampled()).isZero();
        assertThat(health.skipped())
                .singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo(RrSampler.NO_CORE_ENDPOINT));
    }

    @Test
    void samplePerMinIsHonouredRatherThanStoredAndIgnored() {
        // It was written to the database, rendered in the table, and never read: the
        // UI was offering a control that did nothing.
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 1, false));
        List<BrokerNodeEntity> nodes = List.of(node("broker-1", "tcp://10.0.0.1:61616"));
        BrokerMessages transport = browsing(nodes, (coreUrl, queue) -> List.of());
        Fixture f = samplerOver(nodes, expectation, transport);

        // One sample per minute: the second tick, moments later, must not browse.
        f.sampler().tick();
        f.sampler().tick();

        verify(transport, times(1)).browse(eq(CLUSTER), any(), any(), anyInt(), anyInt(), any());
    }
}
