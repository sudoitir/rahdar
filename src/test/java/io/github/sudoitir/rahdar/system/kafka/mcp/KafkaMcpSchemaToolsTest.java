package io.github.sudoitir.rahdar.system.kafka.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.Subject;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.openbroker.kafka.schema.Subjects;
import io.github.sudoitir.openbroker.messaging.Cursor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingView;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaRegistry;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.VersionView;
import io.modelcontextprotocol.spec.McpError;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Schema tools preview by default and never carry a credential. */
class KafkaMcpSchemaToolsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private final String clusterId = CLUSTER.toString();
    private static final String SECRET = "registry-secret-value";

    private final KafkaSchemaReviews reviews = mock(KafkaSchemaReviews.class);
    private final KafkaSchemaBindings bindings = mock(KafkaSchemaBindings.class);
    private final KafkaSchemaRegistry registry = mock(KafkaSchemaRegistry.class);
    private final KafkaMcpSchemaTools tools = new KafkaMcpSchemaTools(registry, reviews, bindings);

    @Test
    void aDeleteWithoutAnEtagPreviewsAndWritesNothing() {
        when(reviews.previewDelete(CLUSTER, "orders-value", true))
                .thenReturn(preview("This soft-deletes the subject and then deletes the schema."));

        var result = tools.kafkaDeleteSubject(clusterId, "orders-value", true, null, null, null);

        assertThat(result.isError()).isFalse();
        assertThat(result.content().toString()).contains("soft-deletes").doesNotContain(SECRET);
        verify(reviews).previewDelete(CLUSTER, "orders-value", true);
        verify(reviews, never()).delete(any(), any(), anyBoolean(), any());
    }

    @Test
    void aDeleteWithTheEtagAppliesOnce() {
        var result = tools.kafkaDeleteSubject(clusterId, "orders-value", true, "etag-1", false, "orders-value");

        assertThat(result.isError()).isFalse();
        verify(reviews).delete(CLUSTER, "orders-value", true, "etag-1");
        verify(reviews, never()).previewDelete(any(), any(), anyBoolean());
    }

    @Test
    void aReadDoesNotNeedAWrite() {
        when(registry.subjects(eq(CLUSTER), any(), any())).thenReturn(new Subjects(List.of(), Optional.empty()));

        assertThat(tools.kafkaSchemaSubjects(clusterId, null, null).isError()).isFalse();
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

    @Test
    void aSubjectReadReturnsVersionsAndABlankNameIsRefused() {
        when(registry.subject(CLUSTER, "orders-value"))
                .thenReturn(new Subject(
                        "orders-value",
                        List.of(new SubjectVersion(3, 1, SchemaType.AVRO, "{}", false)),
                        Optional.of(CompatibilityLevel.BACKWARD),
                        Optional.empty()));

        var read = tools.kafkaSchemaSubject(clusterId, "orders-value");

        assertThat(read.isError()).isFalse();
        assertThat(read.content().toString()).contains("orders-value").contains("BACKWARD");
        assertThatThrownBy(() -> tools.kafkaSchemaSubject(clusterId, " ")).isInstanceOf(McpError.class);
        verify(registry, never()).subject(CLUSTER, " ");
    }

    @Test
    void aPageCarriesTheRegistrysCursor() {
        when(registry.subjects(CLUSTER, "c1", "orders"))
                .thenReturn(new Subjects(List.of(), Optional.of(new Cursor("next-page"))));

        var page = tools.kafkaSchemaSubjects(clusterId, "c1", "orders");

        assertThat(page.isError()).isFalse();
        assertThat(page.content().toString()).contains("next-page");
        verify(registry).subjects(CLUSTER, "c1", "orders");
    }

    @Test
    void aRegisterPreviewsUntilTheEtagAndTheSubjectAreConfirmed() {
        when(reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}"))
                .thenReturn(preview("Nothing has been written."));
        when(reviews.register(CLUSTER, "orders-value", SchemaType.AVRO, "{}", "etag-1"))
                .thenReturn(new VersionView(7, 1, "AVRO", "{}", false));

        var dry = tools.kafkaRegisterSchema(clusterId, "orders-value", "AVRO", "{}", null, null, null);
        var applied =
                tools.kafkaRegisterSchema(clusterId, "orders-value", "AVRO", "{}", "etag-1", false, "orders-value");

        assertThat(dry.isError()).isFalse();
        assertThat(dry.content().toString()).contains("Nothing has been written.");
        assertThat(applied.content().toString()).contains("7");
        verify(reviews).register(CLUSTER, "orders-value", SchemaType.AVRO, "{}", "etag-1");
        assertThatThrownBy(() ->
                        tools.kafkaRegisterSchema(clusterId, "orders-value", "AVRO", "{}", "etag-1", false, "yes"))
                .isInstanceOf(McpError.class);
        assertThatThrownBy(() -> tools.kafkaRegisterSchema(clusterId, "orders-value", "XML", "{}", null, true, null))
                .isInstanceOf(McpError.class);
        verify(reviews, times(1)).register(any(), any(), any(), any(), any());
    }

    @Test
    void compatibilityFollowsTheSubjectOrTheRegistryDefault() {
        when(reviews.previewDefault(CLUSTER, CompatibilityLevel.FULL)).thenReturn(preview("registry default"));
        when(reviews.setDefault(CLUSTER, CompatibilityLevel.FULL, "etag-1")).thenReturn(CompatibilityLevel.FULL);
        when(reviews.setCompatibility(CLUSTER, "orders-value", CompatibilityLevel.BACKWARD, "etag-1"))
                .thenReturn(CompatibilityLevel.BACKWARD);

        var dryDefault = tools.kafkaSetSchemaCompatibility(clusterId, "  ", "FULL", null, true, null);
        var appliedDefault = tools.kafkaSetSchemaCompatibility(clusterId, null, "FULL", "etag-1", false, "default");
        var appliedSubject = tools.kafkaSetSchemaCompatibility(
                clusterId, "orders-value", "BACKWARD", "etag-1", false, "orders-value");

        assertThat(dryDefault.content().toString()).contains("registry default");
        assertThat(appliedDefault.content().toString()).contains("FULL");
        assertThat(appliedSubject.content().toString()).contains("BACKWARD");
        verify(reviews).previewDefault(CLUSTER, CompatibilityLevel.FULL);
        verify(reviews, never()).previewCompatibility(any(), any(), any());
        verify(reviews).setDefault(CLUSTER, CompatibilityLevel.FULL, "etag-1");
        verify(reviews).setCompatibility(CLUSTER, "orders-value", CompatibilityLevel.BACKWARD, "etag-1");
        assertThatThrownBy(() ->
                        tools.kafkaSetSchemaCompatibility(clusterId, "orders-value", "BACKWARD", "etag-1", false, "no"))
                .isInstanceOf(McpError.class);
        assertThatThrownBy(
                        () -> tools.kafkaSetSchemaCompatibility(clusterId, "orders-value", "MAYBE", null, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BACKWARD");
        verify(reviews, times(1)).setCompatibility(any(), any(), any(), any());
    }

    @Test
    void aSoftDeleteSaysDeletedAndAMissingConfirmWritesNothing() {
        when(reviews.previewDelete(CLUSTER, "orders-value", false)).thenReturn(preview("soft"));
        var applied = tools.kafkaDeleteSubject(clusterId, "orders-value", false, "etag-1", false, "orders-value");
        var previewed = tools.kafkaDeleteSubject(clusterId, "orders-value", null, null, null, null);

        assertThat(applied.isError()).isFalse();
        assertThat(previewed.isError()).isFalse();
        assertThat(previewed.content().toString()).contains("soft");
        assertThat(applied.content().toString())
                .contains("Deleted orders-value.")
                .doesNotContain("permanently");
        verify(reviews).delete(CLUSTER, "orders-value", false, "etag-1");
        verify(reviews).previewDelete(CLUSTER, "orders-value", false);
        assertThatThrownBy(() -> tools.kafkaDeleteSubject(clusterId, "orders-value", true, "etag-1", false, "yes"))
                .isInstanceOf(McpError.class);
        verify(reviews, never()).delete(any(), any(), eq(true), any());
    }

    @Test
    void aBindingPreviewsUntilTheTopicIsConfirmed() {
        when(bindings.preview(CLUSTER, "orders", "value", "avro")).thenReturn(preview("bind"));
        when(bindings.apply(CLUSTER, "orders", "value", "avro", "etag-1"))
                .thenReturn(new BindingView("orders", "value", "avro"));

        var dry = tools.kafkaSetSchemaBinding(clusterId, "orders", "value", "avro", null, null, null);
        var applied = tools.kafkaSetSchemaBinding(clusterId, "orders", "value", "avro", "etag-1", false, "orders");

        assertThat(dry.content().toString()).contains("bind");
        assertThat(applied.content().toString()).contains("avro");
        verify(bindings).apply(CLUSTER, "orders", "value", "avro", "etag-1");
        assertThatThrownBy(
                        () -> tools.kafkaSetSchemaBinding(clusterId, "orders", "value", "avro", "etag-1", false, "yes"))
                .isInstanceOf(McpError.class);
        verify(bindings, times(1)).apply(any(), any(), any(), any(), any());
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
