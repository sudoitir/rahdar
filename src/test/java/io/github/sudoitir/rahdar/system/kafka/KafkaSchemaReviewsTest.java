package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaWriteParams;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A schema preview writes nothing, and a stale or forbidden apply writes nothing either. */
class KafkaSchemaReviewsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final SchemaCatalog catalog = mock(SchemaCatalog.class);
    private final KafkaSchemaRegistry registries = mock(KafkaSchemaRegistry.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final KafkaSchemaReviews reviews;

    KafkaSchemaReviewsTest() {
        when(registries.call(eq(CLUSTER), any())).thenAnswer(invocation -> {
            Function<SchemaCatalog, ?> call = invocation.getArgument(1);
            return call.apply(catalog);
        });
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolve()).thenReturn(mock(Actor.class));
        AuditService audit = mock(AuditService.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        Mockito.doAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                })
                .when(gate)
                .run(any(), any());
        reviews = new KafkaSchemaReviews(registries, access, gate, audit, actors);
    }

    @Test
    void aPreviewWritesNothing() {
        when(catalog.previewRegister(any(), any(), any()))
                .thenReturn(preview("etag-1", "The registry judges this schema compatible."));

        SchemaPreview view = reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}");

        assertThat(view.writesNothing()).isTrue();
        assertThat(view.statement()).isEqualTo(KafkaSchemaReviews.NOTHING_WRITTEN);
        verify(catalog, never()).register(any(), any(), any(), any());
    }

    @Test
    void aStaleEtagRefusesAndWritesNothing() {
        when(catalog.previewRegister(any(), any(), any())).thenReturn(preview("now", "ok"));

        assertThatThrownBy(() -> reviews.register(CLUSTER, "orders-value", SchemaType.AVRO, "{}", "then"))
                .isInstanceOf(StalePreviewException.class);
        verify(catalog, never()).register(any(), any(), any(), any());
    }

    @Test
    void anAlreadyRegisteredSchemaReturnsTheExistingId() {
        when(catalog.previewRegister(any(), any(), any()))
                .thenReturn(preview(
                        "etag-1",
                        "Already registered; applying creates no second version.",
                        new AttributeChange("schema.id", Optional.of(7), Optional.of(7))));
        when(catalog.register(any(), any(), any(), any()))
                .thenReturn(new SubjectVersion(7, 1, SchemaType.AVRO, "{}", false));

        var version = reviews.register(CLUSTER, "orders-value", SchemaType.AVRO, "{}", "etag-1");

        assertThat(version.id()).isEqualTo(7);
        verify(catalog).register(eq("orders-value"), eq(SchemaType.AVRO), eq("{}"), any());
    }

    @Test
    void aPermanentDeletePreviewSaysSchemasCannotBeRestored() {
        when(catalog.previewDelete("orders-value", true)).thenReturn(preview("etag-1", "gone"));

        SchemaPreview view = reviews.previewDelete(CLUSTER, "orders-value", true);

        assertThat(view.note()).contains("cannot be restored");
        assertThat(view.writesNothing()).isTrue();
        verify(catalog, never()).delete(any(), anyBoolean(), any());
    }

    @Test
    void aReaderCannotWrite() {
        Mockito.doThrow(new ResourceForbiddenException("schema:write", "cluster", CLUSTER.toString()))
                .when(access)
                .requireCluster(CLUSTER, KafkaModule.SCHEMA_WRITE);

        assertThatThrownBy(() -> reviews.register(CLUSTER, "orders-value", SchemaType.AVRO, "{}", "etag-1"))
                .isInstanceOf(ResourceForbiddenException.class)
                .hasMessageContaining("schema:write");
        verify(gate, never()).run(any(), any());
    }

    @Test
    void aHeldApplyRechecksTheEtag() {
        when(catalog.previewRegister(any(), any(), any())).thenReturn(preview("moved", "ok"));

        assertThatThrownBy(() -> reviews.replay(
                        new SchemaWriteParams(CLUSTER, "register", "orders-value", "AVRO", "{}", null, null, "then")))
                .isInstanceOf(StalePreviewException.class);
        verify(catalog, never()).register(any(), any(), any(), any());
    }

    private static Preview preview(String etag, String note, AttributeChange... extra) {
        List<AttributeChange> diff = new java.util.ArrayList<>();
        diff.add(new AttributeChange("note", Optional.empty(), Optional.of(note)));
        diff.addAll(List.of(extra));
        return new Preview(
                diff,
                Set.of(),
                new Estimate(Count.exact(0), "{schema}"),
                etag,
                EtagBasis.CONFIGURATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }
}
