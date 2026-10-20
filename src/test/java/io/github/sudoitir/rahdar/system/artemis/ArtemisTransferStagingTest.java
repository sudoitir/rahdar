package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.TransferStaging.TransferSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How a transfer's staging operations become Artemis management calls on the queue's MBean. */
class ArtemisTransferStagingTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final String BROKER = "org.apache.activemq.artemis:broker=\"b\"";
    private static final TransferSource SOURCE = new TransferSource("orders.addr", "orders", "ANYCAST");

    private final ArtemisNodes nodes = mock(ArtemisNodes.class);
    private final StagingQueues staging = mock(StagingQueues.class);
    private final MessageOperations messages = mock(MessageOperations.class);
    private final ArtemisNode client = mock(ArtemisNode.class);
    private final ArtemisTransferStaging transferStaging = new ArtemisTransferStaging(nodes, staging, messages);

    @BeforeEach
    void wire() {
        when(nodes.on(CLUSTER, NODE)).thenReturn(client);
        when(client.resolveBrokerObjectName()).thenReturn(BROKER);
    }

    private static String mbean(String address, String queue) {
        return MBeanNames.queue(BROKER, address, queue, "ANYCAST");
    }

    @Test
    void theStagingQueueIsCreatedListedAndDestroyedOnTheNodesClient() {
        UUID run = UUID.randomUUID();
        String queue = StagingQueues.queueName(run);
        when(staging.destroyIfEmpty(client, queue)).thenReturn(true);
        when(staging.list(client)).thenReturn(List.of(queue));

        transferStaging.create(CLUSTER, NODE, run);

        verify(staging).create(client, run);
        assertThat(transferStaging.destroyIfEmpty(CLUSTER, NODE, queue)).isTrue();
        assertThat(transferStaging.list(CLUSTER, NODE)).containsExactly(queue);
    }

    @Test
    void whatIsStagedIsReadFromTheStagingQueuesOwnMbean() {
        String queue = "rahdar.transfer.r1";
        String mbean = mbean(queue, queue);
        when(messages.messageCount(client, mbean)).thenReturn(5L);
        when(messages.listIds(client, mbean)).thenReturn(List.of(1L, 2L));

        assertThat(transferStaging.stagedCount(CLUSTER, NODE, queue)).isEqualTo(5);
        assertThat(transferStaging.stagedIds(CLUSTER, NODE, queue)).containsExactly(1L, 2L);
    }

    @Test
    void stagedMessagesMoveOutOfTheStagingQueueWithNoFilter() {
        String queue = "rahdar.transfer.r1";
        BulkResult result = new BulkResult(1, List.of(), null);
        when(messages.moveMessages(client, mbean(queue, queue), 10, "", "orders", false, 10))
                .thenReturn(7L);
        when(messages.moveByIds(client, mbean(queue, queue), List.of(3L), "orders"))
                .thenReturn(result);

        assertThat(transferStaging.moveStaged(CLUSTER, NODE, queue, 10, "orders"))
                .isEqualTo(7);
        assertThat(transferStaging.moveStagedIds(CLUSTER, NODE, queue, List.of(3L), "orders"))
                .isSameAs(result);
    }

    @Test
    void aSelectionMovesOutOfTheQueuesMbeanWithItsFilterFrozenAtT0() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        String frozen = ArtemisSelectors.frozen("color = 'red'", t0);
        BulkResult result = new BulkResult(2, List.of(), null);
        when(messages.moveMessages(client, mbean("orders.addr", "orders"), 5, frozen, "staging", false, 5))
                .thenReturn(4L);
        when(messages.countMessages(client, mbean("orders.addr", "orders"), frozen))
                .thenReturn(9L);
        when(messages.moveByIds(client, mbean("orders.addr", "orders"), List.of(1L, 2L), "staging"))
                .thenReturn(result);

        assertThat(transferStaging.moveSelected(CLUSTER, NODE, SOURCE, "color = 'red'", t0, 5, "staging"))
                .isEqualTo(4);
        assertThat(transferStaging.countSelected(CLUSTER, NODE, SOURCE, "color = 'red'", t0))
                .isEqualTo(9);
        assertThat(transferStaging.moveIds(CLUSTER, NODE, SOURCE, List.of(1L, 2L), "staging"))
                .isSameAs(result);
    }

    @Test
    void aFilterIsCompleteWhenArtemisReadsItAsOneExpression() {
        assertThat(transferStaging.isCompleteFilter("color = 'red'")).isTrue();
        assertThat(transferStaging.isCompleteFilter("color = 'red' OR (")).isFalse();
    }

    @Test
    void itServesArtemis() {
        assertThat(transferStaging.system()).isEqualTo("artemis");
        verify(nodes, org.mockito.Mockito.never()).on(any(), any());
    }
}
