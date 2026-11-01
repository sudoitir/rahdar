package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.ETAG;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.preview;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Nodes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.Change;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.NodeView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.NodesView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ShovelPage;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Bindings, shovels, federation links and nodes: read as the driver describes them, and never with a credential. */
class RabbitMqRoutesTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String BINDING = "rabbitmq.binding/%2F/events/orders/created";
    private static final String SHOVEL = "rabbitmq.shovel/%2F/mirror";

    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final RabbitMqOps ops = mock(RabbitMqOps.class);
    private final RabbitMqChanges changes = mock(RabbitMqChanges.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterConnection connection;
    private final RabbitMqBindings bindings;

    RabbitMqRoutesTest() {
        connection = RabbitMqFixtures.connection(query -> switch (query.kind()
                .orElseThrow()
                .value()) {
            case RabbitMqIds.BINDING ->
                List.of(
                        resource(
                                BINDING, Map.of(RabbitMqIds.FILTER, "created", RabbitMqIds.ARGUMENT_PREFIX + "x-a", 1)),
                        resource("rabbitmq.binding/%2F/events/audit/~", Map.of()));
            case RabbitMqIds.EXCHANGE_BINDING ->
                List.of(resource(
                        "rabbitmq.exchange-binding/%2F/events/archive/all", Map.of(RabbitMqIds.FILTER, "all")));
            case RabbitMqIds.SHOVEL ->
                List.of(resource(
                        SHOVEL,
                        Map.of(
                                StandardAttributes.ROUTE_SOURCE.name(),
                                        new RouteEndpoint.Remote(
                                                URI.create("amqps://user:secret@far.example:5671/v?x=1"), "q"),
                                StandardAttributes.ROUTE_TARGET.name(),
                                        new RouteEndpoint.Local(ResourceId.of(RabbitMqIds.CLASSIC_QUEUE, "/", "near")),
                                StandardAttributes.MANAGED_BY.name(), "configuration"),
                        List.of(new Condition(
                                ConditionTypes.CONNECTED, ConditionStatus.FALSE, "starting", "", Optional.empty()))));
            case RabbitMqIds.FEDERATION_LINK ->
                List.of(resource(
                        "rabbitmq.federation-link/%2F/up/exchange/events",
                        Map.of(
                                StandardAttributes.ROUTE_SOURCE.name(),
                                new RouteEndpoint.Remote(URI.create("amqp://up:5672"), "events")),
                        List.of(new Condition(
                                ConditionTypes.CONNECTED, ConditionStatus.TRUE, "", "", Optional.empty()))));
            default -> List.of();
        });
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        bindings = new RabbitMqBindings(gateway, access, ops, changes);
    }

    @Test
    void bindingsOfBothKindsAreListedAndFiltered() {
        BindingPage all = bindings.list(CLUSTER, "/", null, null, null, null);
        BindingPage bySource = bindings.list(CLUSTER, null, "events", "orders", null, null);
        BindingPage paged = bindings.list(CLUSTER, null, null, null, null, 2);

        verify(access, times(3)).requireCluster(CLUSTER, Permissions.DESTINATION_READ);
        assertThat(all.items()).hasSize(3);
        assertThat(bySource.items()).hasSize(1);
        assertThat(bySource.items().getFirst().routingKey()).isEqualTo("created");
        assertThat(bySource.items().getFirst().destinationType()).isEqualTo("queue");
        assertThat(bySource.items().getFirst().arguments()).containsEntry("x-a", "1");
        assertThat(bySource.items().getFirst().id()).isEqualTo(BINDING);
        assertThat(all.items())
                .filteredOn(b -> b.destinationType().equals("exchange"))
                .hasSize(1);
        assertThat(paged.items()).hasSize(2);
        assertThat(paged.next()).isEqualTo("2");
    }

    @Test
    void aBindingIsPreviewedAndCreatedByItsKeyOrTheEmptyKeyName() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.CREATE), any())).thenReturn(preview(ETAG, 0));
        BindingRequest keyed = new BindingRequest("/", "events", "orders", "queue", "created", Map.of("x-a", 1), "e1");
        BindingRequest keyless = new BindingRequest("/", "events", "archive", "exchange", null, null, "e2");

        ChangePreview preview = bindings.previewCreate(CLUSTER, keyed);
        bindings.create(CLUSTER, keyed);
        bindings.create(CLUSTER, keyless);

        verify(access, times(3)).requireCluster(CLUSTER, ResourcePermissions.ROUTE_WRITE);
        assertThat(preview.effect().detail()).startsWith("Bind queue orders to exchange events with key 'created'.");
        ArgumentCaptor<Change> change = ArgumentCaptor.forClass(Change.class);
        verify(changes, times(2)).apply(change.capture());
        assertThat(change.getAllValues().get(0)).isInstanceOf(RouteCreate.class);
        assertThat(change.getAllValues().get(0).id()).isEqualTo(BINDING);
        assertThat(change.getAllValues().get(0).attributes())
                .containsEntry(RabbitMqIds.FILTER, "created")
                .containsEntry(RabbitMqIds.ARGUMENT_PREFIX + "x-a", 1);
        assertThat(change.getAllValues().get(1).id()).isEqualTo("rabbitmq.exchange-binding/%2F/events/archive/~");
    }

    @Test
    void aBindingNeedsEverythingAndAKnownDestinationType() {
        assertThatThrownBy(
                        () -> bindings.create(CLUSTER, new BindingRequest("/", "e", null, "queue", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bindings.create(CLUSTER, new BindingRequest("/", "e", "q", "topic", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bindings.create(CLUSTER, new BindingRequest("/", "e", "q", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBindingOrAShovelIsDeletedByItsIdAndNothingElse() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 0));

        ChangePreview preview = bindings.previewDelete(CLUSTER, SHOVEL);
        bindings.delete(CLUSTER, BINDING, "e1");

        assertThat(preview.effect().detail()).startsWith("Delete shovel / / mirror.");
        ArgumentCaptor<Change> change = ArgumentCaptor.forClass(Change.class);
        verify(changes).apply(change.capture());
        assertThat(change.getValue()).isInstanceOf(RouteDelete.class);
        assertThatThrownBy(() -> bindings.delete(CLUSTER, "rabbitmq.vhost/orders", "e"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not one of");
        assertThatThrownBy(() -> bindings.delete(CLUSTER, "nonsense", "e"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a resource id");
    }

    @Test
    void aShovelNeverShowsACredential() {
        ShovelPage page = new RabbitMqShovels(gateway, access).list(CLUSTER, null, null, null);

        verify(access).requireCluster(CLUSTER, Permissions.DESTINATION_READ);
        assertThat(page.available()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().source()).isEqualTo("amqps://far.example:5671/v q");
        assertThat(page.items().getFirst().destination()).isEqualTo("/ / near");
        assertThat(page.items().getFirst().state()).isEqualTo("starting");
        assertThat(page.items().getFirst().managedBy()).isEqualTo("configuration");
        assertThat(page.toString()).doesNotContain("secret").doesNotContain("user");
        assertThat(RabbitMqShovels.withoutCredentials(URI.create("amqp://u:p@h/%2F?q=1")))
                .isEqualTo("amqp://h/%2F");
        assertThat(RabbitMqShovels.withoutCredentials(URI.create("amqp:///x"))).isEqualTo("amqp:///x");
    }

    @Test
    void shovelsAndLinksSayWhichPluginIsMissing() {
        BrokerException missing = new BrokerException(
                Status.NOT_FOUND, ErrorInfo.of("RESOURCE_GONE"), Dispatch.NOT_SENT, null, "no such path");
        Resources resources = mock(Resources.class);
        when(resources.read(any(ResourceQuery.class))).thenThrow(missing);
        ClusterConnection broken = mock(ClusterConnection.class);
        when(broken.resources()).thenReturn(resources);
        BrokerGateway other = mock(BrokerGateway.class);
        when(other.cluster(CLUSTER)).thenReturn(broken);

        ShovelPage shovels = new RabbitMqShovels(other, access).list(CLUSTER, "/", null, null);
        FederationPage links = new RabbitMqFederation(other, access).list(CLUSTER, "/", null, null);

        assertThat(shovels.available()).isFalse();
        assertThat(shovels.remedy()).contains("rabbitmq_shovel_management");
        assertThat(links.available()).isFalse();
        assertThat(links.remedy()).contains("rabbitmq_federation_management");
    }

    @Test
    void anotherFailureOfTheListingIsNotHiddenAsAMissingPlugin() {
        BrokerException down = new BrokerException(
                Status.UNAVAILABLE, ErrorInfo.of("UNREACHABLE"), Dispatch.NOT_SENT, null, "refused");
        Resources resources = mock(Resources.class);
        when(resources.read(any(ResourceQuery.class))).thenThrow(down);
        ClusterConnection broken = mock(ClusterConnection.class);
        when(broken.resources()).thenReturn(resources);
        BrokerGateway other = mock(BrokerGateway.class);
        when(other.cluster(CLUSTER)).thenReturn(broken);

        assertThatThrownBy(() -> new RabbitMqFederation(other, access).list(CLUSTER, null, null, null))
                .isNotInstanceOf(BrokerException.class);
    }

    @Test
    void aFederationLinkHasItsUpstreamAndStatus() {
        FederationPage page = new RabbitMqFederation(gateway, access).list(CLUSTER, null, null, null);

        assertThat(page.available()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().upstream()).isEqualTo("up");
        assertThat(page.items().getFirst().type()).isEqualTo("exchange");
        assertThat(page.items().getFirst().name()).isEqualTo("events");
        assertThat(page.items().getFirst().status()).isEqualTo("running");
        assertThat(page.items().getFirst().source()).isEqualTo("amqp://up:5672 events");
    }

    @Test
    void nodesShowTheirAlarmsPartitionsAndListeners() {
        Nodes nodes = mock(Nodes.class);
        when(connection.nodes()).thenReturn(nodes);
        when(nodes.describe(Scope.whole()))
                .thenReturn(Partial.complete(
                        List.of(node("rabbit@a", true, true, false), node("rabbit@b", false, false, true)), List.of()));

        NodesView view = new RabbitMqNodes(gateway, access).list(CLUSTER);

        verify(access).requireCluster(CLUSTER, Permissions.CLUSTER_READ);
        assertThat(view.clusterName()).isEqualTo("prod");
        NodeView a = view.nodes().get(0);
        NodeView b = view.nodes().get(1);
        assertThat(a.name()).isEqualTo("rabbit@a");
        assertThat(a.version()).isEqualTo("4.4.0");
        assertThat(a.running()).isTrue();
        assertThat(a.alarms()).containsExactly("memory");
        assertThat(a.partitions()).isEmpty();
        assertThat(a.listeners()).hasSize(1);
        assertThat(a.listeners().getFirst().protocol()).isEqualTo("amqp");
        assertThat(a.listeners().getFirst().port()).isEqualTo(5672);
        assertThat(b.running()).isFalse();
        assertThat(b.alarms()).isEmpty();
        assertThat(b.partitions()).containsExactly("rabbit@a", "rabbit@c");
    }

    private static NodeDescription node(String name, boolean running, boolean memoryAlarm, boolean partitioned) {
        return new NodeDescription(
                NodeId.of(name),
                name,
                new Membership(Optional.of("prod"), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(
                        Endpoint.of(Plane.DATA, "amqp", "host:5672"),
                        Endpoint.of(Plane.MANAGEMENT, "http", "http://host:15672")),
                Optional.of("4.4.0"),
                List.of(
                        new Condition(
                                ConditionTypes.FULL,
                                memoryAlarm ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                                memoryAlarm ? "mem_alarm" : "",
                                "",
                                Optional.empty()),
                        new Condition(
                                ConditionTypes.SERVING,
                                running ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                                "",
                                "",
                                Optional.empty()),
                        new Condition(
                                ConditionTypes.OUT_OF_SYNC,
                                partitioned ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                                "",
                                partitioned ? "The node is partitioned from rabbit@a, rabbit@c." : "",
                                Optional.empty())),
                Attributes.empty(),
                Optional.of(Instant.EPOCH));
    }
}
