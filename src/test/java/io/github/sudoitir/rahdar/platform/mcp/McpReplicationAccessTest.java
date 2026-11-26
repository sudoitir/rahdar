package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * A read-only token and a cluster of another system are refused by the gate, before the tool — and so before its hold
 * — runs.
 */
class McpReplicationAccessTest {

    private final UUID kafka = UUID.randomUUID();
    private final UUID artemis = UUID.randomUUID();
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final McpStatelessServerHandler delegate = mock(McpStatelessServerHandler.class);

    @Test
    void aReadOnlyTokenIsRefusedBeforeTheToolCanHold() {
        McpGate gate = gate(true);

        McpSchema.JSONRPCResponse response = gate.wrap(delegate)
                .handleRequest(null, call("kafka_reassign", kafka))
                .block();

        assertThat(response.result().toString()).contains("read-only");
        verify(delegate, never()).handleRequest(any(), any());
    }

    @Test
    void aKafkaToolOnAnotherSystemIsRefusedBeforeTheToolRuns() {
        McpGate gate = gate(false);

        McpSchema.JSONRPCResponse response = gate.wrap(delegate)
                .handleRequest(null, call("kafka_reassign", artemis))
                .block();

        assertThat(response.result().toString())
                .contains("applies to Kafka clusters")
                .contains("Artemis");
        verify(delegate, never()).handleRequest(any(), any());
    }

    @Test
    void aSchemaDeleteIsRefusedForAReadOnlyTokenAndForArtemisBeforeTheToolRuns() {
        assertThat(gate(true)
                        .wrap(delegate)
                        .handleRequest(null, call("kafka_delete_subject", kafka))
                        .block()
                        .result()
                        .toString())
                .contains("read-only");
        assertThat(gate(false)
                        .wrap(delegate)
                        .handleRequest(null, call("kafka_delete_subject", artemis))
                        .block()
                        .result()
                        .toString())
                .contains("applies to Kafka clusters")
                .contains("Artemis");
        verify(delegate, never()).handleRequest(any(), any());
    }

    @SuppressWarnings("unchecked")
    private McpGate gate(boolean readOnly) {
        when(settings.bool(McpSettings.READ_ONLY)).thenReturn(readOnly);
        ObjectProvider<SettingsService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(settings);
        FeatureRegistry features = mock(FeatureRegistry.class);
        when(features.enabled()).thenReturn(List.of(KafkaModule.DESCRIPTOR));
        when(features.isEnabled(McpModule.DESCRIPTOR.id())).thenReturn(true);
        ClusterDirectory clusters = mock(ClusterDirectory.class);
        BrokerSystems systems = mock(BrokerSystems.class);
        PermissionResolver perm = mock(PermissionResolver.class);
        RegisteredCluster kafkaCluster = mock(RegisteredCluster.class);
        RegisteredCluster artemisCluster = mock(RegisteredCluster.class);
        when(kafkaCluster.getId()).thenReturn(kafka);
        when(kafkaCluster.getName()).thenReturn("events");
        when(artemisCluster.getId()).thenReturn(artemis);
        when(artemisCluster.getName()).thenReturn("prod");
        when(clusters.clusters()).thenReturn(List.of(kafkaCluster, artemisCluster));
        when(clusters.cluster(kafka)).thenReturn(Optional.of(kafkaCluster));
        when(clusters.cluster(artemis)).thenReturn(Optional.of(artemisCluster));
        when(systems.idOf(kafka)).thenReturn("kafka");
        when(systems.idOf(artemis)).thenReturn("artemis");
        when(systems.nameOf("artemis")).thenReturn("Artemis");
        when(perm.canSeeCluster(kafka)).thenReturn(true);
        when(perm.canSeeCluster(artemis)).thenReturn(true);
        when(actors.resolve()).thenReturn(mock(Actor.class));
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        McpToolCatalog catalog = new McpToolCatalog(features, provider, new McpSystems(clusters, systems, perm));
        return new McpGate(catalog, audit, actors, JsonMapper.builder().build());
    }

    private static McpSchema.JSONRPCRequest call(String tool, UUID clusterId) {
        return new McpSchema.JSONRPCRequest(
                McpSchema.METHOD_TOOLS_CALL,
                "1",
                Map.of("name", tool, "arguments", Map.of("clusterId", clusterId.toString(), "confirm", "orders-0")));
    }
}
