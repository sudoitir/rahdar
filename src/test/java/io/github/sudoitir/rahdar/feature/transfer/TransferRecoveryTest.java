package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.OrphanReturnRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.OrphanReturnView;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.OrphanView;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 5.11: a run cut off by Rahdar stopping is interrupted and resumable; a staging queue no run knows is orphaned. */
class TransferRecoveryTest extends TransferTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    /** Rahdar's process ending: nothing after it runs, not even the run's own bookkeeping. */
    private static final class ProcessDied extends Error {
        ProcessDied() {
            super("injected: Rahdar's process ended after the target commit");
        }
    }

    @Test
    void aRunCutOffByARestartIsInterruptedAndAResumeCompletesItExactlyOnce() throws Exception {
        String src = queue(p, "restart.src");
        String dst = queue(d, "restart.dst");
        p.produce(src, 35);
        doCallRealMethod()
                .doThrow(new ProcessDied())
                .doCallRealMethod()
                .when(faults)
                .afterTargetCommit(any());

        TransferRunView run = execute(preview(TransferMode.MOVE, src, all(), dst));
        awaitIdle(run.id());
        assertThat(get(run).state())
                .as("the run's thread is gone, its row not updated")
                .isEqualTo(TransferState.RUNNING);

        // The replica that was executing it is gone.
        jdbc.update("UPDATE transfer_run SET replica_id = ? WHERE id = ?", UUID.randomUUID(), run.id());
        recovery.recover();
        TransferRunView interrupted = get(run);
        assertThat(interrupted.state()).isEqualTo(TransferState.INTERRUPTED);
        assertThat(interrupted.lastError()).isEqualTo(TransferRecovery.INTERRUPTED);
        assertThat(p.depth(src) + p.depth(StagingQueues.queueName(run.id())) + d.depth(dst))
                .as("the held messages are intact: at most the cut-off batch is on both sides")
                .isBetween(35L, 45L);

        TransferRunView resumed = awaitEnded(resume(interrupted));

        assertThat(resumed.state()).as(resumed.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(p.depth(src)).isZero();
        assertThat(d.drain(dst))
                .extracting(m -> m.getIntProperty("seq"))
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.range(0, 35).boxed().toList());
    }

    @Test
    void aStagingQueueNoRunKnowsIsListedAsOrphanedAndCanBeReturned() {
        UUID lostRun = UUID.randomUUID();
        String orphan = StagingQueues.queueName(lostRun);
        staging.create(p.jolokia(), lostRun);
        afterTest(() -> p.destroyQueue(orphan));
        p.produce(orphan, 5);
        String back = queue(p, "orphan.back");

        assertThat(transfers.orphans(clusterP.id()))
                .contains(new OrphanView(clusterP.node(), orphanNodeName(), orphan, 5L));

        OrphanReturnView returned =
                transfers.returnOrphan(clusterP.id(), new OrphanReturnRequest(clusterP.node(), orphan, back));

        assertThat(returned).isEqualTo(new OrphanReturnView(orphan, 5, 0, true));
        assertThat(p.depth(back)).isEqualTo(5);
        assertThat(staging.list(p.jolokia())).doesNotContain(orphan);
        assertThat(transfers.orphans(clusterP.id()))
                .extracting(OrphanView::stagingQueue)
                .doesNotContain(orphan);
    }

    private String orphanNodeName() {
        return directory.node(clusterP.node()).orElseThrow().getName();
    }
}
