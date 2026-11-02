package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The whole-cluster path asks the driver by resource id over the whole cluster, and maps a stale etag (ADR-0224). */
class WholeClusterOperationsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final ResourceId ID = ResourceId.of("kafka.topic", "orders");
    private static final Operation DELETE = Operation.delete();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final Operations driver = mock(Operations.class);
    private final WholeClusterOperations operations = new WholeClusterOperations(gateway);

    WholeClusterOperationsTest() {
        ClusterConnection connection = mock(ClusterConnection.class);
        when(connection.operations()).thenReturn(driver);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
    }

    private static Preview preview(Count count, String unit, EtagBasis basis) {
        return new Preview(
                List.of(), Set.of(), new Estimate(count, unit), "etag-1", basis, PreconditionEnforcement.ATOMIC);
    }

    private static BrokerException refused(String reason) {
        return new BrokerException(Status.FAILED_PRECONDITION, ErrorInfo.of(reason), Dispatch.REJECTED, null, "no");
    }

    @Test
    void aPreviewIsTheDriversOverTheWholeCluster() {
        Preview expected = preview(Count.exact(4), "message", EtagBasis.INCARNATION);
        when(driver.preview(ID, DELETE, Scope.whole())).thenReturn(expected);

        assertThat(operations.preview(CLUSTER, ID, DELETE)).isSameAs(expected);
    }

    @Test
    void aRunWithAnEtagRunsOnlyOnWhatWasPreviewed() {
        when(driver.execute(any(), any(), any(), any()))
                .thenReturn(new Partial<>(OperationResult.affected(3), Map.of(), Map.of()));

        OperationResult result = operations.execute(CLUSTER, ID, DELETE, "etag-1");

        assertThat(result.affected()).hasValue(3);
        verify(driver).execute(ID, DELETE, Scope.whole(), Precondition.ifMatch("etag-1"));
    }

    @Test
    void aRunWithoutAnEtagIsUnconditional() {
        when(driver.execute(any(), any(), any(), any()))
                .thenReturn(new Partial<>(OperationResult.done(), Map.of(), Map.of()));

        operations.execute(CLUSTER, ID, DELETE, null);

        verify(driver).execute(ID, DELETE, Scope.whole(), Precondition.none());
    }

    @Test
    void anEtagThatNoLongerMatchesIsStale() {
        when(driver.execute(any(), any(), any(), any())).thenThrow(refused(ErrorReasons.PRECONDITION_FAILED));

        assertThatThrownBy(() -> operations.execute(CLUSTER, ID, DELETE, "etag-1"))
                .isInstanceOf(StalePreviewException.class)
                .hasMessageContaining("changed since the preview");
    }

    @Test
    void anyOtherRefusalIsTheDriversOwn() {
        when(driver.execute(eq(ID), any(), any(), any())).thenThrow(refused(ErrorReasons.HAS_CONSUMERS));

        assertThatThrownBy(() -> operations.execute(CLUSTER, ID, DELETE, "etag-1"))
                .isInstanceOf(BrokerException.class);
    }

    @Test
    void aFailedPreconditionWithoutAnEtagIsNotStale() {
        when(driver.execute(any(), any(), any(), any())).thenThrow(refused(ErrorReasons.PRECONDITION_FAILED));

        assertThatThrownBy(() -> operations.execute(CLUSTER, ID, DELETE, null)).isInstanceOf(BrokerException.class);
    }

    @Test
    void theBasisIsWhatThePreviewsEtagHolds() {
        assertThat(operations.basis(preview(Count.exact(1), "message", EtagBasis.INCARNATION)))
                .isEqualTo(IdentityBasis.INCARNATION);
        assertThat(operations.basis(preview(Count.exact(1), "message", EtagBasis.CONFIGURATION)))
                .isEqualTo(IdentityBasis.CONFIGURATION);
        assertThat(IdentityBasis.INCARNATION.note()).isNull();
        assertThat(IdentityBasis.CONFIGURATION.note()).contains("does not tell a recreated resource apart");
    }

    @Test
    void onlyACountOfMessagesIsMessages() {
        assertThat(WholeClusterOperations.messages(preview(Count.exact(4), "message", EtagBasis.INCARNATION)))
                .isEqualTo(Optional.of(4L));
        assertThat(WholeClusterOperations.messages(preview(Count.estimate(5), "message", EtagBasis.INCARNATION)))
                .isEqualTo(Optional.of(5L));
        assertThat(WholeClusterOperations.messages(
                        preview(Count.unavailable("sampled"), "message", EtagBasis.INCARNATION)))
                .isEmpty();
        assertThat(WholeClusterOperations.messages(preview(Count.exact(1), "{topic}", EtagBasis.INCARNATION)))
                .isEmpty();
    }
}
