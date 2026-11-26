package io.github.sudoitir.rahdar.system.kafka.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.Subject;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.openbroker.kafka.schema.Subjects;
import io.github.sudoitir.openbroker.messaging.Cursor;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingView;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaRegistry;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.VersionView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.CompatibilityRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.DeleteRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.RegisterRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.SchemaBindingRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The schema routes parse the request and pass it on. A bad type or level never reaches the registry. */
class KafkaSchemaControllerTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final KafkaSchemaRegistry registry = mock(KafkaSchemaRegistry.class);
    private final KafkaSchemaReviews reviews = mock(KafkaSchemaReviews.class);
    private final KafkaSchemaBindings bindings = mock(KafkaSchemaBindings.class);
    private final KafkaSchemaController controller = new KafkaSchemaController(registry, reviews, bindings);

    @Test
    void aSubjectPageKeepsTheCursorAndASubjectWithNoVersionsHasNoSchemaText() {
        when(registry.subjects(CLUSTER, "c1", "ord"))
                .thenReturn(new Subjects(
                        List.of(
                                new Subject(
                                        "orders-value",
                                        List.of(new SubjectVersion(1, 1, SchemaType.AVRO, "{}", false)),
                                        Optional.of(CompatibilityLevel.BACKWARD),
                                        Optional.of("set")),
                                new Subject("payments-value", List.of(), Optional.empty(), Optional.empty())),
                        Optional.of(new Cursor("next"))));

        var page = controller.subjects(CLUSTER, "c1", "ord");
        var empty = KafkaSchemaViews.subject(new Subject("gone", List.of(), Optional.empty(), Optional.empty()));

        assertThat(page.next()).isEqualTo("next");
        assertThat(page.items()).extracting(row -> row.name()).containsExactly("orders-value", "payments-value");
        assertThat(page.items().getFirst().compatibility()).isEqualTo("BACKWARD");
        assertThat(page.items().get(1).compatibility()).isNull();
        assertThat(empty.type()).isNull();
        assertThat(empty.schemaText()).isNull();
        assertThat(empty.versions()).isEmpty();
    }

    @Test
    void aSubjectReadIsTheMappedSubject() {
        when(registry.subject(CLUSTER, "orders-value"))
                .thenReturn(new Subject(
                        "orders-value",
                        List.of(new SubjectVersion(4, 2, SchemaType.JSON, "{\"type\":\"object\"}", true)),
                        Optional.empty(),
                        Optional.empty()));

        var view = controller.subject(CLUSTER, "orders-value");

        assertThat(view.type()).isEqualTo("JSON");
        assertThat(view.schemaText()).isEqualTo("{\"type\":\"object\"}");
        assertThat(view.versions().getFirst().deleted()).isTrue();
    }

    @Test
    void registerParsesTheTypeAndRefusesOneTheRegistryDoesNotKnow() {
        SchemaPreview preview = preview();
        when(reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}"))
                .thenReturn(preview);
        when(reviews.register(CLUSTER, "orders-value", SchemaType.PROTOBUF, "syntax", "etag-1"))
                .thenReturn(new VersionView(1, 1, "PROTOBUF", "syntax", false));

        assertThat(controller.previewRegister(CLUSTER, new RegisterRequest("orders-value", "AVRO", "{}", null)))
                .isSameAs(preview);
        assertThat(controller
                        .register(CLUSTER, new RegisterRequest("orders-value", "PROTOBUF", "syntax", "etag-1"))
                        .id())
                .isEqualTo(1);
        assertThatThrownBy(() -> controller.previewRegister(CLUSTER, new RegisterRequest("s", "XML", "{}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVRO, PROTOBUF, JSON");
        assertThatThrownBy(() -> controller.previewRegister(CLUSTER, new RegisterRequest("s", " ", "{}", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVRO, PROTOBUF, JSON");
        verify(reviews, never()).previewRegister(eq(CLUSTER), eq("s"), any(), any());
    }

    @Test
    void compatibilityAndTheDefaultParseTheLevel() {
        SchemaPreview preview = preview();
        when(reviews.previewCompatibility(CLUSTER, "orders-value", CompatibilityLevel.FULL))
                .thenReturn(preview);
        when(reviews.setCompatibility(CLUSTER, "orders-value", CompatibilityLevel.FULL, "etag-1"))
                .thenReturn(CompatibilityLevel.FULL);
        when(reviews.previewDefault(CLUSTER, CompatibilityLevel.NONE)).thenReturn(preview);
        when(reviews.setDefault(CLUSTER, CompatibilityLevel.NONE, "etag-1")).thenReturn(CompatibilityLevel.NONE);

        assertThat(controller.previewCompatibility(CLUSTER, "orders-value", new CompatibilityRequest("FULL", null)))
                .isSameAs(preview);
        assertThat(controller.setCompatibility(CLUSTER, "orders-value", new CompatibilityRequest("FULL", "etag-1")))
                .isEqualTo(CompatibilityLevel.FULL);
        assertThat(controller.previewDefault(CLUSTER, new CompatibilityRequest("NONE", null)))
                .isSameAs(preview);
        assertThat(controller.setDefault(CLUSTER, new CompatibilityRequest("NONE", "etag-1")))
                .isEqualTo(CompatibilityLevel.NONE);
        assertThatThrownBy(() -> controller.setDefault(CLUSTER, new CompatibilityRequest("MAYBE", "etag-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BACKWARD");
        assertThatThrownBy(() -> controller.previewDefault(CLUSTER, new CompatibilityRequest(" ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("level is required.");
        verify(reviews, never()).setDefault(eq(CLUSTER), isNull(), any());
    }

    @Test
    void deleteTreatsAMissingPermanentAsSoftAndStripsAQuotedEtag() {
        when(reviews.previewDelete(CLUSTER, "orders-value", true)).thenReturn(preview());

        assertThat(controller
                        .previewDelete(CLUSTER, "orders-value", new DeleteRequest(true))
                        .action())
                .isEqualTo("delete");
        controller.previewDelete(CLUSTER, "orders-value", new DeleteRequest(null));
        controller.delete(CLUSTER, "orders-value", null, "\"etag-1\"");
        controller.delete(CLUSTER, "orders-value", true, "etag-2");
        controller.delete(CLUSTER, "orders-value", false, null);

        verify(reviews).previewDelete(CLUSTER, "orders-value", false);
        verify(reviews).delete(CLUSTER, "orders-value", false, "etag-1");
        verify(reviews).delete(CLUSTER, "orders-value", true, "etag-2");
        verify(reviews).delete(CLUSTER, "orders-value", false, null);
        assertThat(KafkaSchemaViews.etag("  bare  ")).isEqualTo("bare");
    }

    @Test
    void aBindingRoundTripPassesTheRequestThrough() {
        BindingView stored = new BindingView("orders", "value", "avro");
        SchemaPreview preview = preview();
        when(bindings.list(CLUSTER)).thenReturn(List.of(stored));
        when(bindings.preview(CLUSTER, "orders", "value", "avro")).thenReturn(preview);
        when(bindings.apply(CLUSTER, "orders", "key", "json", "etag-1"))
                .thenReturn(new BindingView("orders", "key", "json"));

        assertThat(controller.bindings(CLUSTER).items()).containsExactly(stored);
        assertThat(controller.previewBinding(CLUSTER, new SchemaBindingRequest("orders", "value", "avro", null)))
                .isSameAs(preview);
        assertThat(controller
                        .bind(CLUSTER, new SchemaBindingRequest("orders", "key", "json", "etag-1"))
                        .side())
                .isEqualTo("key");
    }

    private static SchemaPreview preview() {
        return new SchemaPreview(
                "etag", "orders-value", "delete", List.of(), List.of(), null, false, false, null, null, "x", true);
    }
}
