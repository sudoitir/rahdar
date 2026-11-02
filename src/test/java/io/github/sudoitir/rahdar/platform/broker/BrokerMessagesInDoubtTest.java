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
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.Messages;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.SendReceipt;
import io.github.sudoitir.openbroker.messaging.SendTarget;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.support.Targets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A send the broker never confirmed is in doubt, never a failure an operator would retry (ADR-0211). */
class BrokerMessagesInDoubtTest {

    private static final ResourceId ORDERS = ResourceId.of("rabbitmq.exchange", "orders");

    private final UUID clusterId = UUID.randomUUID();
    private final ClusterConnection connection = Targets.target().connection();
    private final Messages sdk = mock(Messages.class);
    private final BrokerMessages messages = messages();

    private BrokerMessages messages() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(clusterId)).thenReturn(connection);
        when(connection.messages()).thenReturn(sdk);
        return new BrokerMessages(gateway);
    }

    private void sendFails(Dispatch dispatch) {
        when(sdk.send(any(SendTarget.class), any(Scope.class), any(OutboundMessage.class)))
                .thenThrow(new BrokerException(
                        Status.UNAVAILABLE, ErrorInfo.of(ErrorReasons.UNREACHABLE), dispatch, null, "link lost"));
    }

    @Test
    void anUnconfirmedSendIsInDoubt() {
        sendFails(Dispatch.IN_DOUBT);

        assertThatThrownBy(this::send)
                .isInstanceOf(SendInDoubtException.class)
                .hasMessageContaining("may or may not be on the broker");
    }

    @Test
    void aSendThatNeverLeftIsAConnectionFailure() {
        sendFails(Dispatch.NOT_SENT);

        assertThatThrownBy(this::send).isInstanceOf(BrokerConnectionException.class);
    }

    @Test
    void anUnroutedSendToAnExchangeNamesTheExchangeAndTheKeyTheCallerGave() {
        when(sdk.send(any(SendTarget.class), any(Scope.class), any(OutboundMessage.class)))
                .thenThrow(new BrokerException(
                        Status.FAILED_PRECONDITION,
                        ErrorInfo.of(ErrorReasons.NOT_ROUTED),
                        Dispatch.REJECTED,
                        null,
                        "returned"));

        assertThatThrownBy(() -> sendById(ResourceClass.DESTINATION, "eu"))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("not-routed"))
                .hasMessageContaining("exchange 'orders'")
                .hasMessageContaining("routing key 'eu'");
    }

    @Test
    void aSendByIdGoesToTheWholeClusterWithTheRoutingKey() {
        when(sdk.send(any(SendTarget.class), any(Scope.class), any(OutboundMessage.class)))
                .thenReturn(new SendReceipt(Optional.of("m1"), Optional.empty(), Optional.empty(), false));

        assertThat(sendById(ResourceClass.DESTINATION, "eu")).contains("m1");

        ArgumentCaptor<SendTarget> target = ArgumentCaptor.forClass(SendTarget.class);
        verify(sdk).send(target.capture(), eq(Scope.whole()), any(OutboundMessage.class));
        assertThat(target.getValue())
                .isEqualTo(new SendTarget.ToDestination(ORDERS, Optional.of("eu"), Optional.empty()));
    }

    @Test
    void aSendByIdToAQueueGoesStraightToIt() {
        when(sdk.send(any(SendTarget.class), any(Scope.class), any(OutboundMessage.class)))
                .thenReturn(new SendReceipt(Optional.empty(), Optional.empty(), Optional.empty(), false));

        sendById(ResourceClass.SUBSCRIPTION, null);

        ArgumentCaptor<SendTarget> target = ArgumentCaptor.forClass(SendTarget.class);
        verify(sdk).send(target.capture(), eq(Scope.whole()), any(OutboundMessage.class));
        assertThat(target.getValue()).isEqualTo(SendTarget.subscription(ORDERS));
    }

    @Test
    void anUnconfirmedSendByIdIsInDoubt() {
        sendFails(Dispatch.IN_DOUBT);

        assertThatThrownBy(() -> sendById(ResourceClass.SUBSCRIPTION, null)).isInstanceOf(SendInDoubtException.class);
    }

    private Optional<String> sendById(ResourceClass resourceClass, String routingKey) {
        return messages.sendById(
                clusterId,
                ORDERS,
                resourceClass,
                routingKey,
                SentMessages.of(MessageKind.TEXT, true, "x", Map.of(), Map.of()));
    }

    private void send() {
        messages.sendTo(
                clusterId,
                UUID.randomUUID(),
                ResourceRef.destination("orders"),
                SentMessages.of(MessageKind.TEXT, true, "x", Map.of(), Map.of()));
    }
}
