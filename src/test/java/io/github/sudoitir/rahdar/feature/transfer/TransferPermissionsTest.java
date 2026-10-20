package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferPreviewRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 5.12: who may run a transfer, what a preview leaves untouched, and how many runs may go at once. */
class TransferPermissionsTest extends TransferTestSupport {

    /** Twenty messages a second, so a run of a hundred is still going when the test acts on it. */
    private TransferRunView slowRun(String src, String dst) {
        settings.put(TransferSettings.MESSAGES_PER_SECOND, "20");
        p.produce(src, 100);
        TransferRunView run = execute(preview(TransferMode.MOVE, src, all(), dst));
        return await(run, r -> r.delivered() > 0, Duration.ofSeconds(30));
    }

    @Test
    void aTargetClusterTheOperatorMayNotSendToIsIndistinguishableFromOneThatDoesNotExist() {
        String src = queue(p, "perm.src");
        p.produce(src, 1);
        OperatorFixture.signInOnCluster(
                users,
                roles,
                rolePermissions,
                userRoles,
                grants,
                clusterP.id(),
                Permissions.CLUSTER_READ,
                MessagePermissions.MESSAGE_READ,
                MessagePermissions.MESSAGE_MOVE);

        var everything = all();
        String unreachable = "perm.dst." + sfx;
        assertThatThrownBy(() -> preview(TransferMode.MOVE, src, everything, unreachable))
                .isInstanceOf(NotFoundException.class);
        UUID nowhere = UUID.randomUUID();
        UUID sourceCluster = clusterP.id();
        var request = new TransferPreviewRequest(
                TransferMode.MOVE, src, clusterP.node(), everything, nowhere, clusterD.node(), "q", null);
        assertThatThrownBy(() -> transfers.preview(sourceCluster, request)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aGrantWithdrawnMidRunStopsTheRunWithTheReason() {
        TransferRunView run = slowRun(queue(p, "revoke.src"), queue(d, "revoke.dst"));

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);
        TransferRunView stopped = awaitEnded(run);

        assertThat(stopped.state()).isEqualTo(TransferState.STOPPED);
        assertThat(stopped.lastError()).contains("withdrawn");
        assertThat(stopped.delivered()).isLessThan(100);
    }

    @Test
    void aPreviewCreatesNoQueueAndMovesNothing() {
        String src = queue(p, "preview.src");
        p.produce(src, 5);
        String absent = "preview.absent." + sfx;

        TransferRunView preview = preview(TransferMode.MOVE, src, all(), absent);

        assertThat(preview.state()).isEqualTo(TransferState.PREVIEWED);
        assertThat(preview.estimate()).isEqualTo(5);
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(preview.id()));
        assertThat(d.queueExists(absent)).isFalse();
        assertThat(p.depth(src)).isEqualTo(5);
    }

    @Test
    void aSelectionOverTheBulkCapNeedsTheOverride() {
        settings.put(BrokerSettings.BULK_CAP, "3");
        String src = queue(p, "cap.src");
        String dst = queue(d, "cap.dst");
        p.produce(src, 5);
        TransferRunView preview = preview(TransferMode.MOVE, src, all(), dst);
        assertThat(preview.overCap()).isTrue();

        assertThatThrownBy(() -> execute(preview)).isInstanceOf(BulkCapExceededException.class);
        TransferRunView run = awaitEnded(execute(preview, true));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.overrideCap()).isTrue();
        assertThat(d.depth(dst)).isEqualTo(5);
    }

    @Test
    void aSecondRunOnTheSameSourceQueueIsRefusedWhileTheFirstIsActive() {
        String src = queue(p, "twice.src");
        TransferRunView first = slowRun(src, queue(d, "twice.dst"));
        TransferRunView second = preview(TransferMode.MOVE, src, all(), queue(d, "twice.dst2"));

        assertThatThrownBy(() -> execute(second))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("transfer-run-in-progress"));

        transfers.stop(clusterP.id(), first.id());
        assertThat(awaitEnded(first).state()).isEqualTo(TransferState.STOPPED);
    }

    @Test
    void noMoreRunsThanTheLimitExecuteAtOnce() {
        settings.put(TransferSettings.MAX_CONCURRENT_RUNS, "1");
        TransferRunView first = slowRun(queue(p, "limit.a"), queue(d, "limit.a.dst"));
        String other = queue(p, "limit.b");
        p.produce(other, 1);
        TransferRunView second = preview(TransferMode.MOVE, other, all(), queue(d, "limit.b.dst"));

        assertThatThrownBy(() -> execute(second))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("transfer-concurrency-limit"));

        transfers.stop(clusterP.id(), first.id());
        assertThat(awaitEnded(first).state()).isEqualTo(TransferState.STOPPED);
    }
}
