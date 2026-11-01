package io.github.sudoitir.rahdar.system.rabbitmq;

import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.ETAG;
import static io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFixtures.change;
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

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.Change;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceUpdate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ExchangeRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.QueueRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Virtual hosts are listed with their queues and previewed with everything a deletion would take. */
class RabbitMqVhostsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final RabbitMqOps ops = mock(RabbitMqOps.class);
    private final RabbitMqChanges changes = mock(RabbitMqChanges.class);
    private final List<Resource> queues = new ArrayList<>();
    private final RabbitMqVhosts vhosts;

    RabbitMqVhostsTest() {
        queues.add(queue("rabbitmq.classic-queue/orders/a", 5));
        queues.add(queue("rabbitmq.quorum-queue/orders/b", 7));
        queues.add(queue("rabbitmq.stream/billing/c", 1));
        ClusterConnection connection = RabbitMqFixtures.connection(this::answer);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        vhosts = new RabbitMqVhosts(gateway, access, ops, changes);
    }

    private static Resource queue(String id, long ready) {
        return resource(id, Map.of(MetricKeys.BACKLOG.name(), MetricValue.estimate(ready)));
    }

    private List<Resource> answer(ResourceQuery query) {
        if (query.resourceClass().isPresent()) {
            String vhost = query.fieldSelector().get(RabbitMqIds.VHOST_SELECTOR);
            return queues.stream()
                    .filter(q ->
                            vhost == null || q.id().path().segments().getFirst().equals(vhost))
                    .toList();
        }
        return List.of(
                resource(
                        "rabbitmq.vhost/orders",
                        Map.of(
                                RabbitMqIds.DESCRIPTION, "Orders",
                                RabbitMqIds.TAGS, "prod, eu",
                                RabbitMqIds.DEFAULT_QUEUE_TYPE, "quorum")),
                resource("rabbitmq.vhost/empty", Map.of()));
    }

    @Test
    void aVhostIsListedWithItsQueuesAndMessages() {
        VhostPage page = vhosts.list(CLUSTER, null, null);

        verify(access).requireCluster(CLUSTER, RabbitMqModule.NAMESPACE_READ);
        assertThat(page.countsComplete()).isTrue();
        assertThat(page.items()).extracting(v -> v.name()).containsExactly("orders", "empty");
        assertThat(page.items().getFirst().queues()).isEqualTo(2);
        assertThat(page.items().getFirst().messages()).isEqualTo(12);
        assertThat(page.items().getFirst().tags()).containsExactly("prod", "eu");
        assertThat(page.items().getFirst().defaultQueueType()).isEqualTo("quorum");
        assertThat(page.items().get(1).queues()).isZero();
        assertThat(page.items().get(1).defaultQueueType()).isNull();
        assertThat(page.items().get(1).tags()).isEmpty();
    }

    @Test
    void aDeletePreviewNamesEveryQueueAndItsMessages() {
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 12));

        RabbitMqChangePreview preview = vhosts.previewDelete(CLUSTER, "orders");

        verify(access).requireCluster(CLUSTER, RabbitMqModule.NAMESPACE_DELETE);
        assertThat(preview.effect().count()).isEqualTo(12);
        assertThat(preview.effect().unit()).isEqualTo("messages");
        assertThat(preview.effect().lines())
                .containsExactly(
                        "It holds 2 queues, and they go with it.",
                        "Queue a: 5 messages ready",
                        "Queue b: 7 messages ready");
        assertThat(preview.effect().detail()).contains("everything in it").contains("RabbitMQ does not tell");
        assertThat(preview.identityBasis()).isEqualTo("CONFIGURATION");
    }

    @Test
    void aLongListOfQueuesIsCutAndSaysHowManyMore() {
        for (int i = 0; i < 25; i++) {
            queues.add(queue("rabbitmq.classic-queue/orders/q" + i, 0));
        }
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.DELETE), any())).thenReturn(preview(ETAG, 0));

        List<String> lines = vhosts.previewDelete(CLUSTER, "orders").effect().lines();

        assertThat(lines).hasSize(22);
        assertThat(lines.getLast()).isEqualTo("and 7 more.");
    }

    @Test
    void aCreateOrChangePreviewIsAChangeOfConfiguration() {
        when(ops.preview(eq(CLUSTER), any(), any(), any()))
                .thenReturn(preview(ETAG, 0, change(RabbitMqIds.DESCRIPTION, null, "Orders")));
        VhostRequest request = new VhostRequest("orders", "Orders", List.of("a", "b"), "quorum", null);

        RabbitMqChangePreview created = vhosts.previewCreate(CLUSTER, request);
        RabbitMqChangePreview changed = vhosts.previewUpdate(CLUSTER, "orders", request);

        verify(access, times(2)).requireCluster(CLUSTER, RabbitMqModule.NAMESPACE_WRITE);
        assertThat(created.effect().unit()).isEqualTo("changes");
        assertThat(created.effect().changes()).hasSize(1);
        assertThat(created.effect().detail()).startsWith("Create virtual host orders.");
        assertThat(changed.effect().detail()).startsWith("Change virtual host orders.");
    }

    @Test
    void theRealCallsAreNamespaceChangesCarryingTheEtag() {
        VhostRequest request = new VhostRequest("orders", "Orders", List.of("a", "b"), "quorum", "e1");

        vhosts.create(CLUSTER, request);
        vhosts.update(CLUSTER, "orders", new VhostRequest(null, "New", null, null, "e2"));
        vhosts.delete(CLUSTER, "orders", "e3");

        ArgumentCaptor<Change> change = ArgumentCaptor.forClass(Change.class);
        verify(changes, times(3)).apply(change.capture());
        assertThat(change.getAllValues().get(0)).isInstanceOf(NamespaceCreate.class);
        assertThat(change.getAllValues().get(0).attributes())
                .containsEntry(RabbitMqIds.TAGS, "a,b")
                .containsEntry(RabbitMqIds.DEFAULT_QUEUE_TYPE, "quorum");
        assertThat(change.getAllValues().get(1)).isInstanceOf(NamespaceUpdate.class);
        assertThat(change.getAllValues().get(1).attributes()).containsOnlyKeys(RabbitMqIds.DESCRIPTION);
        assertThat(change.getAllValues().get(2)).isInstanceOf(NamespaceDelete.class);
        assertThat(change.getAllValues()).extracting(Change::etag).containsExactly("e1", "e2", "e3");
        verify(access).requireCluster(CLUSTER, RabbitMqModule.NAMESPACE_DELETE);
    }

    @Test
    void aVhostNeedsAName() {
        assertThatThrownBy(() -> vhosts.create(CLUSTER, new VhostRequest(" ", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- queues and exchanges -----------------------------------------------------------------------------------

    @Test
    void aQueueIsCreatedOfTheKindItsTypeNames() {
        RabbitMqQueues creates = new RabbitMqQueues(access, ops, changes);
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.CREATE), any())).thenReturn(preview(ETAG, 0));
        QueueRequest request =
                new QueueRequest("/", "q", "stream", null, true, Map.of("x-max-length-bytes", 100), null);

        RabbitMqChangePreview preview = creates.preview(CLUSTER, request);
        creates.create(CLUSTER, request);

        verify(access, times(2)).requireCluster(CLUSTER, ResourcePermissions.SUBSCRIPTION_CREATE);
        assertThat(preview.effect().detail()).startsWith("Create stream queue q in virtual host /.");
        ArgumentCaptor<Map<String, Object>> attributes = ArgumentCaptor.captor();
        verify(changes)
                .create(
                        eq(CLUSTER),
                        eq(ResourceId.of(RabbitMqIds.STREAM, "/", "q")),
                        attributes.capture(),
                        any(),
                        eq("CREATE_SUBSCRIPTION"),
                        eq("SUBSCRIPTION"));
        assertThat(attributes.getValue())
                .containsEntry(RabbitMqIds.DURABLE, true)
                .containsEntry(RabbitMqIds.AUTO_DELETE, true)
                .containsEntry(RabbitMqIds.ARGUMENT_PREFIX + "x-max-length-bytes", 100);
    }

    @Test
    void aQueueNeedsAKnownTypeAVhostAndAName() {
        RabbitMqQueues creates = new RabbitMqQueues(access, ops, changes);

        assertThatThrownBy(() -> creates.create(CLUSTER, new QueueRequest("/", "q", "lazy", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> creates.create(CLUSTER, new QueueRequest(null, "q", "classic", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RabbitMqIds.queueKind("classic")).isEqualTo(RabbitMqIds.CLASSIC_QUEUE);
        assertThat(RabbitMqIds.queueKind("quorum")).isEqualTo(RabbitMqIds.QUORUM_QUEUE);
    }

    @Test
    void anExchangeIsCreatedAsADestination() {
        RabbitMqExchanges creates = new RabbitMqExchanges(access, ops, changes);
        when(ops.preview(eq(CLUSTER), any(), eq(Verb.CREATE), any())).thenReturn(preview(ETAG, 0));
        ExchangeRequest request = new ExchangeRequest("/", "events", "topic", true, null, null, null, null);

        creates.preview(CLUSTER, request);
        creates.create(CLUSTER, request);

        verify(access, times(2)).requireCluster(CLUSTER, ResourcePermissions.DESTINATION_CREATE);
        ArgumentCaptor<Map<String, Object>> attributes = ArgumentCaptor.captor();
        verify(changes)
                .create(
                        eq(CLUSTER),
                        eq(ResourceId.of(RabbitMqIds.EXCHANGE, "/", "events")),
                        attributes.capture(),
                        any(),
                        eq("CREATE_DESTINATION"),
                        eq("DESTINATION"));
        assertThat(attributes.getValue())
                .containsEntry(RabbitMqIds.EXCHANGE_TYPE, "topic")
                .containsEntry(RabbitMqIds.INTERNAL, false);
        assertThatThrownBy(() ->
                        creates.create(CLUSTER, new ExchangeRequest("/", "e", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        creates.create(CLUSTER, new ExchangeRequest("/", " ", "fanout", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
