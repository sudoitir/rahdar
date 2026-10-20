package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.VersionGate;
import io.github.sudoitir.rahdar.platform.clusters.CapabilityLedger;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.system.artemis.BrokerCommands.Command;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A mixed-version cluster keeps a gated command for the nodes that have it (ADR-0142). */
class BrokerCommandsVersionGateTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final VersionGate GATE = new VersionGate("X", "Doing X", new BrokerVersion(2, 38, 0));

    private final ClusterDirectory nodes = mock(ClusterDirectory.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    ArtemisNodes artemisNodes = mock(ArtemisNodes.class);
    private final BrokerCommands commands = new BrokerCommands(
            nodes,
            connections,
            mock(AuditService.class),
            mock(ActorResolver.class),
            mock(SettingsService.class),
            mock(ClusterAccessGuard.class),
            mock(CapabilityLedger.class),
            artemisNodes);

    private static ClusterNode node(String name, String version) {
        ClusterNode n = mock(ClusterNode.class);
        when(n.getId()).thenReturn(UUID.randomUUID());
        when(n.getName()).thenReturn(name);
        when(n.getArtemisNodeId()).thenReturn(name);
        when(n.getJolokiaUrl()).thenReturn("http://" + name + ":8161/console/jolokia");
        when(n.getActive()).thenReturn(true);
        when(n.getVersion()).thenReturn(version);
        return n;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOlderNodeIsSkippedWithTheReleaseItNeedsAndTheOthersProceed(boolean dryRun) {
        List<ClusterNode> cluster = List.of(node("new", "2.57.0"), node("old", "2.35.0"), node("unknown", null));
        when(nodes.nodes(CLUSTER)).thenReturn(cluster);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(mock(ArtemisNode.class));

        LifecycleOutcome outcome = commands.run(Command.builder()
                .clusterId(CLUSTER)
                .permission("x:write")
                .auditAction("X")
                .targetType("X")
                .targetName("x")
                .dryRun(dryRun)
                .requires(GATE)
                .action((client, broker) -> NodeStatus.APPLIED)
                .build());

        NodeStatus proceeded = dryRun ? NodeStatus.WOULD_APPLY : NodeStatus.APPLIED;
        assertThat(outcome.nodes())
                .extracting(NodeOutcome::nodeName, NodeOutcome::status)
                .containsExactly(
                        Tuple.tuple("new", proceeded),
                        Tuple.tuple("old", NodeStatus.UNSUPPORTED_VERSION),
                        Tuple.tuple("unknown", proceeded));
        assertThat(outcome.nodes().get(1).error())
                .contains("Doing X needs Artemis 2.38.0")
                .contains("2.35.0");
        assertThat(outcome.anyFailed()).isFalse();
    }

    @Test
    void aCommandWithoutAGateReachesEveryNode() {
        List<ClusterNode> cluster = List.of(node("old", "2.32.0"));
        when(nodes.nodes(CLUSTER)).thenReturn(cluster);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(mock(ArtemisNode.class));

        LifecycleOutcome outcome = commands.run(Command.builder()
                .clusterId(CLUSTER)
                .permission("x:write")
                .auditAction("X")
                .targetType("X")
                .targetName("x")
                .action((client, broker) -> NodeStatus.APPLIED)
                .build());

        assertThat(outcome.nodes()).extracting(NodeOutcome::status).containsExactly(NodeStatus.APPLIED);
    }
}
