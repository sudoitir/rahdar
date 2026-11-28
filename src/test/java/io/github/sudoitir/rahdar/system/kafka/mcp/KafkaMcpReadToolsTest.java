package io.github.sudoitir.rahdar.system.kafka.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.kafka.KafkaClusterView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerQuorum;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterProblems;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterTotals;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterView;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The cluster tool returns the service's view. It does not read the brokers itself. */
class KafkaMcpReadToolsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @Test
    void theToolReturnsTheServicesOwnTotals() {
        KafkaClusterView service = mock(KafkaClusterView.class);
        ClusterView view = new ClusterView(
                "KRaft",
                true,
                null,
                List.of(),
                List.of(),
                new BrokerQuorum(true, null, "1", List.of(), true),
                List.of(),
                new ClusterTotals(3, 7, 1, 2L, 6L, 13L, 0L, 1L, false),
                new ClusterProblems(0L, 13L, 1L, List.of("orders"), null, 0L),
                List.of());
        when(service.read(CLUSTER)).thenReturn(view);
        KafkaMcpReadTools tools = new KafkaMcpReadTools(null, null, null, null, null, null, null, null, service);

        McpSchema.CallToolResult result = tools.kafkaDescribeCluster(CLUSTER.toString());

        assertThat(result.isError()).isFalse();
        KafkaMcpViews.ClusterAnswer answer = (KafkaMcpViews.ClusterAnswer) result.structuredContent();
        assertThat(answer.cluster()).isSameAs(view);
        assertThat(answer.cluster().totals()).isEqualTo(view.totals());
        verify(service).read(CLUSTER);
    }

    @Test
    void aCallerWithoutClusterReadIsRefusedBeforeAnyBrokerCall() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterAccessGuard access = mock(ClusterAccessGuard.class);
        doThrow(new NotFoundException("permission")).when(access).requireCluster(CLUSTER, Permissions.CLUSTER_READ);
        KafkaClusterView service = new KafkaClusterView(gateway, access, null, null);
        KafkaMcpReadTools tools = new KafkaMcpReadTools(null, null, null, null, null, null, null, null, service);

        McpSchema.CallToolResult result = tools.kafkaDescribeCluster(CLUSTER.toString());

        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).contains(McpErrors.CLUSTER_DENIED);
        verify(gateway, never()).cluster(CLUSTER);
    }
}
