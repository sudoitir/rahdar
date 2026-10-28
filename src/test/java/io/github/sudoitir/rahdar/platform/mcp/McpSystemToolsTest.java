package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A tool of one broker system is offered only for readable clusters of that system, and refused on others (ADR-0207). */
class McpSystemToolsTest {

    private static final String SYSTEM_TOOL = "artemis_config";

    private final UUID artemis = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final BrokerSystems systems = mock(BrokerSystems.class);
    private final PermissionResolver perm = mock(PermissionResolver.class);

    @SuppressWarnings("unchecked")
    private McpToolCatalog catalog() {
        McpToolDef system = new McpToolDef(SYSTEM_TOOL, McpToolDef.Posture.READ, "s", List.of()).forSystem("artemis");
        McpToolDef shared = new McpToolDef("list_resources", McpToolDef.Posture.READ, "s", List.of());
        FeatureDescriptor feature = FeatureDescriptor.builder()
                .id("probe")
                .title("Probe")
                .kind(FeatureDescriptor.Kind.FEATURE)
                .mcpTool(system)
                .mcpTool(shared)
                .build();
        FeatureRegistry features = mock(FeatureRegistry.class);
        when(features.enabled()).thenReturn(List.of(feature));
        return new McpToolCatalog(features, mock(ObjectProvider.class), new McpSystems(clusters, systems, perm));
    }

    private RegisteredCluster registered(UUID id, String name, String system) {
        RegisteredCluster cluster = mock(RegisteredCluster.class);
        when(cluster.getId()).thenReturn(id);
        when(cluster.getName()).thenReturn(name);
        when(systems.idOf(id)).thenReturn(system);
        when(clusters.cluster(id)).thenReturn(Optional.of(cluster));
        return cluster;
    }

    private void world(boolean seesArtemis) {
        RegisteredCluster prod = registered(artemis, "prod", "artemis");
        RegisteredCluster events = registered(other, "events", "kafka");
        when(clusters.clusters()).thenReturn(List.of(prod, events));
        when(perm.canSeeCluster(artemis)).thenReturn(seesArtemis);
        when(perm.canSeeCluster(other)).thenReturn(true);
        when(systems.nameOf("kafka")).thenReturn("Kafka");
    }

    @Test
    void aSystemToolIsOfferedWhenTheCallerCanReadAClusterOfThatSystem() {
        world(true);

        assertThat(catalog().toolNames()).contains(SYSTEM_TOOL, "list_resources");
    }

    @Test
    void aSystemToolIsAbsentWhenOnlyOtherSystemsClustersAreReadable() {
        world(false);
        McpToolCatalog catalog = catalog();

        assertThat(catalog.toolNames()).containsExactly("list_resources");
        assertThat(catalog.find(SYSTEM_TOOL)).isNull();
        assertThat(catalog.index().get("read")).noneMatch(entry -> SYSTEM_TOOL.equals(entry.get("tool")));
        assertThat(catalog.instructions()).doesNotContain(SYSTEM_TOOL);
    }

    @Test
    void aCallNamingAClusterOfAnotherSystemIsAToolErrorThatNamesBoth() {
        world(true);

        assertThat(catalog().refusedOnCluster(SYSTEM_TOOL, other))
                .isEqualTo("artemis_config applies to Artemis clusters; events runs Kafka.");
    }

    @Test
    void aCallOnAClusterOfTheToolsSystemIsNotRefused() {
        world(true);

        assertThat(catalog().refusedOnCluster(SYSTEM_TOOL, artemis)).isNull();
    }

    @Test
    void aClusterTheCallerCannotSeeIsLeftToTheToolsOwnNotFound() {
        world(false);

        assertThat(catalog().refusedOnCluster(SYSTEM_TOOL, artemis)).isNull();
    }

    @Test
    void aToolOfNoSystemAppliesToEveryCluster() {
        world(true);
        McpToolCatalog catalog = catalog();

        assertThat(catalog.refusedOnCluster("list_resources", other)).isNull();
        assertThat(catalog.refusedOnCluster("list_resources", null)).isNull();
    }

    private McpToolCatalog kafkaCatalog() {
        FeatureRegistry features = mock(FeatureRegistry.class);
        when(features.enabled()).thenReturn(List.of(KafkaModule.DESCRIPTOR));
        return new McpToolCatalog(features, mock(ObjectProvider.class), new McpSystems(clusters, systems, perm));
    }

    @Test
    void theKafkaToolsAreOfferedOnlyToACallerWhoCanReadAKafkaCluster() {
        world(false);
        when(perm.canSeeCluster(other)).thenReturn(false);
        assertThat(kafkaCatalog().toolNames()).isEmpty();

        when(perm.canSeeCluster(other)).thenReturn(true);
        assertThat(kafkaCatalog().toolNames())
                .containsExactlyInAnyOrder(
                        "kafka_list_groups",
                        "kafka_describe_group",
                        "kafka_preview_offset_reset",
                        "kafka_reset_offsets",
                        "kafka_topic_config",
                        "kafka_set_topic_config",
                        "kafka_list_acls",
                        "kafka_list_transactions",
                        "kafka_abort_transaction");
    }

    @Test
    void aKafkaToolCalledOnAnArtemisClusterIsAToolErrorThatNamesBoth() {
        world(true);
        when(systems.nameOf("artemis")).thenReturn("Artemis");

        assertThat(kafkaCatalog().refusedOnCluster("kafka_reset_offsets", artemis))
                .isEqualTo("kafka_reset_offsets applies to Kafka clusters; prod runs Artemis.");
        assertThat(kafkaCatalog().refusedOnCluster("kafka_reset_offsets", other))
                .isNull();
    }
}
