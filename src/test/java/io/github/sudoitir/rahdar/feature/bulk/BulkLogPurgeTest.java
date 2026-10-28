package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionNodeCell;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionView;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A bulk purge acts on each subscription by its kind (ADR-0216): a queue through the queue purge on every node that
 * hosts it, a subscription of a partitioned log through the log purge and its gate, once for the cluster. The
 * subscriptions come from a mocked roll-up, since one cluster here is one system.
 */
class BulkLogPurgeTest extends BulkTestSupport {

    private static final String GROUP_TOPIC = "kafka.group-topic";

    @MockitoBean
    CrossNodeAggregator aggregator;

    @MockitoBean
    MessageService messages;

    @MockitoBean
    ClusterKinds kinds;

    @Test
    void aMixOfGroupsAndAQueueIsActedOnByKindAndAnActiveGroupIsRefusedAtPlanTime() {
        when(kinds.partitioned(eq(clusterId), eq(GROUP_TOPIC))).thenReturn(true);
        when(aggregator.allQueues(clusterId))
                .thenReturn(List.of(
                        group("g-empty", "t", 10, 0),
                        group("g-active", "t", 20, 2),
                        group("g-rebalancing", "t", 30, 0),
                        group("g-empty", "u", 5, 0),
                        queue("orders.q", 4)));
        when(messages.purgeLog(clusterId, ResourceId.parse("kafka.group-topic/g-empty/t"), false))
                .thenReturn(new Attempt.Ok<>(new MessageService.Outcome.Affected(10, null)));
        when(messages.purgeLog(clusterId, ResourceId.parse("kafka.group-topic/g-empty/u"), false))
                .thenReturn(new Attempt.Ok<>(new MessageService.Outcome.Affected(5, null)));
        when(messages.purgeLog(clusterId, ResourceId.parse("kafka.group-topic/g-rebalancing/t"), false))
                .thenThrow(new ConflictException("group-rebalancing", "The group g-rebalancing is rebalancing."));
        when(messages.purge(clusterId, "orders.q", nodeA, false, false))
                .thenReturn(new Attempt.Ok<>(new MessageService.Outcome.Affected(4, nodeA)));

        BulkRunDetailView preview = bulk.preview(
                clusterId,
                new BulkPreviewRequest(
                        BulkOperation.PURGE,
                        List.of(
                                "kafka.group-topic/g-empty/t",
                                "kafka.group-topic/g-empty/u",
                                "kafka.group-topic/g-active/t",
                                "kafka.group-topic/g-rebalancing/t",
                                "artemis.queue/orders.q"),
                        null,
                        false));

        BulkItemView active = item(preview, "kafka.group-topic/g-active/t");
        assertThat(active.status()).isEqualTo(BulkItemStatus.REFUSED);
        assertThat(active.error()).contains("2 members");
        assertThat(item(preview, "kafka.group-topic/g-empty/t").warning())
                .contains("skipping 10 records")
                .contains("not deleted");

        bulk.execute(
                clusterId,
                preview.run().id(),
                new BulkExecuteRequest(preview.run().planHash(), false, true));
        BulkRunDetailView run = awaitFinished(preview.run().id());

        // Two groups reading one name stay two items.
        assertThat(item(run, "kafka.group-topic/g-empty/t").status()).isEqualTo(BulkItemStatus.SUCCEEDED);
        assertThat(item(run, "kafka.group-topic/g-empty/t").affected()).isEqualTo(10L);
        assertThat(item(run, "kafka.group-topic/g-empty/u").affected()).isEqualTo(5L);
        assertThat(item(run, "kafka.group-topic/g-active/t").status()).isEqualTo(BulkItemStatus.REFUSED);
        BulkItemView rebalancing = item(run, "kafka.group-topic/g-rebalancing/t");
        assertThat(rebalancing.status()).isEqualTo(BulkItemStatus.FAILED);
        assertThat(rebalancing.error()).contains("rebalancing");
        assertThat(item(run, "artemis.queue/orders.q").status()).isEqualTo(BulkItemStatus.SUCCEEDED);
        verify(messages, never())
                .purgeLog(eq(clusterId), eq(ResourceId.parse("kafka.group-topic/g-active/t")), eq(false));
    }

    @Test
    void aLogSubscriptionCannotBePausedOrDeleted() {
        when(kinds.partitioned(eq(clusterId), eq(GROUP_TOPIC))).thenReturn(true);
        when(aggregator.allQueues(clusterId)).thenReturn(List.of(group("g-empty", "t", 10, 0)));

        for (BulkOperation operation : List.of(BulkOperation.PAUSE, BulkOperation.RESUME, BulkOperation.DELETE)) {
            BulkRunDetailView preview = bulk.preview(
                    clusterId, new BulkPreviewRequest(operation, List.of("kafka.group-topic/g-empty/t"), null, false));

            assertThat(preview.items().getFirst().status()).isEqualTo(BulkItemStatus.REFUSED);
            assertThat(preview.items().getFirst().error()).contains("position in a log");
        }
    }

    private static BulkItemView item(BulkRunDetailView run, String id) {
        return run.items().stream()
                .filter(i -> i.subscription().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private BulkRunDetailView awaitFinished(UUID runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            BulkRunDetailView run = bulk.get(clusterId, runId);
            if (run.run().finishedAt() != null) {
                return run;
            }
            LockSupport.parkNanos(20_000_000);
        }
        throw new AssertionError("The run did not finish");
    }

    private SubscriptionView group(String group, String topic, long records, long members) {
        return view(GROUP_TOPIC + "/" + group + "/" + topic, GROUP_TOPIC, group, records, members);
    }

    private SubscriptionView queue(String name, long messages) {
        return view("artemis.queue/" + name, "artemis.queue", name, messages, 0);
    }

    private SubscriptionView view(String id, String kind, String name, long messages, long consumers) {
        return new SubscriptionView(
                id,
                kind,
                new KindName("Subscription", "Subscriptions"),
                name,
                "address/" + name,
                Map.of(),
                true,
                Backlog.exact(messages),
                consumers,
                0,
                0,
                1,
                1,
                false,
                List.of(new SubscriptionNodeCell(nodeA, "a", false, Instant.now(), messages, consumers, 0, 0, false)),
                List.of(),
                null);
    }
}
