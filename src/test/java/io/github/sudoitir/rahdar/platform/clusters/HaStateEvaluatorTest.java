package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.BrokerAccount;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HaStateEvaluatorTest {

    private static final String NODE_ID = "f7734597-a768-11f1-aa4c-ceae3fa2df1d";
    private final HaStateEvaluator evaluator = new HaStateEvaluator();

    private static NodeEndpoint endpoint(
            String name, String role, boolean active, String state, Boolean replicaSync, Long cycle) {
        return new NodeEndpoint(
                UUID.randomUUID(),
                name,
                NODE_ID,
                "http://" + name + "/jolokia",
                name + ":61616",
                role,
                state,
                active,
                replicaSync,
                cycle,
                "2.44.0",
                null,
                null,
                Instant.now(),
                null,
                null,
                false,
                true);
    }

    @Test
    void deriveStateFromStarted() {
        assertThat(evaluator.deriveState(Boolean.TRUE)).isEqualTo("STARTED");
        assertThat(evaluator.deriveState(Boolean.FALSE)).isEqualTo("STOPPED");
        assertThat(evaluator.deriveState(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void deriveHaDesignationFromBackupThenClustered() {
        assertThat(evaluator.deriveHaDesignation(true, true)).isEqualTo("BACKUP");
        assertThat(evaluator.deriveHaDesignation(false, true)).isEqualTo("PRIMARY");
        assertThat(evaluator.deriveHaDesignation(false, false)).isEqualTo("NONE");
    }

    @Test
    void healthyPairIsOkWithNoSplitBrain() {
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 5L),
                endpoint("backup", "BACKUP", false, "STARTED", true, 5L));

        List<LogicalNode> nodes = evaluator.toLogicalNodes(eps);
        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), nodes);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).splitBrain()).isEqualTo(SplitBrainStatus.NONE);
        assertThat(health.level()).isEqualTo(Level.OK);
        assertThat(health.liveEndpointNames()).containsExactly("primary");
        assertThat(health.notes()).isEmpty();
    }

    @Test
    void healthyStandbyIsNotReportedDown() {
        // A synced backup is Started=true, Active=false. That must not read as a fault.
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 5L),
                endpoint("backup", "BACKUP", false, "STARTED", true, 5L));

        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), evaluator.toLogicalNodes(eps));

        assertThat(health.level()).isEqualTo(Level.OK);
    }

    @Test
    void replicationBehindIsDegraded() {
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 5L),
                endpoint("backup", "BACKUP", false, "STARTED", false, 5L));

        List<LogicalNode> nodes = evaluator.toLogicalNodes(eps);
        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), nodes);

        assertThat(nodes.get(0).replicationBehind()).isTrue();
        assertThat(health.replicationBehind()).isTrue();
        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.notes()).anyMatch(n -> n.contains("not caught up"));
    }

    @Test
    void toLogicalNodesAppliesTheSuppliedSplitBrainVerdict() {
        // The corroboration ratchet now lives in ScrapeCycle; the evaluator only
        // reflects whatever verdict it is handed (ScrapeCycleTest covers the ratchet).
        List<NodeEndpoint> eps = List.of(
                endpoint("primary", "PRIMARY", true, "STARTED", null, 7L),
                endpoint("backup", "BACKUP", true, "STARTED", null, 7L));

        List<LogicalNode> unknown = evaluator.toLogicalNodes(eps);
        assertThat(unknown.get(0).splitBrain()).isEqualTo(SplitBrainStatus.NONE);

        List<LogicalNode> critical = evaluator.toLogicalNodes(eps, Map.of(NODE_ID, SplitBrainStatus.CRITICAL));
        assertThat(critical.get(0).splitBrain()).isEqualTo(SplitBrainStatus.CRITICAL);
        assertThat(evaluator.toHealth(UUID.randomUUID(), critical).level()).isEqualTo(Level.CRITICAL);
    }

    @Test
    void aFailedOverPairDoesNotShowTheDeadPrimaryAsLive() {
        // The dead primary keeps a stale active=true, but it now carries a lastError.
        NodeEndpoint deadPrimary = new NodeEndpoint(
                UUID.randomUUID(),
                "primary",
                NODE_ID,
                "http://primary/jolokia",
                "primary:61616",
                "PRIMARY",
                "STARTED",
                true,
                null,
                6L,
                "2.44.0",
                "Nothing answered at this address.",
                null,
                Instant.now(),
                null,
                null,
                false,
                true);
        NodeEndpoint promotedBackup = endpoint("backup", "PRIMARY", true, "STARTED", true, 9L);

        List<LogicalNode> nodes = evaluator.toLogicalNodes(List.of(deadPrimary, promotedBackup));
        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), nodes);

        assertThat(nodes.get(0).splitBrain()).isEqualTo(SplitBrainStatus.NONE);
        assertThat(health.liveEndpointNames()).containsExactly("backup");
        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.notes()).anyMatch(n -> n.contains("unreachable"));
    }

    @Test
    void neverContactedClusterIsUnknown() {
        NodeEndpoint uncontacted = new NodeEndpoint(
                UUID.randomUUID(),
                "backup:61616",
                NODE_ID,
                null,
                "backup:61616",
                "BACKUP",
                "UNKNOWN",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false);

        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), evaluator.toLogicalNodes(List.of(uncontacted)));

        assertThat(health.level()).isEqualTo(Level.UNKNOWN);
    }

    private static NodeEndpoint rejected(String name) {
        NodeEndpoint e = endpoint(name, "PRIMARY", true, "STARTED", null, 5L);
        return new NodeEndpoint(
                e.id(),
                name,
                e.nativeNodeId(),
                e.managementUrl(),
                e.dataUrl(),
                e.haDesignation(),
                e.state(),
                e.active(),
                null,
                5L,
                e.version(),
                "The broker rejected these credentials.",
                BrokerConnectionException.Kind.CREDENTIALS_REJECTED,
                e.lastSeenAt(),
                null,
                null,
                false,
                true);
    }

    @Test
    void aManagementAccountEveryBrokerRejectsIsNamedAndIsNotCalledUnreachable() {
        List<NodeEndpoint> eps = List.of(rejected("a"), rejected("b"));

        ClusterHealth health = evaluator.toHealth(UUID.randomUUID(), evaluator.toLogicalNodes(eps));

        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.credentialRejections()).singleElement().satisfies(r -> {
            assertThat(r.account()).isEqualTo(BrokerAccount.MANAGEMENT);
            assertThat(r.nodeNames()).containsExactly("a", "b");
        });
        assertThat(health.notes())
                .singleElement()
                .asString()
                .contains("management account on every node")
                .contains("Connection settings")
                .doesNotContain("unreachable");
    }

    @Test
    void aCoreAccountOnlyOneBrokerRejectedIsNamedWithThatBroker() {
        NodeEndpoint a = endpoint("a", "PRIMARY", true, "STARTED", null, 5L);
        NodeEndpoint b = endpoint("b", "PRIMARY", true, "STARTED", null, 5L);

        ClusterHealth health =
                evaluator.toHealth(UUID.randomUUID(), evaluator.toLogicalNodes(List.of(a, b)), Set.of(b.id()));

        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.credentialRejections()).singleElement().satisfies(r -> {
            assertThat(r.account()).isEqualTo(BrokerAccount.CORE);
            assertThat(r.nodeNames()).containsExactly("b");
        });
        assertThat(health.notes()).singleElement().asString().contains("Core account on b");
    }

    @Test
    void aHealthyClusterNamesNoRejection() {
        ClusterHealth health = evaluator.toHealth(
                UUID.randomUUID(),
                evaluator.toLogicalNodes(List.of(endpoint("a", "PRIMARY", true, "STARTED", null, 5L))));

        assertThat(health.credentialRejections()).isEmpty();
    }
}
