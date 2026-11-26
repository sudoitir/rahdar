package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaWriteParams;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** An approved apply checks the etag again, and writes nothing when the registry has moved. */
class KafkaSchemaApprovalTest {

    @Test
    void aStaleEtagAfterApprovalWritesNothing() {
        UUID cluster = UUID.randomUUID();
        SchemaCatalog catalog = mock(SchemaCatalog.class);
        KafkaSchemaRegistry registries = mock(KafkaSchemaRegistry.class);
        when(registries.call(eq(cluster), any())).thenAnswer(invocation -> {
            Function<SchemaCatalog, ?> call = invocation.getArgument(1);
            return call.apply(catalog);
        });
        when(catalog.previewRegister(any(), any(), any()))
                .thenReturn(new Preview(
                        List.of(new AttributeChange("note", Optional.empty(), Optional.of("moved"))),
                        Set.of(),
                        new Estimate(Count.exact(0), "{schema}"),
                        "now",
                        EtagBasis.CONFIGURATION,
                        PreconditionEnforcement.CHECKED_BEFORE));
        KafkaSchemaReviews reviews = new KafkaSchemaReviews(
                registries,
                mock(ClusterAccessGuard.class),
                mock(OperationGate.class),
                mock(AuditService.class),
                mock(ActorResolver.class));

        assertThatThrownBy(() -> reviews.replay(
                        new SchemaWriteParams(cluster, "register", "orders-value", "AVRO", "{}", null, null, "then")))
                .isInstanceOf(StalePreviewException.class);
        verify(catalog, never()).register(any(), any(), any(), any());
    }
}
