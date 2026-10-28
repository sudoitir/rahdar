package io.github.sudoitir.rahdar.feature.messages.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.messages.MessageAction;
import io.github.sudoitir.rahdar.feature.messages.MessageRequestFixtures;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Outcome;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.mcp.McpResourcesFixture;
import io.github.sudoitir.rahdar.platform.mcp.McpViews.MutationOutcome;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The model-facing gate on {@code message_action} and {@code send_message}, over a stubbed service. */
class MessagesMcpActionToolsTest {

    private final MessageService messages = mock(MessageService.class);
    private final ClusterKinds kinds = mock(ClusterKinds.class);
    private final MessagesMcpActionTools tools =
            new MessagesMcpActionTools(messages, McpResourcesFixture.resolving(), kinds);
    private final UUID cluster = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();

    @BeforeEach
    void namesBecomeIds() {
        when(messages.actionRequest(any(), any(), any(), any()))
                .thenAnswer(call ->
                        MessageRequestFixtures.of(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
    }

    private static MutationOutcome structured(CallToolResult result) {
        return (MutationOutcome) result.structuredContent();
    }

    private CallToolResult action(String action, String ids, Boolean dryRun, String confirm, Boolean override) {
        return tools.messageAction(
                cluster.toString(),
                "artemis.queue/orders",
                action,
                ids,
                "color='red'",
                "artemis.queue/dlq",
                dryRun,
                confirm,
                override);
    }

    @Test
    void aDryRunIsTheDefaultAndNeedsNoConfirmation() {
        when(messages.execute(eq(cluster), eq("orders"), isNull(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.DryRun(4, 100, false, node)));

        CallToolResult result = action("move", "1, 2,,3", null, null, null);

        assertThat(result.isError()).isNotEqualTo(true);
        MutationOutcome outcome = structured(result);
        assertThat(outcome.action()).isEqualTo("move");
        assertThat(outcome.subject()).isEqualTo("orders");
        assertThat(outcome.dryRun()).isTrue();
        assertThat(outcome.affected()).isEqualTo(4);
        assertThat(outcome.cap()).isEqualTo(100L);
        assertThat(outcome.overCap()).isFalse();
        assertThat(outcome.node()).isEqualTo(node.toString());
        assertThat(outcome.message()).contains("dryRun=false").contains("confirm=\"orders\"");

        ArgumentCaptor<MessageActionRequest> request = ArgumentCaptor.forClass(MessageActionRequest.class);
        verify(messages)
                .execute(
                        eq(cluster),
                        eq("orders"),
                        isNull(),
                        eq(MessageAction.MOVE),
                        request.capture(),
                        eq(true),
                        eq(false));
        assertThat(request.getValue().ids()).containsExactly(1L, 2L, 3L);
        assertThat(request.getValue().filter()).isEqualTo("color='red'");
        assertThat(request.getValue().targetQueue()).isEqualTo("dlq");
    }

    @Test
    void aDryRunOverTheBulkCapSaysOverrideIsNeeded() {
        when(messages.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.DryRun(5000, 100, true, node)));

        MutationOutcome outcome = structured(action("delete", null, true, null, null));

        assertThat(outcome.overCap()).isTrue();
        assertThat(outcome.message()).contains("override=true");
        assertThat(outcome.message()).doesNotContain("confirm=");
    }

    @Test
    void aRealRunWithoutTheExactSubscriptionNameIsRejectedBeforeTouchingTheService() {
        assertThatThrownBy(() -> action("delete", "1", false, null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("confirm to be exactly \"orders\"");
        assertThatThrownBy(() -> action("delete", "1", false, "yes", null)).isInstanceOf(McpError.class);

        verifyNoInteractions(messages);
    }

    @Test
    void aConfirmedRealRunAppliesAndCarriesTheOverrideFlag() {
        when(messages.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.Affected(3, node)));

        CallToolResult result = action("expire", "7", false, "orders", true);

        MutationOutcome outcome = structured(result);
        assertThat(outcome.action()).isEqualTo("expire");
        assertThat(outcome.dryRun()).isFalse();
        assertThat(outcome.affected()).isEqualTo(3);
        assertThat(outcome.cap()).isNull();
        assertThat(outcome.message()).isEqualTo("Applied.");
        verify(messages)
                .execute(eq(cluster), eq("orders"), isNull(), eq(MessageAction.EXPIRE), any(), eq(false), eq(true));
    }

    @Test
    void purgeGoesThroughItsOwnServiceMethod() {
        when(messages.purge(cluster, "orders", null, false, false))
                .thenReturn(new Attempt.Ok<>(new Outcome.Affected(9, node)));

        MutationOutcome outcome = structured(action("PURGE", null, false, "orders", null));

        assertThat(outcome.action()).isEqualTo("purge");
        assertThat(outcome.affected()).isEqualTo(9);
        verify(messages, never()).execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void aPartialRunNamesWhatWasNotDone() {
        when(messages.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.Partial(2, List.of(30L, 40L), "broker hiccup", node)));

        MutationOutcome outcome = structured(action("retry", "10,20,30,40", false, "orders", null));

        assertThat(outcome.message())
                .contains("2 message(s)")
                .contains("broker hiccup")
                .contains("2 id(s) were not done")
                .contains("starting with 30");
    }

    @Test
    void aBrokerThatCouldNotBeReachedIsAFailedOperationNotAProtocolError() {
        when(messages.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new Attempt.Failed<>(BrokerConnectionException.Kind.UNREACHABLE, "down"));

        CallToolResult result = action("move", "1", true, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).contains("The broker could not be reached");
    }

    @Test
    void malformedArgumentsAreProtocolErrors() {
        String clusterId = cluster.toString();

        assertThatThrownBy(() -> tools.messageAction(
                        "not-a-uuid", "artemis.queue/orders", "move", null, null, null, true, null, null))
                .isInstanceOf(McpError.class);
        assertThatThrownBy(() -> tools.messageAction(clusterId, " ", "move", null, null, null, true, null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("subscription is required");
        assertThatThrownBy(() -> action("explode", null, true, null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("action must be one of");
        assertThatThrownBy(() -> action(null, null, true, null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("action is required");
        assertThatThrownBy(() -> action("move", "1,abc", true, null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("positions must be comma-separated numbers; got \"abc\"");
    }

    @Test
    void blankMessageIdsSelectNothingByIds() {
        when(messages.execute(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.DryRun(0, 100, false, node)));

        action("delete", "  ", true, null, null);

        ArgumentCaptor<MessageActionRequest> request = ArgumentCaptor.forClass(MessageActionRequest.class);
        verify(messages).execute(any(), any(), any(), any(), request.capture(), anyBoolean(), anyBoolean());
        assertThat(request.getValue().ids()).isEmpty();
    }

    @Test
    void sendMessageDryRunsByDefaultAndDefaultsToATextDurableMessage() {
        when(messages.send(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.DryRun(1, 100, false, node)));

        MutationOutcome outcome =
                structured(tools.sendMessage(cluster.toString(), "artemis.queue/orders", "hello", null, null, null));

        assertThat(outcome.action()).isEqualTo("send");
        assertThat(outcome.dryRun()).isTrue();
        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(messages).send(eq(cluster), eq("orders"), isNull(), request.capture(), eq(true));
        assertThat(request.getValue().kind()).isEqualTo(MessageKind.TEXT);
        assertThat(request.getValue().durable()).isTrue();
        assertThat(request.getValue().body()).isEqualTo("hello");
    }

    @Test
    void sendMessageHonoursExplicitTypeDurabilityAndRealRun() {
        when(messages.send(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Attempt.Ok<>(new Outcome.Affected(1, node)));

        MutationOutcome outcome =
                structured(tools.sendMessage(cluster.toString(), "artemis.queue/orders", "x", "bytes", false, false));

        assertThat(outcome.dryRun()).isFalse();
        assertThat(outcome.message()).isEqualTo("Applied.");
        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(messages).send(any(), any(), any(), request.capture(), eq(false));
        assertThat(request.getValue().kind()).isEqualTo(MessageKind.BYTES);
        assertThat(request.getValue().durable()).isFalse();
    }

    @Test
    void sendMessageRefusesAnUnknownKind() {
        String clusterId = cluster.toString();

        assertThatThrownBy(() -> tools.sendMessage(clusterId, "artemis.queue/orders", "x", "3", null, null))
                .isInstanceOf(McpError.class);
        verifyNoInteractions(messages);
    }

    @Test
    void sendMessageRequiresABodyAndAQueue() {
        String clusterId = cluster.toString();

        assertThatThrownBy(() -> tools.sendMessage(clusterId, "artemis.queue/orders", " ", null, null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("body is required");
        assertThatThrownBy(() -> tools.sendMessage(clusterId, null, "x", null, null, null))
                .isInstanceOf(McpError.class);
        verifyNoInteractions(messages);
    }

    @Test
    void aPurgeOfASubscriptionOfALogGoesThroughTheLogPurgeAndNotTheQueuePurge() {
        when(kinds.partitioned(cluster, "kafka.group-topic")).thenReturn(true);
        ResourceId subscription = ResourceId.parse("kafka.group-topic/billing/orders");
        when(messages.purgeLog(cluster, subscription, true))
                .thenReturn(new Attempt.Ok<>(new Outcome.DryRun(12, 100, false, null)));

        CallToolResult result = tools.messageAction(
                cluster.toString(), "kafka.group-topic/billing/orders", "purge", null, null, null, null, null, null);

        assertThat(result.isError()).isNotEqualTo(true);
        verify(messages).purgeLog(cluster, subscription, true);
        verify(messages, never()).purge(any(), any(), any(), anyBoolean(), anyBoolean());
    }
}
