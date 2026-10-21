package io.github.sudoitir.rahdar.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.plugin.Contract;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor.Kind;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Startup validation of the installed modules (feature-modules spec). */
class FeatureContractTest {

    private static FeatureDescriptor.FeatureDescriptorBuilder feature(String id) {
        return FeatureDescriptor.builder().id(id).title(id).kind(Kind.FEATURE);
    }

    private static FeatureRegistry registry(MockEnvironment env, FeatureDescriptor... descriptors) {
        return new FeatureRegistry(new InstalledFeatures(List.of(descriptors)), env, event -> {});
    }

    @Test
    void featuresAreEnabledByDefaultAndDisabledByProperty() {
        var env = new MockEnvironment().withProperty("rahdar.features.sql.enabled", "false");
        var registry = registry(env, feature("queues").build(), feature("sql").build());

        assertThat(registry.isEnabled("queues")).isTrue();
        assertThat(registry.isEnabled("sql")).isFalse();
        assertThat(registry.enabled()).extracting(FeatureDescriptor::id).containsExactly("queues");
        assertThat(registry.all()).extracting(FeatureDescriptor::id).containsExactly("queues", "sql");
    }

    @Test
    void mismatchedContractVersionIsRefusedNamingBothVersions() {
        assertThatThrownBy(() -> registry(
                        new MockEnvironment(),
                        feature("rr").contract(Contract.VERSION + 1).build()))
                .hasMessageContaining("'rr'")
                .hasMessageContaining("version " + (Contract.VERSION + 1))
                .hasMessageContaining("version " + Contract.VERSION);
    }

    @Test
    void disablingARequiredModuleIsRefused() {
        var env = new MockEnvironment().withProperty("rahdar.features.clusters.enabled", "false");
        assertThatThrownBy(
                        () -> registry(env, feature("clusters").required(true).build()))
                .hasMessageContaining("'clusters' is required and cannot be disabled");
    }

    @Test
    void disablingAFeatureAnEnabledFeatureRequiresIsRefused() {
        var env = new MockEnvironment().withProperty("rahdar.features.routing.enabled", "false");
        assertThatThrownBy(() -> registry(
                        env,
                        feature("routing").build(),
                        feature("brokerconfig").require("routing").build()))
                .hasMessageContaining("'brokerconfig'")
                .hasMessageContaining("'routing'");
    }

    @Test
    void aDisabledFeatureMayRequireAnotherDisabledFeature() {
        var env = new MockEnvironment()
                .withProperty("rahdar.features.routing.enabled", "false")
                .withProperty("rahdar.features.brokerconfig.enabled", "false");
        var registry = registry(
                env,
                feature("routing").build(),
                feature("brokerconfig").require("routing").build());
        assertThat(registry.enabled()).isEmpty();
    }

    @Test
    void duplicateContributionsAreRefused() {
        var env = new MockEnvironment();
        assertThatThrownBy(() -> registry(
                        env,
                        feature("a")
                                .permission(PermissionDef.cluster("subscription:create", "x"))
                                .build(),
                        feature("b")
                                .permission(PermissionDef.cluster("subscription:create", "y"))
                                .build()))
                .hasMessageContaining("permission 'subscription:create' is declared by both 'a' and 'b'");
        assertThatThrownBy(() -> registry(
                        env,
                        feature("a").settingKey("rr.sweep-interval").build(),
                        feature("b").settingKey("rr.sweep-interval").build()))
                .hasMessageContaining("setting 'rr.sweep-interval'");
        assertThatThrownBy(() -> registry(
                        env,
                        feature("a").streamTopic(TopicDef.signal("queues")).build(),
                        feature("b").streamTopic(TopicDef.signal("queues")).build()))
                .hasMessageContaining("stream topic 'queues'");
        assertThatThrownBy(() -> registry(
                        env,
                        feature("a").mcpTool(tool("diagnose")).build(),
                        feature("b").mcpTool(tool("diagnose")).build()))
                .hasMessageContaining("MCP tool 'diagnose'");
        assertThatThrownBy(
                        () -> registry(env, feature("a").build(), feature("a").build()))
                .hasMessageContaining("declared twice");
    }

    @Test
    void aDisabledFeaturesApiPrefixesIdentifyIt() {
        var env = new MockEnvironment().withProperty("rahdar.features.sql.enabled", "false");
        var registry = registry(
                env,
                feature("sql").apiPrefix("/api/v1/clusters/{clusterId}/sql").build(),
                feature("queues")
                        .apiPrefix("/api/v1/clusters/{clusterId}/queues")
                        .build());

        assertThat(registry.disabledOwnerOf("/api/v1/clusters/abc/sql/query"))
                .map(FeatureDescriptor::id)
                .contains("sql");
        assertThat(registry.disabledOwnerOf("/api/v1/clusters/abc/sql")).isPresent();
        assertThat(registry.disabledOwnerOf("/api/v1/clusters/abc/queues")).isEmpty();
    }

    @Test
    void theMostSpecificPrefixDecidesTheOwner() {
        var env = new MockEnvironment().withProperty("rahdar.features.queues.enabled", "false");
        var registry = registry(
                env,
                feature("queues")
                        .apiPrefix("/api/v1/clusters/{clusterId}/addresses")
                        .build(),
                feature("resources")
                        .apiPrefix("/api/v1/clusters/{clusterId}/addresses/{address}/consumers")
                        .build());

        assertThat(registry.disabledOwnerOf("/api/v1/clusters/c/addresses/orders/consumers/close"))
                .as("served by the enabled resources feature")
                .isEmpty();
        assertThat(registry.disabledOwnerOf("/api/v1/clusters/c/addresses/orders"))
                .map(FeatureDescriptor::id)
                .contains("queues");
    }

    private static McpToolDef tool(String name) {
        return new McpToolDef(name, McpToolDef.Posture.READ, "s", List.of());
    }
}
