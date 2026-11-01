package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService.QueueDeleteParams;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import io.github.sudoitir.rahdar.platform.broker.WholeClusterOperations;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A cluster read as one changes a resource through its driver, by id (ADR-0224). The driver is the SDK's
 * operations, mocked at that seam; {@code BrokerCommands}, the per-node fan-out, is never touched.
 */
class QueueLifecycleServiceWholeClusterTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final ResourceId TOPIC = ResourceId.of("kafka.topic", "orders");
    private static final ResourceId QUEUE = ResourceId.of("rabbitmq.queue.classic", "orders");
    private static final int CAP = 100;

    private final Operations driver = mock(Operations.class);
    private final BrokerCommands commands = mock(BrokerCommands.class);
    private final AuditService audit = mock(AuditService.class);
    private final AuditEvent event = mock(AuditEvent.class);
    private final ClusterKinds kinds = mock(ClusterKinds.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final SseHub sse = mock(SseHub.class);
    private final QueueSnapshotUpsert snapshots = mock(QueueSnapshotUpsert.class);
    private QueueLifecycleService lifecycle;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(connection.operations()).thenReturn(driver);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        SettingsService settings = mock(SettingsService.class);
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(CAP);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(event);
        when(kinds.readAsOne(CLUSTER)).thenReturn(true);
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        lifecycle = new QueueLifecycleService(
                mock(QueueLocator.class),
                snapshots,
                Optional.empty(),
                Optional.empty(),
                sse,
                commands,
                mock(ClusterAccessGuard.class),
                mock(PermissionResolver.class),
                gate,
                mock(BrokerMessages.class),
                mock(QueueCounters.class),
                kinds,
                new WholeClusterLifecycle(
                        new WholeClusterOperations(gateway), audit, mock(ActorResolver.class), settings));
    }

    private static Preview preview(Count count, String unit, EtagBasis basis) {
        return new Preview(
                List.of(),
                Set.of(),
                new Estimate(count, unit),
                "etag-1",
                basis,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    private static Partial<OperationResult> done(OperationResult result) {
        return new Partial<>(result, Map.of(), Map.of());
    }

    private static BrokerException refused(String reason) {
        return new BrokerException(Status.FAILED_PRECONDITION, ErrorInfo.of(reason), Dispatch.REJECTED, null, "no");
    }

    private static NodeOutcome only(Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>) attempt).value();
        assertThat(outcome.nodes()).hasSize(1);
        return outcome.nodes().getFirst();
    }

    private static LifecycleOutcome outcome(Attempt<LifecycleOutcome> attempt) {
        return ((Attempt.Ok<LifecycleOutcome>) attempt).value();
    }

    // ---- a Kafka-like cluster: nodes without a management URL ------------------------------

    @Test
    void aKafkaTopicIsDeletedThroughTheDriverAndNeverThroughTheNodeFanOut() {
        when(driver.preview(TOPIC, new Operation.Delete(false, false), Scope.whole()))
                .thenReturn(preview(Count.exact(1), "{topic}", EtagBasis.INCARNATION));
        when(driver.execute(eq(TOPIC), any(), any(), any())).thenReturn(done(OperationResult.done()));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.deleteAddress(CLUSTER, new Target(TOPIC, ResourceClass.DESTINATION), false);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(outcome(attempt).identityBasis()).isEqualTo(IdentityBasis.INCARNATION);
        verify(driver)
                .execute(TOPIC, new Operation.Delete(false, false), Scope.whole(), Precondition.ifMatch("etag-1"));
        verify(audit).finish(eq(event), eq(false), anyLong(), isNull(), any());
        verifyNoInteractions(commands);
    }

    @Test
    void aKafkaTopicIsCreatedThroughTheDriverAndNeverThroughTheNodeFanOut() {
        when(driver.execute(eq(TOPIC), any(), any(), any())).thenReturn(done(OperationResult.done()));
        CreateDestinationRequest request =
                new CreateDestinationRequest("kafka.topic", "orders", Map.of("kafka.partitions", 3));

        Attempt<LifecycleOutcome> attempt = lifecycle.createAddress(CLUSTER, request, false);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.APPLIED);
        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(driver).execute(eq(TOPIC), operation.capture(), eq(Scope.whole()), eq(Precondition.none()));
        assertThat(((Operation.Create) operation.getValue()).attributes().raw("kafka.partitions"))
                .hasValue(3);
        verifyNoInteractions(commands);
    }

    @Test
    void aCreateThatExistsAsAskedIsAlready() {
        when(driver.execute(eq(TOPIC), any(), any(), any())).thenReturn(done(OperationResult.already()));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.createAddress(CLUSTER, new CreateDestinationRequest("kafka.topic", "orders", null), false);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.ALREADY);
    }

    @Test
    void aCreatePreviewChangesNothing() {
        when(driver.preview(eq(TOPIC), any(), eq(Scope.whole())))
                .thenReturn(preview(Count.exact(1), "{topic}", EtagBasis.CONFIGURATION));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.createAddress(CLUSTER, new CreateDestinationRequest("kafka.topic", "orders", null), true);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(outcome(attempt).dryRun()).isTrue();
        assertThat(outcome(attempt).identityBasis()).isEqualTo(IdentityBasis.CONFIGURATION);
        verify(driver, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aSubscriptionIsCreatedBoundToItsDestinationAndAPathNameIsSeveralSegments() {
        ResourceId queue = ResourceId.of("rabbitmq.queue.classic", "vh", "orders");
        when(driver.execute(eq(queue), any(), any(), any())).thenReturn(done(OperationResult.done()));
        CreateSubscriptionRequest request = new CreateSubscriptionRequest(
                "rabbitmq.queue.classic", "vh/orders", "rabbitmq.exchange/vh/ex", Map.of("durable", true));

        Attempt<LifecycleOutcome> attempt = lifecycle.createQueue(CLUSTER, request, false);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.APPLIED);
        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(driver).execute(eq(queue), operation.capture(), eq(Scope.whole()), eq(Precondition.none()));
        Attributes attributes = ((Operation.Create) operation.getValue()).attributes();
        assertThat(attributes.get(StandardAttributes.DESTINATION))
                .hasValue(ResourceId.of("rabbitmq.exchange", "vh", "ex"));
        assertThat(attributes.raw("durable")).hasValue(true);
        verifyNoInteractions(commands);
    }

    @Test
    void aDriverThatRefusesACreateIsAFailedOutcomeWithItsWords() {
        when(driver.execute(eq(TOPIC), any(), any(), any())).thenThrow(refused(ErrorReasons.INVALID_ARGUMENT));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.createAddress(CLUSTER, new CreateDestinationRequest("kafka.topic", "orders", null), false);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.FAILED);
        verify(audit).finish(eq(event), eq(true), anyLong(), any(), any());
    }

    // ---- delete ---------------------------------------------------------------------------

    @Test
    void aQueueDeletePreviewReportsTheMessagesAndWhatTheEtagHolds() {
        when(driver.preview(QUEUE, new Operation.Delete(false, true), Scope.whole()))
                .thenReturn(preview(Count.estimate(12), "message", EtagBasis.CONFIGURATION));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.deleteQueue(CLUSTER, new Target(QUEUE, ResourceClass.SUBSCRIPTION), true, false, true);

        NodeOutcome node = only(attempt);
        assertThat(node.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(node.affected()).isEqualTo(12L);
        assertThat(node.error()).contains("does not tell a recreated resource apart");
        assertThat(outcome(attempt).identityBasis()).isEqualTo(IdentityBasis.CONFIGURATION);
        verify(driver, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aDeleteTheDriverRefusesFromThePreviewChangesNothing() {
        when(driver.preview(eq(QUEUE), any(), any())).thenThrow(refused(ErrorReasons.HAS_CONSUMERS));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.deleteQueue(CLUSTER, new Target(QUEUE, ResourceClass.SUBSCRIPTION), true, false, false);

        assertThat(only(attempt).status()).isEqualTo(NodeStatus.FAILED);
        assertThat(only(attempt).error()).contains("consumers");
        verify(driver, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aRealDeleteIsHeldByTheGateWithTheResourceId() {
        when(driver.preview(eq(QUEUE), any(), any()))
                .thenReturn(preview(Count.exact(2), "message", EtagBasis.INCARNATION));
        when(driver.execute(eq(QUEUE), any(), any(), any())).thenReturn(done(OperationResult.affected(2)));

        Attempt<LifecycleOutcome> attempt =
                lifecycle.deleteQueue(CLUSTER, new Target(QUEUE, ResourceClass.SUBSCRIPTION), false, false, false);

        ArgumentCaptor<io.github.sudoitir.rahdar.kernel.gate.Operation> held =
                ArgumentCaptor.forClass(io.github.sudoitir.rahdar.kernel.gate.Operation.class);
        verify(gate).run(held.capture(), any());
        assertThat(held.getValue().params())
                .isEqualTo(new QueueDeleteParams(CLUSTER, "orders", QUEUE.format(), false, false));
        assertThat(only(attempt).affected()).isEqualTo(2L);
        verify(snapshots).forget(CLUSTER, "orders");
        verify(sse).publishAbout(eq(CLUSTER), any(), any(Collection.class));
    }

    @Test
    void aDeleteWhoseEtagMovedIsStaleAndTheRowSaysSo() {
        when(driver.preview(eq(QUEUE), any(), any()))
                .thenReturn(preview(Count.exact(2), "message", EtagBasis.INCARNATION));
        when(driver.execute(eq(QUEUE), any(), any(), any())).thenThrow(refused(ErrorReasons.PRECONDITION_FAILED));

        assertThatThrownBy(() -> lifecycle.deleteQueue(
                        CLUSTER, new Target(QUEUE, ResourceClass.SUBSCRIPTION), false, false, false))
                .isInstanceOf(StalePreviewException.class);
        verify(audit).fail(eq(event), any());
    }

    @Test
    void aDeleteOverTheCapNeedsTheOverride() {
        when(driver.preview(eq(QUEUE), any(), any()))
                .thenReturn(preview(Count.exact(CAP + 1L), "message", EtagBasis.INCARNATION));

        assertThatThrownBy(() -> lifecycle.deleteQueue(
                        CLUSTER, new Target(QUEUE, ResourceClass.SUBSCRIPTION), false, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(driver, never()).execute(any(), any(), any(), any());

        when(driver.execute(eq(QUEUE), any(), any(), any())).thenReturn(done(OperationResult.done()));
        assertThat(only(lifecycle.deleteQueue(
                                CLUSTER, new Target(QUEUE, ResourceClass.SUBSCRIPTION), false, true, false))
                        .status())
                .isEqualTo(NodeStatus.APPLIED);
    }

    @Test
    void theGatesEstimateIsThePreviewsCountAndEtag() {
        when(driver.preview(eq(QUEUE), any(), any()))
                .thenReturn(preview(Count.exact(7), "message", EtagBasis.CONFIGURATION));

        QueueLifecycleService.Reach reach =
                lifecycle.reachOfDelete(CLUSTER, QUEUE, LifecycleKind.DELETE_SUBSCRIPTION, false);

        assertThat(reach.count()).isEqualTo(7);
        assertThat(reach.identity()).isEqualTo("etag-1");
        assertThat(reach.detail()).contains("does not tell a recreated resource apart");
    }

    @Test
    void aGoneResourceIsNotFoundForTheGatesEstimate() {
        when(driver.preview(eq(QUEUE), any(), any())).thenThrow(refused(ErrorReasons.RESOURCE_GONE));

        assertThatThrownBy(() -> lifecycle.reachOfDelete(CLUSTER, QUEUE, LifecycleKind.DELETE_SUBSCRIPTION, false))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- an Artemis cluster keeps its per-node path ---------------------------------------

    @Test
    void aClusterWithManagementUrlsStaysOnTheNodeFanOut() {
        when(kinds.readAsOne(CLUSTER)).thenReturn(false);
        LifecycleOutcome fanned = new LifecycleOutcome(true, CAP, false, List.of());
        when(commands.run(any())).thenReturn(fanned);

        Attempt<LifecycleOutcome> attempt =
                lifecycle.deleteAddress(CLUSTER, new Target(TOPIC, ResourceClass.DESTINATION), true);

        assertThat(outcome(attempt)).isSameAs(fanned);
        verifyNoInteractions(driver);
    }
}
