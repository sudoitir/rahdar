package io.github.sudoitir.rahdar.system.kafka.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.system.kafka.KafkaPartitionReads;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.CancelPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignmentList;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaSuggestion;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The suggestion tool reads a suggestion. It does not preview or apply a replica move. */
class KafkaMcpReplicationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final KafkaPartitionReads reads = mock(KafkaPartitionReads.class);
    private final KafkaReplication replication = mock(KafkaReplication.class);
    private final KafkaMcpReadTools tools =
            new KafkaMcpReadTools(null, null, null, null, null, null, reads, replication, null);

    @Test
    void aSuggestionDoesNotCallReassign() {
        when(reads.suggest(CLUSTER, "orders", 0))
                .thenReturn(new ReplicaSuggestion(
                        "suggestion", "orders", 0, List.of(1, 2), List.of(1, 2, 3), "This is a suggestion."));

        McpSchema.CallToolResult result = tools.kafkaSuggestReplicas(CLUSTER.toString(), "orders", 0);

        assertThat(result.isError()).isFalse();
        assertThat(result.content().toString()).contains("suggestion").contains("[1,2,3]");
        verify(reads).suggest(CLUSTER, "orders", 0);
        verify(replication, never()).previewReassign(any(), any(), anyInt(), any());
        verify(replication, never()).reassign(any(), any(), anyInt(), any());
    }

    @Test
    void theReadToolsReturnThePreviewAndRefuseABadSelection() {
        when(replication.previewElection(any(), any())).thenReturn(new ElectionPreview(1, 0, "etag", "would change"));
        when(replication.previewReassign(any(), any(), anyInt(), any()))
                .thenReturn(new ReassignPreview(
                        List.of(1), List.of(1, 2), 1, 2, 1, 10L, null, "1s", null, "removed", 1000L, "etag"));
        when(replication.reassignments(any())).thenReturn(new ReassignmentList(List.of()));
        when(replication.previewCancel(any(), any())).thenReturn(new CancelPreview(List.of(), "etag", "cancel"));

        assertThat(tools.kafkaPreviewPreferredElection(CLUSTER.toString(), "orders:0")
                        .isError())
                .isFalse();
        assertThat(tools.kafkaPreviewReassign(CLUSTER.toString(), "orders", 0, "1,2", 1000L)
                        .isError())
                .isFalse();
        assertThat(tools.kafkaListReassignments(CLUSTER.toString()).isError()).isFalse();
        assertThat(tools.kafkaPreviewCancelReassignment(CLUSTER.toString(), "orders:0")
                        .isError())
                .isFalse();
        assertThat(KafkaMcpActionTools.selection(" ")).isEmpty();
        assertThat(KafkaMcpActionTools.brokers(null)).isEmpty();
        assertThatThrownBy(() -> KafkaMcpActionTools.selection("orders")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaMcpActionTools.selection("orders:no"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaMcpActionTools.brokers("east")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.kafkaSuggestReplicas(CLUSTER.toString(), "orders", null))
                .hasMessageContaining("partition");
    }
}
