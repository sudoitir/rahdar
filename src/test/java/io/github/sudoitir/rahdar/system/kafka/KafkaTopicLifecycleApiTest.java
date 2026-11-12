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

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
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
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ClusterOutcome;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PartitionsRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PreviewView;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.TopicApplyRequest;
import io.github.sudoitir.rahdar.system.kafka.mcp.KafkaMcpActionTools;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaController;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
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
 * The HTTP commands and the MCP tools call the same lifecycle. A preview applies nothing, a read-only key is refused
 * before a hold, and a delete records the groups and the estimate before and after.
 */
class KafkaTopicLifecycleApiTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String SECRET = "bootstrap-password-secret";

    private final Operations operations = mock(Operations.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaController http;
    private final KafkaMcpActionTools tools;

    KafkaTopicLifecycleApiTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(connection.toString()).thenReturn(SECRET);
        SettingsService settings = mock(SettingsService.class);
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(1000);
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolve()).thenReturn(mock(Actor.class));
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        org.mockito.Mockito.doAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                })
                .when(gate)
                .run(any(), any());
        KafkaTopicLifecycle lifecycle = new KafkaTopicLifecycle(gateway, access, gate, audit, actors, settings);
        http = new KafkaController(null, null, null, null, null, null, null, null, null, null, null, lifecycle);
        tools = new KafkaMcpActionTools(null, null, null, null, null, null, null, lifecycle);
    }

    @BeforeEach
    void driverApplies() {
        when(operations.execute(any(), any(), any(), any()))
                .thenReturn(Partial.complete(OperationResult.done(), List.of()));
    }

    @Test
    void theHttpAndMcpPreviewsNameTheSameGroupsAndApplyNothing() {
        when(operations.preview(any(), any(), eq(Scope.whole()))).thenReturn(deletePreview(4, "etag-1", "billing"));

        PreviewView httpPreview = http.previewDelete(CLUSTER, "orders");
        CallToolResult mcpPreview = tools.kafkaDeleteTopic(CLUSTER.toString(), "orders", null, null, null, null, null);

        assertThat(httpPreview.groups()).containsExactly("billing");
        assertThat(httpPreview.note()).contains("does not erase");
        assertThat(httpPreview.estimate()).isEqualTo(4);
        assertThat(mcpPreview.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(mcpPreview.toString())
                .contains("billing")
                .contains("does not erase")
                .doesNotContain(SECRET);
        assertThat(httpPreview.toString()).doesNotContain(SECRET);
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void eachCommandUsesItsExistingPermission() {
        when(operations.preview(any(), any(), any())).thenReturn(createPreview("etag-c"));

        http.previewCreate(CLUSTER, new CreateRequest("orders", 6, 3, Map.of("cleanup.policy", "delete"), null));
        http.previewDelete(CLUSTER, "orders");
        http.previewPartitions(CLUSTER, "orders", new PartitionsRequest(12, null));
        http.previewDeleteGroup(CLUSTER, "billing");

        verify(access)
                .requireCreate(
                        eq(CLUSTER), eq(ResourceRef.destination("orders")), eq(ResourcePermissions.DESTINATION_CREATE));
        verify(access)
                .requireResource(
                        eq(CLUSTER), eq(ResourceRef.destination("orders")), eq(ResourcePermissions.DESTINATION_DELETE));
        verify(access)
                .requireResource(
                        eq(CLUSTER), eq(ResourceRef.destination("orders")), eq(ResourcePermissions.DESTINATION_UPDATE));
        verify(access)
                .requireResource(
                        eq(CLUSTER),
                        eq(ResourceRef.subscription("billing")),
                        eq(ResourcePermissions.SUBSCRIPTION_DELETE));
    }

    @Test
    void aChangedEstimateAndAMissingConfirmChangeNothing() {
        when(operations.preview(any(), any(), any()))
                .thenReturn(deletePreview(10, "etag-1", "billing"))
                .thenReturn(deletePreview(11, "etag-1", "billing"));
        PreviewView first = http.previewDelete(CLUSTER, "orders");

        assertThatThrownBy(() -> http.deleteTopic(
                        CLUSTER, "orders", new TopicApplyRequest(first.etag(), first.fingerprint(), false)))
                .isInstanceOf(KafkaStaleException.class);
        assertThatThrownBy(() -> tools.kafkaDeleteTopic(
                        CLUSTER.toString(), "orders", "etag-1", first.fingerprint(), false, false, "order"))
                .isInstanceOf(McpError.class);
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aReadOnlyKeyIsRefusedBeforeTheHold() {
        doThrow(new NotFoundException("hidden"))
                .when(access)
                .requireResource(any(), any(), eq(ResourcePermissions.DESTINATION_DELETE));

        CallToolResult refused =
                tools.kafkaDeleteTopic(CLUSTER.toString(), "orders", "etag-1", "fp", false, false, "orders");

        assertThat(refused.isError()).isTrue();
        assertThat(refused.toString()).doesNotContain(SECRET);
        verify(gate, never()).run(any(), any());
        verify(operations, never()).preview(any(), any(), any());
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aDeleteRecordsTheGroupsAndTheEstimateBeforeAndAfter() {
        when(operations.preview(any(), any(), any())).thenReturn(deletePreview(4, "etag-1", "billing", "search"));
        PreviewView preview = http.previewDelete(CLUSTER, "orders");

        ClusterOutcome outcome = http.deleteTopic(
                CLUSTER, "orders", new TopicApplyRequest(preview.etag(), preview.fingerprint(), false));

        assertThat(outcome).isEqualTo(new ClusterOutcome("whole cluster", "APPLIED"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("DELETE_TOPIC"),
                        eq("DESTINATION"),
                        eq("orders"),
                        eq(CLUSTER),
                        any(),
                        before.capture(),
                        eq(false));
        assertThat(before.getValue())
                .containsEntry("groups", List.of("billing", "search"))
                .containsEntry("estimate", 4L)
                .containsEntry("override", false);
        assertThat(before.getValue().toString()).doesNotContain(SECRET);
        ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
        verify(audit).finish(any(), eq(false), eq(4L), isNull(), after.capture());
        assertThat(after.getValue().toString())
                .contains("billing")
                .contains("search")
                .doesNotContain(SECRET);
        verify(operations)
                .execute(
                        eq(ResourceId.of("kafka.topic", "orders")),
                        any(Operation.Delete.class),
                        eq(Scope.whole()),
                        any());
    }

    @Test
    void mcpGroupDeleteLeavesTheFingerprintUnsetAndDoesNotRemoveMembers() {
        when(operations.preview(any(), any(), eq(Scope.whole()))).thenReturn(groupPreview("etag-g"));

        CallToolResult dry = tools.kafkaDeleteGroup(CLUSTER.toString(), "billing", null, null, null);
        assertThat(dry.isError()).isNotEqualTo(Boolean.TRUE);
        verify(operations, never()).execute(any(), any(), any(), any());

        CallToolResult applied = tools.kafkaDeleteGroup(CLUSTER.toString(), "billing", "etag-g", false, "billing");

        assertThat(applied.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(applied.toString()).contains("whole cluster").doesNotContain(SECRET);
        verify(operations)
                .execute(
                        eq(ResourceId.of("kafka.group", "billing")),
                        any(Operation.Delete.class),
                        eq(Scope.whole()),
                        any());
    }

    private static Preview deletePreview(long records, String etag, String... groups) {
        List<AttributeChange> diff = new ArrayList<>();
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

    private static Preview groupPreview(String etag) {
        return new Preview(
                List.of(new AttributeChange(KafkaKinds.NOTE, Optional.empty(), Optional.of("The group is empty."))),
                Set.of(),
                new Estimate(Count.exact(0), "{group}"),
                etag,
                EtagBasis.INCARNATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }
}
