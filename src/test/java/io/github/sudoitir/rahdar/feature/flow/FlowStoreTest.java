package io.github.sudoitir.rahdar.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link FlowStore} against a real Postgres: the lease, the per-node replace, and forgetting. */
class FlowStoreTest extends PostgresIntegrationTest {

    @Autowired
    FlowStore store;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-09-14T10:00:00Z");

    @BeforeEach
    void cluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name, system) VALUES (:id, :name, 'artemis')",
                Map.of("id", clusterId, "name", "flow-store-" + clusterId));
        jdbc.update(
                "INSERT INTO broker_node (id, cluster_id, name) VALUES (:id, :c, :name)",
                Map.of("id", nodeId, "c", clusterId, "name", "node-a"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    @Test
    void aLeaseIsExtendedButNeverShortened() {
        store.renew(clusterId, t0.plusSeconds(60));
        store.renew(clusterId, t0.plusSeconds(30));

        assertThat(store.observedClusters(t0.plusSeconds(45))).contains(clusterId);
        assertThat(store.observedClusters(t0.plusSeconds(61))).doesNotContain(clusterId);
    }

    @Test
    void aSweepReplacesTheNodesEdgesAndKeepsAnUnknownRateUnknown() {
        store.persistNode(sample(t0, null), List.of(edge("orders", 12.5), edge("refunds", null)), List.of());
        store.persistNode(sample(t0.plusSeconds(15), null), List.of(edge("orders", 20.0)), List.of());

        var edges = store.edges(clusterId);
        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.edge().queue()).isEqualTo("orders");
            assertThat(e.edge().rate()).isEqualTo(20.0);
            assertThat(e.sampledAt()).isEqualTo(t0.plusSeconds(15));
        });

        store.persistNode(sample(t0.plusSeconds(30), null), List.of(edge("orders", null)), List.of());
        assertThat(store.edges(clusterId).getFirst().edge().rate()).isNull();
    }

    @Test
    void aFailedNodeDropsItsEdgesAndRecordsWhy() {
        store.persistNode(sample(t0, null), List.of(edge("orders", 1.0)), List.of());

        store.persistNode(sample(t0.plusSeconds(15), "UNREACHABLE"), List.of(), List.of());

        assertThat(store.edges(clusterId)).isEmpty();
        assertThat(store.nodeSamples(clusterId))
                .singleElement()
                .satisfies(n -> assertThat(n.errorKind()).isEqualTo("UNREACHABLE"));
    }

    @Test
    void anUnobservedClusterIsForgottenLeaseAndAll() {
        store.renew(clusterId, t0);
        store.persistNode(sample(t0, null), List.of(edge("orders", 1.0)), List.of());

        store.forgetUnobserved(t0.plusSeconds(1));

        assertThat(store.edges(clusterId)).isEmpty();
        assertThat(store.nodeSamples(clusterId)).isEmpty();
        assertThat(store.observedClusters(t0.minusSeconds(1))).doesNotContain(clusterId);
    }

    @Test
    void routesAreReplacedPerSweepAndKeepAnUnknownRateUnknown() {
        var divert = new FlowStore.Route(
                FlowStore.RouteKind.DIVERT,
                "orders-audit",
                "ORDERS",
                "AUDIT",
                "color='red'",
                null,
                true,
                true,
                0,
                null);
        var bridge = new FlowStore.Route(
                FlowStore.RouteKind.BRIDGE, "to-dc2", "ORDERS", "ORDERS.remote", null, null, false, false, 900, 12.0);
        store.persistNode(sample(t0, null), List.of(), List.of(divert, bridge));
        store.persistNode(sample(t0.plusSeconds(15), null), List.of(), List.of(divert));

        assertThat(store.routes(clusterId)).singleElement().satisfies(r -> {
            assertThat(r.route().name()).isEqualTo("orders-audit");
            assertThat(r.route().exclusive()).isTrue();
            assertThat(r.route().filter()).isEqualTo("color='red'");
            assertThat(r.route().rate()).isNull();
        });

        store.renew(clusterId, t0);
        store.forgetUnobserved(t0.plusSeconds(1));
        assertThat(store.routes(clusterId)).isEmpty();
    }

    private NodeSample sample(Instant at, String errorKind) {
        return new NodeSample(nodeId, clusterId, at, 1, 1, 2, 2, errorKind == null ? null : "down", errorKind);
    }

    private static Edge edge(String queue, Double rate) {
        return new Edge(Kind.CONSUME, "billing", "artemis", "10.0.0.5", "CORE", queue, queue, rate, 0, 1, false);
    }
}
