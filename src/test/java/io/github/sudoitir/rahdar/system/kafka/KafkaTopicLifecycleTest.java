package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ClusterOutcome;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PartitionsRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PreviewView;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.TopicApplyRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The topic and group commands share one preview, one refusal and one audit, and one cluster outcome. */
class KafkaTopicLifecycleTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String SECRET = "bootstrap-password-secret";

    private final Operations operations = mock(Operations.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final KafkaTopicLifecycle lifecycle;

    KafkaTopicLifecycleTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(connection.toString()).thenReturn(SECRET);
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolve()).thenReturn(mock(Actor.class));
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(1000);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        org.mockito.Mockito.doAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                })
                .when(gate)
                .run(any(), any());
        lifecycle = new KafkaTopicLifecycle(gateway, access, gate, audit, actors, settings);
    }

    @BeforeEach
    void driverApplies() {
        when(operations.execute(any(), any(), any(), any()))
                .thenReturn(Partial.complete(OperationResult.done(), List.of()));
    }

    @Test
    void deletePreviewNamesGroupsAndTheEstimateAndDoesNotApply() {
        when(operations.preview(any(), any(), any())).thenReturn(deletePreview(120, "etag-1", "billing", "search"));

        PreviewView preview = lifecycle.previewDelete(CLUSTER, "orders");

        assertThat(preview.groups()).containsExactly("billing", "search");
        assertThat(preview.estimate()).isEqualTo(120);
        assertThat(preview.accuracy()).isEqualTo("estimate");
        assertThat(preview.note()).contains("does not erase");
        assertThat(preview.toString()).doesNotContain(SECRET);
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aChangedGroupSetDeletesNothing() {
        when(operations.preview(any(), any(), any()))
                .thenReturn(deletePreview(120, "etag-1", "billing"))
                .thenReturn(deletePreview(120, "etag-1", "billing", "search"));
        PreviewView first = lifecycle.previewDelete(CLUSTER, "orders");
        TopicApplyRequest request = new TopicApplyRequest(first.etag(), first.fingerprint(), false);

        assertThatThrownBy(() -> lifecycle.deleteTopic(CLUSTER, "orders", request))
                .isInstanceOf(KafkaStaleException.class);
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aHeldDeleteDoesNotApplyAndAnApprovedApplyChecksTheEtagAgain() {
        when(operations.preview(any(), any(), any())).thenReturn(deletePreview(10, "etag-1", "billing"));
        org.mockito.Mockito.doReturn(null).when(gate).run(any(), any());
        PreviewView first = lifecycle.previewDelete(CLUSTER, "orders");

        lifecycle.deleteTopic(CLUSTER, "orders", new TopicApplyRequest(first.etag(), first.fingerprint(), false));
        verify(operations, never()).execute(any(), any(), any(), any());

        org.mockito.Mockito.doAnswer(
                        invocation -> invocation.<Supplier<?>>getArgument(1).get())
                .when(gate)
                .run(any(), any());
        when(operations.preview(any(), any(), any())).thenReturn(deletePreview(10, "etag-2", "billing"));
        TopicApplyRequest stale = new TopicApplyRequest("etag-1", first.fingerprint(), false);
        assertThatThrownBy(() -> lifecycle.deleteTopic(CLUSTER, "orders", stale))
                .isInstanceOf(KafkaStaleException.class);
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void theBulkCapRefusesUntilTheOverrideAndTheOverrideIsAudited() {
        when(operations.preview(any(), any(), any())).thenReturn(deletePreview(5000, "etag-1"));
        PreviewView preview = lifecycle.previewDelete(CLUSTER, "orders");
        assertThat(preview.overCap()).isTrue();

        TopicApplyRequest overCap = new TopicApplyRequest(preview.etag(), preview.fingerprint(), false);
        assertThatThrownBy(() -> lifecycle.deleteTopic(CLUSTER, "orders", overCap))
                .isInstanceOf(BulkCapExceededException.class);
        verify(operations, never()).execute(any(), any(), any(), any());

        ClusterOutcome outcome = lifecycle.deleteTopic(
                CLUSTER, "orders", new TopicApplyRequest(preview.etag(), preview.fingerprint(), true));
        assertThat(outcome.scope()).isEqualTo("whole cluster");
        assertThat(outcome.status()).isEqualTo("APPLIED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("DELETE_TOPIC"),
                        eq("DESTINATION"),
                        eq("orders"),
                        eq(CLUSTER),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getValue()).containsEntry("override", true).containsEntry("estimate", 5000L);
        assertThat(params.getValue().toString()).doesNotContain(SECRET);
        verify(audit).finish(any(), eq(false), eq(5000L), isNull(), any());
    }

    @Test
    void aReadOnlyTokenIsRefusedBeforeThePreview() {
        doThrow(new NotFoundException("permission"))
                .when(access)
                .requireCreate(CLUSTER, ResourceRef.destination("orders"), ResourcePermissions.DESTINATION_CREATE);

        CreateRequest request = new CreateRequest("orders", 6, 3, Map.of("cleanup.policy", "delete"), null);
        assertThatThrownBy(() -> lifecycle.previewCreate(CLUSTER, request)).isInstanceOf(NotFoundException.class);
        verify(operations, never()).preview(any(), any(), any());
    }

    @Test
    void createIsOneClusterOutcome() {
        when(operations.preview(any(), any(), any())).thenReturn(createPreview("etag-c"));

        ClusterOutcome outcome = lifecycle.create(
                CLUSTER, new CreateRequest("orders", 6, 3, Map.of("cleanup.policy", "delete"), "etag-c"));

        assertThat(outcome).isEqualTo(new ClusterOutcome("whole cluster", "APPLIED"));
        verify(operations)
                .execute(
                        eq(ResourceId.of("kafka.topic", "orders")),
                        any(Operation.Create.class),
                        eq(Scope.whole()),
                        any());
    }

    @Test
    void increasingPartitionsAuditsBothCounts() {
        when(operations.preview(any(), any(), any())).thenReturn(increasePreview(6, "etag-p"));

        lifecycle.increasePartitions(CLUSTER, "orders", new PartitionsRequest(12, "etag-p"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("INCREASE_PARTITIONS"),
                        eq("DESTINATION"),
                        eq("orders"),
                        eq(CLUSTER),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getValue()).containsEntry("from", "6").containsEntry("to", "12");
    }

    @Test
    void aGroupWithMembersIsRefusedByName() {
        when(operations.preview(any(), any(), eq(Scope.whole())))
                .thenThrow(new BrokerException(
                        Status.FAILED_PRECONDITION,
                        ErrorInfo.of(ErrorReasons.HAS_CONSUMERS),
                        Dispatch.NOT_SENT,
                        null,
                        "kafka.group/billing has members: app@host (member-1), app@host (member-2)."));

        assertThatThrownBy(() -> lifecycle.previewDeleteGroup(CLUSTER, "billing"))
                .hasMessageContaining("member-1")
                .hasMessageContaining("member-2");
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    private static Preview deletePreview(long records, String etag, String... groups) {
        List<AttributeChange> diff = new java.util.ArrayList<>();
        diff.add(new AttributeChange(KafkaKinds.PARTITIONS, Optional.of(6L), Optional.empty()));
        diff.add(new AttributeChange(
                KafkaKinds.NOTE,
                Optional.empty(),
                Optional.of("Deleting this topic does not erase those groups' offsets.")));
        for (String group : groups) {
            diff.add(new AttributeChange(
                    KafkaKinds.OFFSETS_KEPT + group, Optional.empty(), Optional.of("offsets remain")));
        }
        return new Preview(
                diff,
                Set.of(),
                new Estimate(Count.estimate(records), "{message}"),
                etag,
                EtagBasis.INCARNATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    private static Preview createPreview(String etag) {
        return new Preview(
                List.of(new AttributeChange(
                        KafkaKinds.NOTE,
                        Optional.empty(),
                        Optional.of("The cluster accepted this topic. Nothing has been created."))),
                Set.of(),
                new Estimate(Count.exact(1), "{topic}"),
                etag,
                EtagBasis.CONFIGURATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    private static Preview increasePreview(int current, String etag) {
        return new Preview(
                List.of(
                        new AttributeChange(KafkaKinds.PARTITIONS, Optional.of((long) current), Optional.of(12L)),
                        new AttributeChange(
                                KafkaKinds.NOTE,
                                Optional.empty(),
                                Optional.of("Records already written stay on their partitions."))),
                Set.of(),
                new Estimate(Count.exact(1), "{topic}"),
                etag,
                EtagBasis.INCARNATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }
}
