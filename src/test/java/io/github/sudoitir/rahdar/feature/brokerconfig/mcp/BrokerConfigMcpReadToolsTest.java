package io.github.sudoitir.rahdar.feature.brokerconfig.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService;
import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigDiffService;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigDiffView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigKeyView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigNodeValueView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigNodeView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigSectionView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigSummaryView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigValueGroupView;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code artemis_config_diff} is a thin adapter over {@link ConfigDiffService}: what it adds is the
 * comma-separated node narrowing and a response of drifting keys only (ADR-0178).
 */
class BrokerConfigMcpReadToolsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private ConfigDiffService configDiff;
    private BrokerConfigMcpReadTools tools;

    @BeforeEach
    void setUp() {
        configDiff = mock(ConfigDiffService.class);
        tools = new BrokerConfigMcpReadTools(
                mock(BrokerConfigService.class), mock(BrokerConfigApplyService.class), configDiff);
    }

    private static ConfigNodeValueView value(UUID id, String name, String value) {
        return new ConfigNodeValueView(id, name, value, value == null);
    }

    private static ConfigKeyView key(
            String key,
            String state,
            boolean drift,
            String majority,
            List<ConfigNodeValueView> values,
            List<ConfigNodeValueView> outliers,
            List<ConfigValueGroupView> groups) {
        return new ConfigKeyView(
                key,
                state,
                state.toLowerCase(),
                drift ? "DRIFT" : "EXPECTED",
                drift,
                values,
                majority,
                outliers,
                groups);
    }

    private ConfigDiffView view() {
        List<ConfigNodeValueView> all =
                List.of(value(A, "a", "ASYNCIO"), value(B, "b", "ASYNCIO"), value(C, "c", "NIO"));
        List<ConfigNodeValueView> split = List.of(value(A, "a", "1"), value(B, "b", "2"));
        ConfigKeyView outlier = key("/JournalType", "DIFFERENT", true, "ASYNCIO", all, List.of(all.get(2)), List.of());
        ConfigKeyView noMajority = key(
                "/GlobalMaxSize",
                "DIFFERENT",
                true,
                null,
                split,
                List.of(),
                List.of(
                        new ConfigValueGroupView("1", List.of(split.get(0))),
                        new ConfigValueGroupView("2", List.of(split.get(1)))));
        ConfigKeyView expected = key("/Name", "DIFFERENT", false, null, split, List.of(), List.of());
        ConfigKeyView same = key("/JournalFileSize", "SAME", false, "10", all, List.of(), List.of());
        return new ConfigDiffView(
                CLUSTER,
                List.of(
                        new ConfigNodeView(A, "a", true, true, false, null, null),
                        new ConfigNodeView(B, "b", true, true, false, null, null),
                        new ConfigNodeView(C, "c", false, false, false, "UNREACHABLE", "Connection refused")),
                true,
                List.of(new ConfigSectionView("broker", "Broker", List.of(outlier, noMajority, expected, same))),
                new ConfigSummaryView(2, 3, 1),
                1,
                1,
                List.of("a note"));
    }

    @Test
    void reportsOnlyTheDriftingKeysWithTheirOutliersAndTheUnavailableNodes() {
        when(configDiff.compare(eq(CLUSTER), any())).thenReturn(view());

        McpSchema.CallToolResult result = tools.artemisConfigDiff(CLUSTER.toString(), null);

        assertThat(result.isError()).isNotEqualTo(true);
        McpViews.ConfigDiff diff = (McpViews.ConfigDiff) result.structuredContent();
        assertThat(diff.driftKeys()).isEqualTo(2);
        assertThat(diff.driftNodes()).isEqualTo(3);
        assertThat(diff.expectedKeys()).isEqualTo(1);
        assertThat(diff.nodes()).containsExactly("a", "b");
        assertThat(diff.unavailable()).singleElement().satisfies(u -> {
            assertThat(u.node()).isEqualTo("c");
            assertThat(u.kind()).isEqualTo("UNREACHABLE");
            assertThat(u.reason()).isEqualTo("Connection refused");
        });
        assertThat(diff.notes()).containsExactly("a note");
        assertThat(diff.items())
                .extracting(McpViews.ConfigDrift::pointer)
                .containsExactly("broker/JournalType", "broker/GlobalMaxSize");
        McpViews.ConfigDrift outlier = diff.items().get(0);
        assertThat(outlier.majority()).isEqualTo("ASYNCIO");
        assertThat(outlier.differing()).containsExactly(new McpViews.ConfigNodeValue("c", "NIO"));
        // With no majority every node's value is listed.
        McpViews.ConfigDrift split = diff.items().get(1);
        assertThat(split.majority()).isNull();
        assertThat(split.differing())
                .containsExactly(new McpViews.ConfigNodeValue("a", "1"), new McpViews.ConfigNodeValue("b", "2"));
    }

    @Test
    void passesTheNamedNodesThroughAsASet() {
        when(configDiff.compare(eq(CLUSTER), any())).thenReturn(view());

        tools.artemisConfigDiff(CLUSTER.toString(), A + ", " + B);

        verify(configDiff).compare(CLUSTER, new LinkedHashSet<>(List.of(A, B)));
    }

    @Test
    void omittingNodesComparesEveryNode() {
        when(configDiff.compare(eq(CLUSTER), any())).thenReturn(view());

        tools.artemisConfigDiff(CLUSTER.toString(), " ");

        verify(configDiff).compare(CLUSTER, Set.of());
    }

    @Test
    void aSingleNamedNodeIsAMalformedCallNotAnExecutionFailure() {
        when(configDiff.compare(eq(CLUSTER), any()))
                .thenThrow(new IllegalArgumentException(
                        "Name at least two nodes to compare, or none to compare them all."));

        String cluster = CLUSTER.toString();
        String node = A.toString();

        assertThatThrownBy(() -> tools.artemisConfigDiff(cluster, node))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("at least two nodes");
    }
}
