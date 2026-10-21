package io.github.sudoitir.rahdar.feature.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageDeleteParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageMoveParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.QueuePurgeParams;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Outcome;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Reach;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.kernel.gate.CanonicalJson;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The gated message operations: what an approver reads, what is estimated and pinned, and what a replay runs. */
class MessageGatedOperationsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID ENVIRONMENT = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final MessageService messages = mock(MessageService.class);
    private final ClusterEnvironmentIndex clusters = mock(ClusterEnvironmentIndex.class);
    private final MessageGatedOperations operations = new MessageGatedOperations();

    @BeforeEach
    void clusterIsKnown() {
        when(clusters.labelOf(CLUSTER)).thenReturn("prod-eu");
        when(clusters.environmentOf(CLUSTER)).thenReturn(ENVIRONMENT);
    }

    @Test
    void aPurgeIsDestructiveAndScopedToItsClusterAndEnvironment() {
        GatedOperation<QueuePurgeParams> purge = operations.queuePurgeOperation(messages, clusters);
        QueuePurgeParams params = new QueuePurgeParams(CLUSTER, "orders.dlq", null, false);

        assertThat(purge.type()).isEqualTo("subscription.purge");
        assertThat(purge.traits(params)).containsExactly(Trait.DESTRUCTIVE);
        assertThat(purge.mode()).isEqualTo(ExecutionMode.ON_APPROVAL);
        assertThat(purge.scope(params)).isEqualTo(OperationScope.cluster(CLUSTER, ENVIRONMENT));
        assertThat(purge.summary(params)).isEqualTo("Purge queue orders.dlq on prod-eu");
        assertThat(purge.display(params)).extracting("label").containsExactly("Cluster", "Queue", "Removes");
    }

    @Test
    void aPurgeEstimatesTheQueueDepthAndPinsTheQueuesIdentity() {
        when(messages.reachOfPurge(CLUSTER, "orders", NODE)).thenReturn(new Reach(12, "orders.addr|ANYCAST"));

        Effect effect = operations
                .queuePurgeOperation(messages, clusters)
                .estimate(new QueuePurgeParams(CLUSTER, "orders", NODE, false));

        assertThat(effect).isEqualTo(new Effect(12, "messages", "orders.addr|ANYCAST", null));
    }

    @Test
    void aPurgeReplaysThroughThePublicMethodWithTheOriginalOverride() {
        when(messages.purge(CLUSTER, "orders", NODE, false, true))
                .thenReturn(new Attempt.Ok<>(new Outcome.Affected(3, NODE)));

        operations.queuePurgeOperation(messages, clusters).replay(new QueuePurgeParams(CLUSTER, "orders", NODE, true));

        verify(messages).purge(CLUSTER, "orders", NODE, false, true);
    }

    @Test
    void aReplayTheBrokerCouldNotCarryOutFailsTheRequest() {
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), eq(false)))
                .thenReturn(new Attempt.Failed<>(BrokerConnectionException.Kind.UNREACHABLE, "down"));

        var operation = operations.queuePurgeOperation(messages, clusters);
        QueuePurgeParams params = new QueuePurgeParams(CLUSTER, "orders", null, false);

        assertThatThrownBy(() -> operation.replay(params))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("down");
    }

    @Test
    void aSelectedActionSaysWhatItMovesAndWhere() {
        GatedOperation<MessageMoveParams> move = operations.messageMoveOperation(messages, clusters);
        MessageMoveParams params = new MessageMoveParams(
                CLUSTER, "orders.dlq", null, MessageRequestFixtures.of(List.of(1L, 2L), null, "orders"), false);

        assertThat(move.type()).isEqualTo("message.move");
        assertThat(move.summary(params)).isEqualTo("Move 2 messages on queue orders.dlq on prod-eu");
        assertThat(move.display(params))
                .extracting("label", "to")
                .contains(Tuple.tuple("Queue", "orders.dlq"), Tuple.tuple("Into queue", "orders"));
    }

    @Test
    void aFilteredActionEstimatesTheMatchesAndPinsTheFilterWithTheQueue() {
        MessageActionRequest request = MessageRequestFixtures.of(null, "priority > 3", null);
        when(messages.reach(CLUSTER, "orders", null, MessageAction.DELETE, request))
                .thenReturn(new Reach(40, "orders.addr|ANYCAST"));

        Effect effect = operations
                .messageDeleteOperation(messages, clusters)
                .estimate(new MessageDeleteParams(CLUSTER, "orders", null, request, false));

        assertThat(effect).isEqualTo(new Effect(40, "messages", "orders.addr|ANYCAST|priority > 3", null));
    }

    @Test
    void aSelectionByIdsPinsTheQueueAloneBecauseTheIdsAreInTheParameters() {
        MessageActionRequest request = MessageRequestFixtures.of(List.of(1L, 2L, 3L), null, null);
        when(messages.reach(CLUSTER, "orders", null, MessageAction.EXPIRE, request))
                .thenReturn(new Reach(3, "orders.addr|ANYCAST"));

        Effect effect = operations
                .messageExpireOperation(messages, clusters)
                .estimate(new MessageActionParams.MessageExpireParams(CLUSTER, "orders", null, request, false));

        assertThat(effect.etag()).isEqualTo("orders.addr|ANYCAST");
        assertThat(effect.count()).isEqualTo(3);
    }

    @Test
    void eachActionReplaysAsItself() {
        MessageActionRequest request = MessageRequestFixtures.of(List.of(5L), null, null);
        when(messages.execute(CLUSTER, "orders", null, MessageAction.RETRY, request, false, false))
                .thenReturn(new Attempt.Ok<>(new Outcome.Affected(1, NODE)));

        operations
                .messageRetryOperation(messages, clusters)
                .replay(new MessageActionParams.MessageRetryParams(CLUSTER, "orders", null, request, false));

        verify(messages).execute(CLUSTER, "orders", null, MessageAction.RETRY, request, false, false);
    }

    @Test
    void everyParameterRecordRoundTripsThroughCanonicalJson() {
        MessageActionRequest request = MessageRequestFixtures.of(List.of(1L, 2L), "a = 'b'", "orders");
        List<Record> all = List.of(
                new QueuePurgeParams(CLUSTER, "orders", null, true),
                new MessageMoveParams(CLUSTER, "orders", NODE, request, true),
                new MessageDeleteParams(CLUSTER, "orders", null, request, false),
                new MessageActionParams.MessageRetryParams(CLUSTER, "orders", null, request, false),
                new MessageActionParams.MessageExpireParams(CLUSTER, "orders", null, request, false));
        JsonMapper mapper = JsonMapper.builder().build();

        for (Record params : all) {
            assertThat(mapper.readValue(CanonicalJson.write(params), params.getClass()))
                    .isEqualTo(params);
        }
    }

    @Test
    void noMessageOperationRedactsAnythingBecauseNoneCarriesASecret() {
        assertThat(operations.queuePurgeOperation(messages, clusters).redactedPaths())
                .isEqualTo(Set.of());
    }
}
