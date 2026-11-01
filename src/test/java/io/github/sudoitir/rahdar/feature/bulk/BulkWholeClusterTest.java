package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionNodeCell;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionView;
import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A bulk delete on a cluster read as one deletes each subscription by its resource id, through the same command as a
 * single delete (ADR-0224), where a per-node cluster deletes it by name.
 */
class BulkWholeClusterTest extends BulkTestSupport {

    private static final String ID = "rabbitmq.queue.classic/vh/orders";

    @MockitoBean
    CrossNodeAggregator aggregator;

    @MockitoBean
    QueueLifecycleService lifecycle;

    @MockitoBean
    ClusterKinds kinds;

    @Test
    void aSubscriptionOfAClusterReadAsOneIsDeletedByItsId() {
        when(kinds.readAsOne(clusterId)).thenReturn(true);
        when(aggregator.allQueues(clusterId)).thenReturn(List.of(view(ID, "orders", 3)));
        Target target = new Target(ResourceId.parse(ID), ResourceClass.SUBSCRIPTION);
        when(lifecycle.deleteQueue(clusterId, target, false, false, false))
                .thenReturn(new Attempt.Ok<>(new LifecycleOutcome(
                        false,
                        100,
                        false,
                        List.of(new NodeOutcome(null, "whole cluster", NodeStatus.APPLIED, 3L, null)))));

        BulkRunDetailView preview =
                bulk.preview(clusterId, new BulkPreviewRequest(BulkOperation.DELETE, List.of(ID), null, false));
        bulk.execute(
                clusterId,
                preview.run().id(),
                new BulkExecuteRequest(preview.run().planHash(), false, true));
        BulkRunDetailView run = awaitFinished(preview.run().id());

        assertThat(run.items().getFirst().status()).isEqualTo(BulkItemStatus.SUCCEEDED);
        assertThat(run.items().getFirst().affected()).isEqualTo(3L);
        verify(lifecycle).deleteQueue(clusterId, target, false, false, false);
        verify(lifecycle, never()).deleteQueue(clusterId, "orders", false, false, false);
    }

    @Test
    void aSubscriptionOfAClusterWithNodesIsStillDeletedByName() {
        when(kinds.readAsOne(clusterId)).thenReturn(false);
        when(aggregator.allQueues(clusterId)).thenReturn(List.of(view("artemis.queue/orders", "orders", 3)));
        when(lifecycle.deleteQueue(clusterId, "orders", false, false, false))
                .thenReturn(new Attempt.Ok<>(new LifecycleOutcome(
                        false, 100, false, List.of(new NodeOutcome(nodeA, "a", NodeStatus.APPLIED, 3L, null)))));

        BulkRunDetailView preview = bulk.preview(
                clusterId, new BulkPreviewRequest(BulkOperation.DELETE, List.of("artemis.queue/orders"), null, false));
        bulk.execute(
                clusterId,
                preview.run().id(),
                new BulkExecuteRequest(preview.run().planHash(), false, true));
        awaitFinished(preview.run().id());

        verify(lifecycle).deleteQueue(clusterId, "orders", false, false, false);
        verify(lifecycle, never())
                .deleteQueue(eq(clusterId), any(Target.class), anyBoolean(), anyBoolean(), anyBoolean());
        verify(lifecycle, never()).deleteAddress(eq(clusterId), anyString(), anyBoolean());
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

    private SubscriptionView view(String id, String name, long messages) {
        return new SubscriptionView(
                id,
                id.substring(0, id.indexOf('/')),
                new KindName("Subscription", "Subscriptions"),
                name,
                "address/" + name,
                Map.of(),
                true,
                Backlog.exact(messages),
                0,
                0,
                0,
                1,
                1,
                false,
                List.of(new SubscriptionNodeCell(nodeA, "a", false, Instant.now(), messages, 0, 0, 0, false)),
                List.of(),
                null);
    }
}
