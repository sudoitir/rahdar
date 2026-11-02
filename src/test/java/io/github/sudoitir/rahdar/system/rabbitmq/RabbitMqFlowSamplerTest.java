package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowMember;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowRoute;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Flow on RabbitMQ: consumers with their connection, bindings and exchange bindings as routes, no producers. */
class RabbitMqFlowSamplerTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final RabbitMqFlowSampler sampler;

    RabbitMqFlowSamplerTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        sampler = new RabbitMqFlowSampler(gateway);
    }

    private void kind(String kind, Resource... found) {
        when(resources.read(any(ResourceQuery.class))).thenAnswer(inv -> {
            ResourceQuery q = inv.getArgument(0);
            List<Resource> items = q.kind().map(k -> k.value().equals(kind)).orElse(false) ? List.of(found) : List.of();
            return Partial.complete(new ResourcePage(items, Optional.empty()), List.of());
        });
    }

    private static Resource resource(String kind, Attributes attributes, String... path) {
        return new Resource(ResourceId.of(kind, path), attributes, List.of(), List.of(), Instant.now());
    }

    @Test
    void aBindingIsARouteFromItsExchangeToItsQueueUnderItsRoutingKey() {
        kind(
                "rabbitmq.binding",
                resource(
                        "rabbitmq.binding",
                        Attributes.builder().putRaw("openbroker.filter", "eu.#").build(),
                        "/",
                        "orders",
                        "orders.q",
                        "eu.#"));

        FlowSample sample = sampler.sample(CLUSTER, UUID.randomUUID(), 100);

        assertThat(sample.producers()).isEmpty();
        assertThat(sample.routes()).hasSize(1);
        FlowRoute route = sample.routes().getFirst();
        assertThat(route.kind()).isEqualTo("BINDING");
        assertThat(route.source()).isEqualTo("%2F/orders");
        assertThat(route.target()).isEqualTo("%2F/orders.q");
        assertThat(route.filter()).isEqualTo("eu.#");
    }

    @Test
    void anExchangeBindingIsAForwardBetweenTwoExchanges() {
        kind(
                "rabbitmq.exchange-binding",
                resource("rabbitmq.exchange-binding", Attributes.empty(), "/", "a", "b", "k"));

        FlowSample sample = sampler.sample(CLUSTER, UUID.randomUUID(), 100);

        assertThat(sample.routes()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("EXCHANGE_BINDING");
            assertThat(r.source()).isEqualTo("%2F/a");
            assertThat(r.target()).isEqualTo("%2F/b");
        });
    }

    @Test
    void aConsumerIsNamedAfterItsConnectionAndJoinsItsQueue() {
        ResourceId queue = ResourceId.of("rabbitmq.classic-queue", "/", "orders.q");
        ResourceId conn = ResourceId.of("rabbitmq.connection", "10.0.0.5:4000 -> 10.0.0.2:5672");
        Resource consumer = resource(
                "rabbitmq.consumer",
                Attributes.builder()
                        .put(StandardAttributes.SUBSCRIPTION, queue)
                        .put(StandardAttributes.CONNECTION, conn)
                        .build(),
                "/",
                "ch1",
                "tag1");
        Resource owner = resource(
                "rabbitmq.connection",
                Attributes.builder()
                        .put(StandardAttributes.CLIENT_ID, "billing-svc")
                        .put(StandardAttributes.PRINCIPAL, "billing")
                        .put(StandardAttributes.REMOTE_ADDRESS, "10.0.0.5:4000")
                        .build(),
                "10.0.0.5:4000 -> 10.0.0.2:5672");
        Map<String, Resource> byKind = Map.of("rabbitmq.consumer", consumer, "rabbitmq.connection", owner);
        when(resources.read(any(ResourceQuery.class))).thenAnswer(inv -> {
            ResourceQuery q = inv.getArgument(0);
            Resource r = byKind.get(q.kind().orElseThrow().value());
            return Partial.complete(new ResourcePage(r == null ? List.of() : List.of(r), Optional.empty()), List.of());
        });

        FlowSample sample = sampler.sample(CLUSTER, UUID.randomUUID(), 100);

        assertThat(sample.consumers()).singleElement().satisfies((FlowMember m) -> {
            assertThat(m.clientId()).isEqualTo("billing-svc");
            assertThat(m.user()).isEqualTo("billing");
            assertThat(m.subscription()).isEqualTo("%2F/orders.q");
        });
        assertThat(sampler.clusterWide()).isTrue();
        assertThat(sampler.producersNote()).isNotBlank();
    }
}
