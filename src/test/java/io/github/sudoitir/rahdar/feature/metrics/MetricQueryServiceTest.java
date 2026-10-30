package io.github.sudoitir.rahdar.feature.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples.Bucket;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples.NodeBucket;
import io.github.sudoitir.rahdar.platform.scrape.PluginMetrics;
import io.github.sudoitir.rahdar.platform.scrape.SampleGate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricQueryServiceTest {

    @Mock
    MetricSamples repository;

    @Mock
    LifecycleRegistry lifecycle;

    /** Permissive by default: an unstubbed void call is a no-op, i.e. access granted.
     * The guard's real behaviour is covered by {@code ClusterScopeAuthorizationTest}. */
    @Mock
    ClusterAccessGuard clusterAccess;

    @Mock
    ClusterDirectory directory;

    @Mock
    PluginMetrics pluginMetrics;

    MetricQueryService service;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(lifecycle.retention("metrics")).thenReturn(Optional.of(Duration.ofDays(7)));
        service = new MetricQueryService(repository, lifecycle, clusterAccess, directory, pluginMetrics);
    }

    @Test
    void counterResetWithinABucketNeverProducesANegativeRate() {
        // A broker restart resets the messagesAdded counter within one bucket. The clamp lives in
        // the repository's own SQL, so the service must pass the already-clamped value straight
        // through and never introduce a negative number of its own.
        Instant bucket = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.rateSeries(eq(clusterId), eq("openbroker.enqueued"), any(), any(), any(), any()))
                .thenReturn(List.of(new Bucket(bucket, 0.0, null)));

        MetricSeriesResponse response = service.query(
                clusterId,
                new MetricQuery(
                        List.of("openbroker.enqueued"),
                        null,
                        bucket,
                        bucket.plusSeconds(60),
                        Duration.ofSeconds(60),
                        null));

        assertThat(response.series().get(0).points())
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.value()).isNotNegative());
    }

    @Test
    void aFinerStepThanAllowedIsClampedAndMarkedTruncated() {
        when(repository.gaugeSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(7));
        MetricSeriesResponse response = service.query(
                clusterId, new MetricQuery(List.of("openbroker.backlog"), null, from, to, Duration.ofSeconds(1), null));

        assertThat(response.truncated()).isTrue();
        assertThat(Duration.parse(response.step())).isGreaterThanOrEqualTo(SampleGate.HEARTBEAT);
    }

    @Test
    void aStepFinerThanTheHeartbeatIsWidenedToIt() {
        when(repository.gaugeSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));
        MetricSeriesResponse response = service.query(
                clusterId, new MetricQuery(List.of("openbroker.backlog"), null, from, to, Duration.ofSeconds(1), null));

        assertThat(response.truncated()).isTrue();
        assertThat(Duration.parse(response.step())).isEqualTo(SampleGate.HEARTBEAT);
    }

    @Test
    void aRangeBeyondRetentionIsClampedAndMarkedTruncated() {
        when(repository.gaugeSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(30));
        MetricSeriesResponse response =
                service.query(clusterId, new MetricQuery(List.of("openbroker.backlog"), null, from, to, null, null));

        assertThat(response.truncated()).isTrue();
        assertThat(response.from()).isAfter(from);
    }

    private static ClusterNode node(UUID id, String name, boolean active) {
        ClusterNode n = mock(ClusterNode.class);
        when(n.getId()).thenReturn(id);
        when(n.getName()).thenReturn(name);
        when(n.getActive()).thenReturn(active);
        return n;
    }

    @Test
    void splitByNodeNamesEachNodeAndListsAServingNodeWithNoSampleAsNotSampled() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID backup = UUID.randomUUID();
        // Built before the stubbing that returns them: Mockito cannot stub inside another stubbing.
        List<ClusterNode> nodes = List.of(
                node(a, "artemis-a", true), node(b, "artemis-b", true), node(backup, "artemis-a-backup", false));
        when(directory.nodes(clusterId)).thenReturn(nodes);
        Instant to = Instant.now();
        when(repository.rateSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(new Bucket(to.minusSeconds(60), 30.0, null)));
        when(repository.rateSeriesByNode(eq(clusterId), eq("openbroker.enqueued"), eq("orders"), any(), any(), any()))
                .thenReturn(List.of(new NodeBucket(a, to.minusSeconds(60), 30.0, null)));

        MetricSeriesResponse response = service.query(
                clusterId,
                new MetricQuery(
                        List.of("openbroker.enqueued"),
                        ResourceRef.subscription("orders"),
                        to.minusSeconds(3600),
                        to,
                        null,
                        "NODE"));

        assertThat(response.splitBy()).isEqualTo("NODE");
        assertThat(response.series().get(0).points()).hasSize(1);
        assertThat(response.byNode())
                .extracting(n -> n.nodeName() + ":" + n.sampled())
                // The standby is neither serving nor sampled, so it is not a node of this split.
                .containsExactly("artemis-a:true", "artemis-b:false");
        assertThat(response.byNode().get(1).series().get(0).points()).isEmpty();
    }

    @Test
    void splitByNodeWidensTheStepSoEveryNodeTogetherStaysWithinTheBound() {
        List<ClusterNode> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(node(UUID.randomUUID(), "node-" + i, true));
        }
        when(directory.nodes(clusterId)).thenReturn(many);
        Instant to = Instant.now();

        MetricSeriesResponse response = service.query(
                clusterId,
                new MetricQuery(
                        List.of("openbroker.backlog"),
                        ResourceRef.subscription("orders"),
                        to.minus(Duration.ofHours(24)),
                        to,
                        Duration.ofSeconds(60),
                        "NODE"));

        long buckets = Duration.ofHours(24).dividedBy(Duration.parse(response.step()));
        assertThat(buckets * many.size()).isLessThanOrEqualTo(2_000);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void aSplitIsRefusedForTheClusterScopeAndForAnyOtherDimension() {
        Instant to = Instant.now();
        MetricQuery clusterSplit =
                new MetricQuery(List.of("openbroker.backlog"), null, to.minusSeconds(60), to, null, "NODE");
        assertThatThrownBy(() -> service.query(clusterId, clusterSplit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs one subscription");
        MetricQuery addressSplit = new MetricQuery(
                List.of("openbroker.backlog"),
                ResourceRef.subscription("orders"),
                to.minusSeconds(60),
                to,
                null,
                "ADDRESS");
        assertThatThrownBy(() -> service.query(clusterId, addressSplit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("splitBy must be NODE");
    }

    private PluginMetrics.Declared editsMetric() {
        return new PluginMetrics.Declared(
                "acme-notes",
                new PluginDescriptor.Metric("acme-notes:edits", "Edits", "count", "note", "acme-notes:stats"));
    }

    @Test
    void aPluginSeriesNeedsTheMetricsDeclaredPermission() {
        when(pluginMetrics.declared("acme-notes:edits")).thenReturn(Optional.of(editsMetric()));
        doThrow(new NotFoundException("cluster", clusterId))
                .when(clusterAccess)
                .requireCluster(clusterId, "acme-notes:stats");
        Instant to = Instant.now();

        Instant from = to.minus(Duration.ofHours(1));
        assertThatThrownBy(() -> service.pluginQuery(clusterId, "acme-notes:edits", "daily", from, to, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPluginSeriesIsReadAsGaugeBucketsInTheDeclaredUnit() {
        when(pluginMetrics.declared("acme-notes:edits")).thenReturn(Optional.of(editsMetric()));
        Instant bucket = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.pluginSeries(eq(clusterId), eq("acme-notes:edits"), eq("daily"), any(), any(), any()))
                .thenReturn(List.of(new Bucket(bucket, 3.0, 4.0)));
        Instant to = Instant.now();

        MetricSeriesResponse response =
                service.pluginQuery(clusterId, "acme-notes:edits", "daily", to.minus(Duration.ofHours(1)), to, null);

        assertThat(response.series()).singleElement().satisfies(s -> {
            assertThat(s.unit()).isEqualTo("count");
            assertThat(s.points()).singleElement().extracting(p -> p.value()).isEqualTo(3.0);
        });
    }

    @Test
    void aMetricNoRunningPluginDeclaresIsUnknown() {
        when(pluginMetrics.declared("acme-notes:edits")).thenReturn(Optional.empty());
        Instant to = Instant.now();

        assertThatThrownBy(() -> service.pluginQuery(clusterId, "acme-notes:edits", "daily", to, to, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
