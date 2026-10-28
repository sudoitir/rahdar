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

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingAborts.HangingAbortParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingPage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hanging transactions are found on demand, aborted one partition at a time, and pinned to what was previewed. */
class KafkaHangingTransactionsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final Operations operations = mock(Operations.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaHangingTransactions hanging;
    private final KafkaHangingAborts aborts;

    KafkaHangingTransactionsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(connection.operations()).thenReturn(operations);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        hanging = new KafkaHangingTransactions(gateway, mock(ClusterAccessGuard.class));
        aborts = new KafkaHangingAborts(hanging, gateway, mock(OperationGate.class), audit, mock(ActorResolver.class));
    }

    private static Resource resource(String topic, int partition, long producer) {
        return new Resource(
                ResourceId.of("kafka.hanging-transaction", topic, String.valueOf(partition), String.valueOf(producer)),
                Attributes.builder()
                        .putRaw("kafka.producer_epoch", 3L)
                        .putRaw("kafka.transaction_start_offset", 100L)
                        .putRaw("kafka.records_held_back", 42L)
                        .putRaw("kafka.hanging_age_ms", 3_600_000L)
                        .build(),
                List.of(),
                List.of(),
                Instant.now());
    }

    private void previewIs(long heldBack, String etag) {
        when(operations.preview(any(), any(), any()))
                .thenReturn(new Preview(
                        List.of(),
                        Set.of(),
                        new Estimate(Count.exact(heldBack), "{record}"),
                        etag,
                        PreconditionEnforcement.CHECKED_BEFORE));
    }

    @Test
    void theFindListsEachHangingTransactionWithWhatItHoldsBack() {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(
                        new ResourcePage(List.of(resource("orders", 0, 4711)), Optional.empty()), List.of()));

        HangingPage page = hanging.find(CLUSTER, "orders", null, 10);

        assertThat(page.items()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo("kafka.hanging-transaction/orders/0/4711");
            assertThat(t.topic()).isEqualTo("orders");
            assertThat(t.partition()).isZero();
            assertThat(t.producerId()).isEqualTo(4711);
            assertThat(t.recordsHeldBack()).isEqualTo(42);
            assertThat(t.ageMs()).isEqualTo(3_600_000L);
        });
    }

    @Test
    void thePreviewNamesTheRecordsItReleasesAndTheFencedProducer() {
        previewIs(42, "e1");

        HangingAbortPreview preview = hanging.abortPreview(CLUSTER, "orders", 0, 4711);

        assertThat(preview.recordsHeldBack()).isEqualTo(42);
        assertThat(preview.effect())
                .contains("42 record(s)")
                .contains("producer 4711")
                .contains("fenced");
        assertThat(preview.etag()).isEqualTo("e1");
    }

    @Test
    void anAbortWithAStaleEtagIsRefusedBeforeAnythingIsAudited() {
        previewIs(42, "e2");

        assertThatThrownBy(() -> aborts.abort(CLUSTER, "orders", 0, 4711, "e1"))
                .isInstanceOf(StalePreviewException.class);

        verify(audit, never()).begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void anApprovedAbortRunsAndRecordsWhatItAbortedAndAFailureClosesTheRow() {
        previewIs(42, "e1");
        OperationResult done = mock(OperationResult.class);
        when(done.alreadyInState()).thenReturn(false);
        when(operations.execute(any(), any(), any(), any()))
                .thenReturn(Partial.complete(done, List.of()))
                .thenThrow(new IllegalStateException("closed meanwhile"));
        HangingAbortParams params = new HangingAbortParams(CLUSTER, "orders", 0, 4711, "e1");

        aborts.replay(params);
        verify(audit).finish(any(), anyBoolean(), eq(1L), any(), any());
        assertThatThrownBy(() -> aborts.replay(params)).isInstanceOf(IllegalStateException.class);
        verify(audit).fail(any(), any());
    }
}
