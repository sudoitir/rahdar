package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.Cut;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.TruncateEffect;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** A cut names offsets or a time, and its effect names every group that loses records, the biggest loss first. */
class TruncateServiceTest {

    private static AttributeChange change(String key, long before, long after) {
        return new AttributeChange(key, Optional.of(before), Optional.of(after));
    }

    @Test
    void theEffectListsTheRecordsAndEachGroupThatLosesSomeBiggestFirst() {
        Preview preview = new Preview(
                List.of(
                        change("kafka.log_start_offset.0", 0, 6),
                        change("kafka.unread_lost.billing", 0, 3),
                        change("kafka.unread_lost.audit", 0, 5)),
                Set.of(),
                new Estimate(Count.estimate(6), "{message}"),
                "etag",
                PreconditionEnforcement.CHECKED_BEFORE);

        TruncateEffect effect = TruncateService.effectOf(preview);

        assertThat(effect.records()).isEqualTo(6);
        assertThat(effect.groups())
                .extracting(g -> g.group() + ":" + g.records())
                .containsExactly("audit:5", "billing:3");
    }

    @Test
    void anEstimateTheDriverCannotGiveIsAbsentNotZero() {
        Preview preview = new Preview(
                List.of(),
                Set.of(),
                new Estimate(new Count.Unavailable("not countable"), "{message}"),
                "etag",
                PreconditionEnforcement.CHECKED_BEFORE);

        assertThat(TruncateService.effectOf(preview).records()).isNull();
    }

    @Test
    void aCutIsOffsetsOrATimeAndNotBothNorNeither() {
        assertThat(new Cut(Map.of(0, 5L), null).offsets()).containsEntry(0, 5L);
        assertThat(new Cut(null, Instant.parse("2026-10-28T09:30:00Z")).time()).isNotNull();
        assertThatThrownBy(() -> new Cut(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cut(Map.of(0, 5L), Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCutNeverNamesANegativePartitionOrOffset() {
        assertThatThrownBy(() -> new Cut(Map.of(-1, 5L), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cut(Map.of(0, -5L), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRealRunNeedsThePreviewsEtagAndIsRefusedStaleWhenTheLogMovedAndTheAuditRowIsClosed() {
        Fixture f = new Fixture();
        Cut cut = new Cut(Map.of(0, 5L), null);

        assertThatThrownBy(() -> f.service.truncate(f.cluster, "kafka.topic", "orders", cut, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("etag");
        assertThatThrownBy(() -> f.service.truncate(f.cluster, "kafka.topic", "orders", cut, "old", false))
                .isInstanceOf(StalePreviewException.class);

        verify(f.audit).fail(any(), any());
        verify(f.operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aCutOfAPartitionTheLogDoesNotHaveIsRefusedBeforeAnythingIsCut() {
        Fixture f = new Fixture();

        assertThatThrownBy(() -> f.service.truncate(
                        f.cluster, "kafka.topic", "orders", new Cut(Map.of(7, 5L), null), "e1", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Partition 7");

        verify(f.audit).fail(any(), any());
        verify(f.operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void thePreviewCarriesTheEtagARealRunSendsBack() {
        Fixture f = new Fixture();

        TruncateEffect effect = f.service.preview(f.cluster, "kafka.topic", "orders", new Cut(Map.of(0, 5L), null));

        assertThat(effect.etag()).isEqualTo("e1");
    }

    /** A service over a log with one partition, whose preview has the etag {@code e1}. */
    private static final class Fixture {
        final UUID cluster = UUID.randomUUID();
        final Operations operations = mock(Operations.class);
        final AuditService audit = mock(AuditService.class);
        final TruncateService service;

        @SuppressWarnings("unchecked")
        Fixture() {
            ResourceTargets targets = mock(ResourceTargets.class);
            when(targets.require(any(), any(), any(), any()))
                    .thenReturn(new ResourceTargets.Target(
                            ResourceId.of("kafka.topic", "orders"), ResourceClass.DESTINATION));
            BrokerGateway gateway = mock(BrokerGateway.class);
            ClusterConnection connection = mock(ClusterConnection.class);
            when(gateway.cluster(any())).thenReturn(connection);
            when(connection.operations()).thenReturn(operations);
            when(operations.preview(any(), any(), any()))
                    .thenReturn(new Preview(
                            List.of(change("kafka.log_start_offset.0", 0, 5)),
                            Set.of(),
                            new Estimate(Count.exact(5), "{message}"),
                            "e1",
                            PreconditionEnforcement.CHECKED_BEFORE));
            OperationGate gate = mock(OperationGate.class);
            when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
            when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(mock(AuditEvent.class));
            service = new TruncateService(
                    targets, gateway, mock(ClusterAccessGuard.class), audit, mock(ActorResolver.class), gate);
        }
    }
}
