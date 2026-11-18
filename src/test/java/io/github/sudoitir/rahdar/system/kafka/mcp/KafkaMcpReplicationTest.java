package io.github.sudoitir.rahdar.system.kafka.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.system.kafka.KafkaPartitionReads;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication;
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
            new KafkaMcpReadTools(null, null, null, null, null, null, reads, replication);

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
}
