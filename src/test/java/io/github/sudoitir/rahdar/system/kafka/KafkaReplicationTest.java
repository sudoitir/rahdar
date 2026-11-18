package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Election;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentRepository;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionSelection;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaMoveRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.SelectedPartition;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Preferred election and a replica move: a preview, a hold, and an etag that is checked again before anything is sent. */
class KafkaReplicationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Operations operations = mock(Operations.class);
    private final Resources resources = mock(Resources.class);
    private final KafkaReassignmentRepository reassignments = mock(KafkaReassignmentRepository.class);
    private final OperationGate gate = mock(OperationGate.class);
    private final AuditService audit = mock(AuditService.class);
    private final AuditEvent event = mock(AuditEvent.class);
    private final KafkaReplication replication;

    private int minInSync = 1;
    private String existingRate;

    KafkaReplicationTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.operations()).thenReturn(operations);
        when(connection.resources()).thenReturn(resources);
        ClusterAccessGuard access = mock(ClusterAccessGuard.class);
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolve()).thenReturn(mock(Actor.class));
        when(event.getId()).thenReturn(7L);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(event);
        when(reassignments.findByClusterIdAndTopicAndPartition(any(), any(), anyInt()))
                .thenReturn(Optional.empty());
        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get())
                .when(gate)
                .run(any(), any());
        replication = new KafkaReplication(
                gateway,
                new KafkaPartitionReads(gateway, access),
                reassignments,
                access,
                gate,
                audit,
                actors,
                Clock.systemUTC());
    }

    @BeforeEach
    void brokerAnswers() {
        when(resources.read(any(ResourceQuery.class))).thenAnswer(invocation -> {
            ResourceQuery query = invocation.getArgument(0);
            KindId kind = query.kind().orElseThrow();
            if (KafkaKinds.TOPIC.equals(kind)) {
                return page(config("kafka.topic", "orders", KafkaPlacement.MIN_IN_SYNC, Integer.toString(minInSync)));
            }
            if (KafkaKinds.BROKER_CONFIG.equals(kind)) {
                Attributes.Builder attributes = Attributes.builder();
                if (existingRate != null) {
                    attributes.putRaw(KafkaKinds.CONFIG_PREFIX + "leader.replication.throttled.rate", existingRate);
                }
                return page(new Resource(
                        ResourceId.of("kafka.broker-config", query.namePattern().orElse("1")),
                        attributes.build(),
                        List.of(),
                        List.of(),
                        Instant.EPOCH));
            }
            return page(partition(2, List.of(1, 2)));
        });
        when(operations.execute(any(), any(), any(), any()))
                .thenReturn(Partial.complete(OperationResult.done(), List.of()));
    }

    @Test
    void anEmptyElectionIsRefusedBeforeAnyDriverCall() {
        PartitionSelection empty = new PartitionSelection(List.of(), null);

        assertThatThrownBy(() -> replication.previewElection(CLUSTER, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> replication.elect(CLUSTER, empty)).isInstanceOf(IllegalArgumentException.class);
        verify(operations, never()).preview(any(), any(), any());
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void electingThePreferredLeaderAuditsTheLeadersAndDoesNotReorderReplicas() {
        when(operations.preview(any(), any(), any())).thenReturn(electionPreview("etag-e", false));
        PartitionSelection selection = new PartitionSelection(List.of(new SelectedPartition("orders", 0)), null);

        ElectionPreview preview = replication.previewElection(CLUSTER, selection);
        ElectionOutcome outcome =
                replication.elect(CLUSTER, new PartitionSelection(selection.partitions(), preview.etag()));

        assertThat(outcome.elected()).isEqualTo(1);
        assertThat(outcome.alreadyPreferred()).isZero();
        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(operations).execute(any(), sent.capture(), eq(Scope.whole()), eq(Precondition.ifMatch("etag-e")));
        assertThat(sent.getValue()).isEqualTo(new Operation.ElectLeader(Election.PREFERRED));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("ELECT_PREFERRED_LEADERS"),
                        eq("DESTINATION"),
                        eq("orders"),
                        eq(CLUSTER),
                        any(),
                        before.capture(),
                        eq(false));
        assertThat(before.getValue()).containsEntry("leaders", Map.of("orders-0", "2"));
        verify(audit)
                .finish(
                        eq(event),
                        eq(false),
                        eq(1L),
                        any(),
                        eq(Map.of("leaders", Map.of("orders-0", "1"), "scope", "whole cluster")));
    }

    @Test
    void aMissingOrNonPositiveThrottleIsRefusedAndCannotBeConfirmed() {
        ReplicaMoveRequest missing = new ReplicaMoveRequest(List.of(1, 2), null, null);
        ReplicaMoveRequest zero = new ReplicaMoveRequest(List.of(1, 2), 0L, null);
        ReplicaMoveRequest negative = new ReplicaMoveRequest(List.of(1, 2), -5L, null);

        assertThatThrownBy(() -> replication.previewReassign(CLUSTER, "orders", 0, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be confirmed");
        assertThatThrownBy(() -> replication.previewReassign(CLUSTER, "orders", 0, zero))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replication.reassign(CLUSTER, "orders", 0, negative))
                .isInstanceOf(IllegalArgumentException.class);
        verify(operations, never()).preview(any(), any(), any());
        verify(operations, never()).execute(any(), any(), any(), any());
        verify(resources, never()).read(any(ResourceQuery.class));
    }

    @Test
    void aFactorBelowMinInSyncReplicasNamesBothNumbersAndAltersNothing() {
        minInSync = 2;

        assertThatThrownBy(() -> replication.reassign(
                        CLUSTER, "orders", 0, new ReplicaMoveRequest(List.of(1), 1_000L, "etag-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Replication factor 1")
                .hasMessageContaining("min.insync.replicas 2");
        verify(operations, never()).preview(any(), any(), any());
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void anUnknownSizeIsNotReportedAsZeroBytes() {
        when(operations.preview(any(), any(), any()))
                .thenReturn(reassignPreview(
                        "etag-1",
                        Count.unavailable("The leader's replica size is not known."),
                        "The duration is unavailable: The leader's replica size is not known."));

        ReassignPreview preview = replication.previewReassign(
                CLUSTER, "orders", 0, new ReplicaMoveRequest(List.of(1, 2, 3), 1_000L, null));

        assertThat(preview.currentReplicas()).containsExactly(1, 2);
        assertThat(preview.replicas()).containsExactly(1, 2, 3);
        assertThat(preview.bytes()).isNull();
        assertThat(preview.bytesUnavailable()).contains("not known");
        assertThat(preview.duration()).isNull();
        assertThat(preview.durationUnavailable()).contains("not known");
        assertThat(preview.throttleOutcome()).isEqualTo(KafkaPlacement.THROTTLE_REMOVED);
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void anExistingThrottleIsDescribedAsPutBack() {
        existingRate = "4000";
        when(operations.preview(any(), any(), any()))
                .thenReturn(reassignPreview(
                        "etag-1", Count.estimate(0), "About 0s to copy 0 bytes at 1000 bytes/s (estimate)."));

        ReassignPreview preview =
                replication.previewReassign(CLUSTER, "orders", 0, new ReplicaMoveRequest(List.of(1, 2), 1_000L, null));

        assertThat(preview.throttleOutcome()).isEqualTo(KafkaPlacement.THROTTLE_RESTORED);
        assertThat(preview.bytes()).isZero();
    }

    @Test
    void aHeldMoveSendsNothingAndAStaleEtagAfterApprovalSendsNothing() {
        when(operations.preview(any(), any(), any()))
                .thenReturn(reassignPreview(
                        "etag-1", Count.estimate(10), "About 1s to copy 10 bytes at 1000 bytes/s (estimate)."));
        doReturn(null).when(gate).run(any(), any());
        ReplicaMoveRequest request = new ReplicaMoveRequest(List.of(1, 2, 3), 1_000L, "etag-1");

        assertThat(replication.reassign(CLUSTER, "orders", 0, request)).isNull();
        verify(operations, never()).execute(any(), any(), any(), any());

        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get())
                .when(gate)
                .run(any(), any());
        when(operations.preview(any(), any(), any()))
                .thenReturn(reassignPreview("etag-1", Count.estimate(10), "About 1s"))
                .thenReturn(reassignPreview("etag-2", Count.estimate(10), "About 1s"));

        assertThatThrownBy(() -> replication.reassign(CLUSTER, "orders", 0, request))
                .isInstanceOf(KafkaStaleException.class);
        verify(operations, never()).execute(any(), any(), any(), any());
        verify(reassignments, never()).save(any());
    }

    @Test
    void aMoveStoresTheThrottleBaselineInTheSameStepAsTheAudit() {
        when(operations.preview(any(), any(), any()))
                .thenReturn(reassignPreview(
                        "etag-1", Count.estimate(80), "About 1s to copy 80 bytes at 1000 bytes/s (estimate)."));
        when(operations.execute(any(), any(), any(), any())).thenReturn(Partial.complete(applied(), List.of()));

        replication.reassign(CLUSTER, "orders", 0, new ReplicaMoveRequest(List.of(3, 1), 1_000L, "etag-1"));

        ArgumentCaptor<KafkaReassignmentEntity> saved = ArgumentCaptor.forClass(KafkaReassignmentEntity.class);
        InOrder order = inOrder(reassignments, audit);
        order.verify(reassignments).save(saved.capture());
        order.verify(audit).finish(eq(event), eq(false), eq(1L), any(), any());
        assertThat(saved.getValue().getPreviousThrottle())
                .containsEntry("broker.1.leader.replication.throttled.rate", "5000");
        assertThat(saved.getValue().getAddedKeys()).isEqualTo("broker.1.follower.replication.throttled.rate");
        assertThat(saved.getValue().getOperationId()).isEqualTo(7L);
        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(operations).execute(any(), sent.capture(), eq(Scope.whole()), eq(Precondition.ifMatch("etag-1")));
        Operation.Reassign reassign = (Operation.Reassign) sent.getValue();
        assertThat(reassign.assignments().get(PartitionId.of("0")))
                .extracting(NodeId::value)
                .containsExactly("3", "1");
        assertThat(reassign.options().raw(KafkaPlacement.THROTTLE_OPTION)).contains(1_000L);
    }

    private static Partial<ResourcePage> page(Resource resource) {
        return Partial.complete(new ResourcePage(List.of(resource), Optional.empty()), List.of());
    }

    private static Resource partition(int leader, List<Integer> replicas) {
        return new Resource(
                ResourceId.of("kafka.partition", "orders", "0"),
                Attributes.builder()
                        .putRaw(KafkaKinds.LEADER, (long) leader)
                        .putRaw(KafkaKinds.REPLICAS, replicas)
                        .putRaw(KafkaKinds.IN_SYNC_REPLICAS, replicas)
                        .build(),
                List.of(),
                List.of(),
                Instant.EPOCH);
    }

    private static Resource config(String kind, String name, String setting, String value) {
        return new Resource(
                ResourceId.of(kind, name),
                Attributes.builder()
                        .putRaw(KafkaKinds.CONFIG_PREFIX + setting, value)
                        .build(),
                List.of(),
                List.of(),
                Instant.EPOCH);
    }

    private static Preview electionPreview(String etag, boolean already) {
        List<AttributeChange> diff = new ArrayList<>();
        if (!already) {
            diff.add(new AttributeChange(KafkaKinds.LEADER, Optional.of(2L), Optional.of(1L)));
        }
        diff.add(new AttributeChange(
                KafkaKinds.NOTE,
                Optional.empty(),
                Optional.of(
                        already
                                ? "Already the preferred leader; nothing would be sent."
                                : "The leader would move from 2 to 1, the first replica.")));
        return new Preview(
                diff,
                Set.of(),
                new Estimate(Count.exact(already ? 0 : 1), "{leader}"),
                etag,
                EtagBasis.CONFIGURATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    private static Preview reassignPreview(String etag, Count bytes, String note) {
        return new Preview(
                List.of(new AttributeChange(KafkaKinds.NOTE, Optional.empty(), Optional.of(note))),
                Set.of(),
                new Estimate(bytes, "By"),
                etag,
                EtagBasis.CONFIGURATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    private static OperationResult applied() {
        return new OperationResult(
                OptionalLong.empty(),
                Optional.of(new Resource(
                        ResourceId.of("kafka.partition", "orders", "0"),
                        Attributes.builder()
                                .putRaw(
                                        KafkaKinds.THROTTLE_PREVIOUS,
                                        Map.of("broker.1.leader.replication.throttled.rate", "5000"))
                                .putRaw(
                                        KafkaKinds.THROTTLE_ADDED,
                                        List.of("broker.1.follower.replication.throttled.rate"))
                                .build(),
                        List.of(),
                        List.of(),
                        Instant.EPOCH)),
                false);
    }
}
