package io.github.sudoitir.rahdar.system.kafka.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.schema.Subjects;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaRegistry;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Schema tools preview by default and never carry a credential. */
class KafkaMcpSchemaToolsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String SECRET = "registry-secret-value";

    private final KafkaSchemaReviews reviews = mock(KafkaSchemaReviews.class);
    private final KafkaSchemaBindings bindings = mock(KafkaSchemaBindings.class);
    private final KafkaSchemaRegistry registry = mock(KafkaSchemaRegistry.class);
    private final KafkaMcpSchemaTools tools = new KafkaMcpSchemaTools(registry, reviews, bindings);

    @Test
    void aDeleteWithoutAnEtagPreviewsAndWritesNothing() {
        when(reviews.previewDelete(CLUSTER, "orders-value", true))
                .thenReturn(preview("This soft-deletes the subject and then deletes the schema."));

        var result = tools.kafkaDeleteSubject(CLUSTER.toString(), "orders-value", true, null, null, null);

        assertThat(result.isError()).isFalse();
        assertThat(result.content().toString()).contains("soft-deletes").doesNotContain(SECRET);
        verify(reviews).previewDelete(CLUSTER, "orders-value", true);
        verify(reviews, never()).delete(any(), any(), anyBoolean(), any());
    }

    @Test
    void aDeleteWithTheEtagAppliesOnce() {
        var result =
                tools.kafkaDeleteSubject(CLUSTER.toString(), "orders-value", true, "etag-1", false, "orders-value");

        assertThat(result.isError()).isFalse();
        verify(reviews).delete(CLUSTER, "orders-value", true, "etag-1");
        verify(reviews, never()).previewDelete(any(), any(), anyBoolean());
    }

    @Test
    void aReadDoesNotNeedAWrite() {
        when(registry.subjects(eq(CLUSTER), any(), any())).thenReturn(new Subjects(List.of(), Optional.empty()));

        assertThat(tools.kafkaSchemaSubjects(CLUSTER.toString(), null, null).isError())
                .isFalse();
        verify(reviews, never()).previewRegister(any(), any(), any(), any());
    }

    @Test
    void theCatalogueRefusesAnotherSystemAndNamesSchemaWrite() {
        McpToolDef delete = KafkaMcpToolDefs.ALL.stream()
                .filter(tool -> tool.name().equals("kafka_delete_subject"))
                .findFirst()
                .orElseThrow();

        assertThat(delete.system()).isEqualTo(KafkaModule.SYSTEM);
        assertThat(delete.posture()).isEqualTo(McpToolDef.Posture.MUTATE);
        assertThat(delete.summary()).doesNotContain("glue").doesNotContain(SECRET);
    }

    private static SchemaPreview preview(String note) {
        return new SchemaPreview(
                "etag",
                "orders-value",
                "delete",
                List.of(),
                List.of(),
                null,
                false,
                false,
                null,
                note,
                "Nothing has been written.",
                true);
    }
}
