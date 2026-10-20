package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.rahdar.kernel.core.RahdarProperties;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AlertPayloadsTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    private AlertPayloads payloads(String publicUrl) {
        ClusterDirectory clusters = mock(ClusterDirectory.class);
        RegisteredCluster cluster = mock(RegisteredCluster.class);
        when(cluster.getName()).thenReturn("prod-eu");
        when(clusters.cluster(clusterId)).thenReturn(Optional.of(cluster));
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(nodeId);
        when(node.getName()).thenReturn("primary-1");
        when(clusters.nodes(clusterId)).thenReturn(List.of(node));
        return new AlertPayloads(clusters, new RahdarProperties(publicUrl), mapper);
    }

    @Test
    void theOriginalFieldsKeepTheirNamesAndMeaning() {
        UUID ruleId = UUID.randomUUID();
        String json = payloads("")
                .build(
                        ruleId,
                        "Node down",
                        "CRITICAL",
                        clusterId,
                        List.of(new AlertPayloads.Item("node:" + nodeId, TransitionKind.FIRED, 1.0)),
                        Instant.parse("2026-09-24T10:00:00Z"));
        JsonNode root = mapper.readTree(json);
        assertThat(root.get("ruleId").asString()).isEqualTo(ruleId.toString());
        assertThat(root.get("ruleName").asString()).isEqualTo("Node down");
        assertThat(root.get("severity").asString()).isEqualTo("CRITICAL");
        JsonNode t = root.get("transitions").get(0);
        assertThat(t.get("subject").asString()).isEqualTo("node:" + nodeId);
        assertThat(t.get("kind").asString()).isEqualTo("FIRED");
        assertThat(t.get("value").asDouble()).isEqualTo(1.0);
        // Version 2 additions.
        assertThat(root.get("version").asInt()).isEqualTo(2);
        assertThat(root.get("clusterName").asString()).isEqualTo("prod-eu");
        assertThat(t.get("subjectLabel").asString()).isEqualTo("node primary-1");
        assertThat(t.get("at").asString()).isEqualTo("2026-09-24T10:00:00Z");
        assertThat(root.has("rahdarUrl")).isFalse();
    }

    @Test
    void aPublicUrlLinksToTheClustersAlerts() {
        String json = payloads("https://rahdar.example.com/")
                .build(UUID.randomUUID(), "r", "INFO", clusterId, List.of(), Instant.now());
        assertThat(mapper.readTree(json).get("rahdarUrl").asString())
                .isEqualTo("https://rahdar.example.com/clusters/" + clusterId + "/alerts");
    }

    @Test
    void subjectsAreLabelledReadably() {
        Map<String, String> names = Map.of(nodeId.toString(), "primary-1");
        assertThat(AlertPayloads.label("node:" + nodeId + "/subscription:orders", "prod", names))
                .isEqualTo("node primary-1 / subscription orders");
        assertThat(AlertPayloads.label("cluster", "prod", names)).isEqualTo("cluster prod");
        assertThat(AlertPayloads.label("rahdar", "prod", names)).isEqualTo("Rahdar host");
        assertThat(AlertPayloads.label("setup:HA_SINGLE_PAIR_QUORUM:cluster", "prod", names))
                .isEqualTo("HA_SINGLE_PAIR_QUORUM on cluster prod");
        assertThat(AlertPayloads.label("node:unknown", "prod", names)).isEqualTo("node:unknown");
    }
}
