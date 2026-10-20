package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.FindingKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.support.ArtemisBrokers;
import io.github.sudoitir.rahdar.support.ArtemisBrokers.Broker;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 5.10: forced redistribution. Messages chosen by id on node 1 of a live-live cluster land on node 2's
 * own queue, and are not routed onward by the cluster when they arrive.
 */
class TransferRedistributeTest extends TransferTestSupport {

    @Test
    void messagesChosenByIdOnOneNodeLandOnTheOtherNodesOwnQueue() {
        List<Broker> pair = ArtemisBrokers.clusterPair();
        Broker node1 = pair.get(0);
        Broker node2 = pair.get(1);
        Registered cluster = register("pair", node1, node2);
        String orders = "orders." + sfx;
        for (Broker node : pair) {
            node.createQueue(orders);
            afterTest(() -> node.destroyQueue(orders));
        }
        node1.produce(orders, 6);
        List<Long> chosen = node1.messageIds(orders).subList(1, 4);

        TransferRunView preview = preview(
                TransferMode.MOVE,
                cluster,
                cluster.nodes().get(0),
                orders,
                ids(chosen),
                cluster,
                cluster.nodes().get(1),
                orders);
        assertThat(preview.sameNode()).isFalse();
        assertThat(preview.findings())
                .as("node 2's queue has no consumers and its address redistributes")
                .anySatisfy(f -> {
                    assertThat(f.kind()).isEqualTo(FindingKind.WARN);
                    assertThat(f.code()).isEqualTo("may-redistribute");
                });
        TransferRunView run = execute(preview);
        afterTest(() -> node1.destroyQueue(StagingQueues.queueName(run.id())));
        TransferRunView done = awaitEnded(run);

        assertThat(done.state()).as(done.lastError()).isEqualTo(TransferState.SUCCEEDED);
        // Read without consuming: a consumer on either node would let the cluster redistribute.
        assertThat(node2.list(orders))
                .extracting(m -> m.path("_rahdar_orig_message_id").asLong())
                .containsExactlyElementsOf(chosen);
        assertThat(node1.list(orders)).extracting(m -> m.path("seq").asInt()).containsExactly(0, 4, 5);
        assertThat(staging.list(node1.jolokia())).doesNotContain(StagingQueues.queueName(run.id()));
    }
}
