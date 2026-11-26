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

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.access.Assessment;
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
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaWriteParams;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaProblemAdvice;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ProblemDetail;

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
    void aNewSubjectDoesNotLookLikeItAlreadyExists() {
        when(catalog.previewRegister(any(), any(), any()))
                .thenReturn(preview(
                        "etag-1", "ok", new AttributeChange("subject", Optional.empty(), Optional.of("orders-value"))));

        SchemaPreview view = reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}");

        assertThat(view.before()).isEmpty();
        assertThat(view.after()).contains("subject=orders-value");
        assertThat(view.statement()).isEqualTo(KafkaSchemaReviews.NOTHING_WRITTEN);
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
                        "Nothing has been written. Already registered; applying creates no second version.",
                        new AttributeChange("schema.id", Optional.of(7), Optional.of(7))));
        when(catalog.register(any(), any(), any(), any()))
                .thenReturn(new SubjectVersion(7, 1, SchemaType.AVRO, "{}", false));

        SchemaPreview preview = reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}");
        var version = reviews.register(CLUSTER, "orders-value", SchemaType.AVRO, "{}", "etag-1");

        assertThat(preview.alreadyRegistered()).isTrue();
        assertThat(reviews.estimate(new SchemaWriteParams(
                                CLUSTER, "register", "orders-value", "AVRO", "{}", null, null, "etag-1"))
                        .count())
                .isZero();
        assertThat(version.id()).isEqualTo(7);
        verify(catalog).register(eq("orders-value"), eq(SchemaType.AVRO), eq("{}"), any());
    }

    @Test
    void aPermanentDeletePreviewSaysSchemasCannotBeRestored() {
        when(catalog.previewDelete("orders-value", true)).thenReturn(preview("etag-1", "gone"));

        SchemaPreview view = reviews.previewDelete(CLUSTER, "orders-value", true);

        assertThat(view.note()).contains("soft-deletes the subject and then deletes the schema");
        assertThat(view.note()).contains("cannot be restored");
        assertThat(view.note()).contains(KafkaSchemaReviews.NOTHING_WRITTEN);
        assertThat(view.action()).isEqualTo("delete");
        assertThat(view.writesNothing()).isTrue();
        verify(catalog, never()).delete(any(), anyBoolean(), any());
    }

    @Test
    void aDriverNoteDoesNotCarryACredentialIntoThePreview() {
        when(catalog.previewRegister(any(), any(), any()))
                .thenReturn(preview("etag-1", "The registry refused password=registry-secret-value"));

        SchemaPreview view = reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}");

        assertThat(view.note()).doesNotContain("registry-secret-value");
        assertThat(view.note()).contains("[redacted]");
    }

    @Test
    void aPreviewIsRefusedWithoutSchemaWrite() {
        Mockito.doThrow(new ResourceForbiddenException("schema:write", "cluster", CLUSTER.toString()))
                .when(access)
                .requireCluster(CLUSTER, KafkaModule.SCHEMA_WRITE);

        assertThatThrownBy(() -> reviews.previewRegister(CLUSTER, "orders-value", SchemaType.AVRO, "{}"))
                .isInstanceOf(ResourceForbiddenException.class)
                .hasMessageContaining("schema:write");
        verify(catalog, never()).previewRegister(any(), any(), any());
    }

    @Test
    void aDeleteIsRefusedWithoutSchemaWrite() {
        Mockito.doThrow(new ResourceForbiddenException("schema:write", "cluster", CLUSTER.toString()))
                .when(access)
                .requireCluster(CLUSTER, KafkaModule.SCHEMA_WRITE);

        assertThatThrownBy(() -> reviews.delete(CLUSTER, "orders-value", false, "etag-1"))
                .isInstanceOf(ResourceForbiddenException.class)
                .hasMessageContaining("schema:write");
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

    @Test
    void anUnavailableRegistryKeepsTheDriversReason() {
        String reason = "registry kind 'confluent' is configured but could not be reached";
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        SchemaCatalog catalog = mock(SchemaCatalog.class);
        KafkaConnectionRepository connections = mock(KafkaConnectionRepository.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.unwrap(SchemaCatalog.class)).thenReturn(Optional.of(catalog));
        when(catalog.capability()).thenReturn(Assessment.unavailable(reason, Optional.empty()));
        when(connections.findById(CLUSTER))
                .thenReturn(Optional.of(new KafkaConnectionEntity(
                        CLUSTER, "k:9092", "PLAINTEXT", null, null, "confluent", "https://r", null)));
        KafkaSchemaRegistry registry = new KafkaSchemaRegistry(gateway, connections, mock(ClusterAccessGuard.class));

        assertThatThrownBy(() -> registry.catalog(CLUSTER))
                .isInstanceOf(SchemaUnavailableException.class)
                .hasMessage(reason);
        ProblemDetail problem =
                new KafkaSchemaProblemAdvice().onUnavailable(new SchemaUnavailableException("confluent", reason));
        assertThat(problem.getDetail()).isEqualTo(reason);
    }

    @Test
    void aPermanentDeleteThatStopsAfterTheSoftDeleteIsInDoubt() {
        BrokerException doubt = new BrokerException(
                Status.ABORTED,
                ErrorInfo.of(ErrorReasons.UNEXPECTED_SYSTEM),
                Dispatch.IN_DOUBT,
                null,
                "Subject not found");

        RuntimeException translated = KafkaSchemaRegistry.translate("confluent", doubt);

        assertThat(translated).isInstanceOf(ConflictException.class).isNotInstanceOf(NotFoundException.class);
        assertThat(translated.getMessage())
                .contains("soft-deleted")
                .contains("in doubt")
                .doesNotContain("not found");
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
