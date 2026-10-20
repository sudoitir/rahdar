package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import jakarta.jms.Message;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Rahdar stopping between a batch's commit on the target and its acknowledgement on the source, for a
 * copy (5.3) and a move (5.5): after a resume the target holds each selected message exactly once.
 */
class TransferExactlyOnceTest extends TransferTestSupport {

    /** The second batch reaches the target, then the run dies before the source side of it. */
    private void failAfterTheSecondTargetCommit() {
        doCallRealMethod()
                .doThrow(new IllegalStateException("injected: Rahdar stopped after the target commit"))
                .doCallRealMethod()
                .when(faults)
                .afterTargetCommit(any());
    }

    @Test
    void aCopyByFilterLeavesTheSourceAndAResumeAfterAFaultStillCopiesEachMessageOnce() {
        String src = queue(p, "copy.src");
        String dst = queue(d, "copy.dst");
        p.produce(src, 60, (session, i) -> {
            Message m = session.createTextMessage("m" + i);
            m.setIntProperty("seq", i);
            m.setBooleanProperty("pick", i % 2 == 0);
            return m;
        });
        failAfterTheSecondTargetCommit();

        TransferRunView failed = awaitEnded(execute(preview(TransferMode.COPY, src, filter("pick = true"), dst)));
        assertThat(failed.state()).isEqualTo(TransferState.FAILED);
        assertThat(failed.lastError()).contains("injected");
        assertThat(d.depth(dst)).as("the first two batches reached the target").isEqualTo(20);

        TransferRunView run = awaitEnded(resume(failed));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(p.depth(src)).as("a copy leaves the source as it was").isEqualTo(60);
        List<Message> copied = d.drain(dst);
        assertThat(copied)
                .extracting(m -> m.getIntProperty("seq"))
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.range(0, 60).filter(i -> i % 2 == 0).boxed().toList());
    }

    @Test
    void aMoveInterruptedBetweenTheTwoCommitsDeliversEachMessageOnceWhenResumed() {
        String src = queue(p, "crash.src");
        String dst = queue(d, "crash.dst");
        p.produce(src, 35);
        failAfterTheSecondTargetCommit();

        TransferRunView failed = awaitEnded(execute(preview(TransferMode.MOVE, src, all(), dst)));
        assertThat(failed.state()).isEqualTo(TransferState.FAILED);
        assertThat(failed.resumable()).isTrue();
        assertThat(failed.returnable()).isTrue();

        TransferRunView run = awaitEnded(resume(failed));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(p.depth(src)).isZero();
        assertThat(d.drain(dst))
                .extracting(m -> m.getIntProperty("seq"))
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.range(0, 35).boxed().toList());
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(run.id()));
    }
}
