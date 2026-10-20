package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferLedger;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.6: the target stops answering mid-move. The run fails with nothing lost: what it had taken and
 * not delivered is in staging, the rest is still on the source. It then either resumes to the end or
 * returns what staging holds to the source.
 */
class TransferTargetDownTest extends TransferTestSupport {
    @Autowired
    TransferLedger ledger;

    private static final int COUNT = 200;

    private String src;
    private String dst;

    @BeforeEach
    void slowRun() {
        // Twenty a second, so the run is still going when the target is paused.
        settings.put(TransferSettings.MESSAGES_PER_SECOND, "20");
        src = queue(p, "down.src");
        dst = queue(d, "down.dst");
        p.produce(src, COUNT);
    }

    /** Start the move, pause the target once some messages arrived, and wait for the run to fail. */
    private TransferRunView failWhileTargetPaused() {
        TransferRunView failed;
        try {
            failed = failWithTargetPaused();
        } finally {
            d.unpause();
        }
        return checked(failed);
    }

    /** As above, leaving the target paused; the caller unpauses it. */
    private TransferRunView failWithTargetPaused() {
        TransferRunView run = execute(preview(TransferMode.MOVE, src, all(), dst));
        await(run, r -> r.delivered() >= 30, Duration.ofSeconds(60));
        d.pause();
        return awaitEnded(run, Duration.ofSeconds(120));
    }

    private TransferRunView checked(TransferRunView failed) {
        assertThat(failed.state()).isEqualTo(TransferState.FAILED);
        assertThat(failed.lastError()).contains("staging queue");
        assertThat(failed.held()).isPositive();
        String stagingQueue = StagingQueues.queueName(failed.id());
        assertThat(p.depth(stagingQueue))
                .as("what was taken and not delivered is held in staging")
                .isEqualTo(failed.held());
        // The batch in flight when the target stopped may have been committed there unseen: until the
        // run resumes or returns it may be both on the target and in staging, never on neither.
        assertThat(p.depth(src) + p.depth(stagingQueue) + d.depth(dst))
                .as("nothing is lost")
                .isBetween((long) COUNT, COUNT + 10L);
        return failed;
    }

    @Test
    void aMoveFailsWhenTheTargetStopsAnsweringAndAResumeFinishesIt() {
        TransferRunView failed = failWhileTargetPaused();

        TransferRunView run = awaitEnded(resume(failed));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(d.depth(dst)).isEqualTo(COUNT);
        assertThat(p.depth(src)).isZero();
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(run.id()));
    }

    @Test
    void aFailedMoveReturnedToItsSourceRestoresEveryMessageNotDelivered() {
        TransferRunView failed = failWhileTargetPaused();

        transfers.returnToSource(clusterP.id(), failed.id());
        TransferRunView returned = awaitEnded(failed);

        assertThat(returned.state()).as(returned.lastError()).isEqualTo(TransferState.RETURNED);
        // A batch whose commit the target never confirmed is settled with the target, not returned:
        // it ends there whether or not that commit had gone through. Everything else held comes back.
        long settled = d.depth(dst) - failed.delivered();
        assertThat(settled).isBetween(0L, 10L);
        assertThat(returned.returned() + settled).isEqualTo(failed.held());
        assertThat(p.depth(src) + d.depth(dst))
                .as("each message is on exactly one broker")
                .isEqualTo(COUNT);
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(failed.id()));
    }

    @Test
    void aReturnWhileTheTargetIsDownKeepsOnlyTheBatchItCannotSettle() {
        TransferRunView failed;
        TransferRunView kept;
        String stagingQueue;
        try {
            failed = failWithTargetPaused();
            stagingQueue = StagingQueues.queueName(failed.id());
            transfers.returnToSource(clusterP.id(), failed.id());
            kept = awaitEnded(failed, Duration.ofSeconds(180));

            long inStaging = p.depth(stagingQueue);
            assertThat(inStaging).as("at most the batch in flight is kept").isBetween(0L, 10L);
            assertThat(p.depth(src) + inStaging)
                    .as("everything not delivered is back on the source or kept in staging")
                    .isEqualTo(COUNT - failed.delivered());
            if (inStaging > 0) {
                assertThat(kept.state()).isEqualTo(TransferState.FAILED);
                assertThat(kept.lastError()).contains("may already be on the target");
            }
        } finally {
            d.unpause();
        }

        TransferRunView returned = kept.state() == TransferState.RETURNED
                ? kept
                : awaitEnded(transfers.returnToSource(clusterP.id(), kept.id()));

        assertThat(returned.state()).as(returned.lastError()).isEqualTo(TransferState.RETURNED);
        assertThat(p.depth(src) + d.depth(dst))
                .as("each message is on exactly one broker")
                .isEqualTo(COUNT);
        assertThat(staging.list(p.jolokia())).doesNotContain(stagingQueue);
    }
}
