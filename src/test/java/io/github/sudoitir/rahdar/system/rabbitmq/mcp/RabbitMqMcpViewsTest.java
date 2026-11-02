package io.github.sudoitir.rahdar.system.rabbitmq.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqAccessRules;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqBindings;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFederation;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqModule;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqNodes;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqPolicies;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqShovels;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqVhosts;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationLink;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.NodesView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PolicyPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Shovel;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ShovelPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostPage;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RabbitMqMcpViewsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final RabbitMqNodes nodes = mock(RabbitMqNodes.class);
    private final RabbitMqVhosts vhosts = mock(RabbitMqVhosts.class);
    private final RabbitMqBindings bindings = mock(RabbitMqBindings.class);
    private final RabbitMqPolicies policies = mock(RabbitMqPolicies.class);
    private final RabbitMqAccessRules permissions = mock(RabbitMqAccessRules.class);
    private final RabbitMqShovels shovels = mock(RabbitMqShovels.class);
    private final RabbitMqFederation federation = mock(RabbitMqFederation.class);
    private final RabbitMqMcpReadTools tools =
            new RabbitMqMcpReadTools(nodes, vhosts, bindings, policies, permissions, shovels, federation);

    private static ShovelPage shovelPage() {
        return new ShovelPage(
                true,
                null,
                List.of(new Shovel(
                        "s1", "/", "mirror", "running", "amqp://user:secret@remote:5672/%2F", "queue:orders", null)),
                null);
    }

    private static FederationPage federationPage() {
        return new FederationPage(
                true,
                null,
                List.of(new FederationLink("/", "up", "exchange", "ex", "running", "amqps://u:p@far.example/vh")),
                null);
    }

    @Test
    void anAddressLosesItsCredentialsAndKeepsTheRest() {
        assertThat(RabbitMqMcpViews.address("amqp://user:secret@remote:5672/%2F"))
                .isEqualTo("amqp://remote:5672/%2F");
        assertThat(RabbitMqMcpViews.address("amqp://remote")).isEqualTo("amqp://remote");
        assertThat(RabbitMqMcpViews.address("queue:orders")).isEqualTo("queue:orders");
        assertThat(RabbitMqMcpViews.address(null)).isNull();
    }

    @Test
    void aPageSizeIsBounded() {
        assertThat(RabbitMqMcpViews.limit(null)).isNull();
        assertThat(RabbitMqMcpViews.limit(0)).isEqualTo(1);
        assertThat(RabbitMqMcpViews.limit(5000)).isEqualTo(RabbitMqMcpViews.PAGE);
        assertThat(RabbitMqMcpViews.limit(25)).isEqualTo(25);
    }

    @Test
    void shovelsAndLinksAreScrubbed() {
        var shovel = RabbitMqMcpViews.shovels(shovelPage()).items().getFirst();
        assertThat(shovel.source()).isEqualTo("amqp://remote:5672/%2F").doesNotContain("secret");
        assertThat(shovel.destination()).isEqualTo("queue:orders");
        assertThat(RabbitMqMcpViews.federation(federationPage())
                        .items()
                        .getFirst()
                        .source())
                .isEqualTo("amqps://far.example/vh");
    }

    @Test
    void bothKindsAreAnsweredWhenNoneIsNamed() {
        when(shovels.list(eq(CLUSTER), any(), any(), any())).thenReturn(shovelPage());
        when(federation.list(eq(CLUSTER), any(), any(), any())).thenReturn(federationPage());

        McpSchema.CallToolResult result = tools.rabbitmqListShovels(CLUSTER.toString(), null, null, null, null);

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(result.content().toString())
                .contains("mirror", "far.example")
                .doesNotContain("secret");
    }

    @Test
    void oneKindIsPagedByItsCursor() {
        when(shovels.list(CLUSTER, "/", "c1", 100)).thenReturn(shovelPage());
        when(federation.list(CLUSTER, "/", "c2", 7)).thenReturn(federationPage());

        tools.rabbitmqListShovels(CLUSTER.toString(), "shovel", "/", "c1", 9999);
        tools.rabbitmqListShovels(CLUSTER.toString(), " Federation ", "/", "c2", 7);

        verify(shovels).list(CLUSTER, "/", "c1", 100);
        verify(federation).list(CLUSTER, "/", "c2", 7);
    }

    @Test
    void anUnknownKindIsAMalformedCall() {
        assertThatThrownBy(() -> tools.rabbitmqListShovels(CLUSTER.toString(), "queue", null, null, null))
                .hasMessageContaining("kind");
    }

    @Test
    void everyReadToolPassesItsArgumentsToTheService() {
        when(nodes.list(CLUSTER)).thenReturn(new NodesView(null, List.of()));
        when(vhosts.list(CLUSTER, "c", 3)).thenReturn(new VhostPage(List.of(), true, null));
        when(bindings.list(CLUSTER, "/", "src", "dst", "c", 4)).thenReturn(new BindingPage(List.of(), null));
        when(policies.list(CLUSTER, "/", "c", 5)).thenReturn(new PolicyPage(List.of(), null));
        when(permissions.list(CLUSTER, "/", "guest", "c", 6)).thenReturn(new PermissionPage(List.of(), null));
        tools.rabbitmqNodes(CLUSTER.toString());
        tools.rabbitmqListVhosts(CLUSTER.toString(), "c", 3);
        tools.rabbitmqListBindings(CLUSTER.toString(), "/", "src", "dst", "c", 4);
        tools.rabbitmqListPolicies(CLUSTER.toString(), "/", "c", 5);
        tools.rabbitmqListPermissions(CLUSTER.toString(), "/", "guest", "c", 6);

        verify(nodes).list(CLUSTER);
        verify(vhosts).list(CLUSTER, "c", 3);
        verify(bindings).list(CLUSTER, "/", "src", "dst", "c", 4);
        verify(policies).list(CLUSTER, "/", "c", 5);
        verify(permissions).list(CLUSTER, "/", "guest", "c", 6);
    }

    @Test
    void everyToolIsAReadForRabbitMqOnly() {
        assertThat(RabbitMqMcpToolDefs.ALL).hasSize(6).allSatisfy(def -> {
            assertThat(def.posture()).isEqualTo(McpToolDef.Posture.READ);
            assertThat(def.system()).isEqualTo(RabbitMqModule.SYSTEM);
            assertThat(def.name()).startsWith("rabbitmq_");
        });
        assertThat(RabbitMqModule.DESCRIPTOR.mcpTools()).isEqualTo(RabbitMqMcpToolDefs.ALL);
    }
}
