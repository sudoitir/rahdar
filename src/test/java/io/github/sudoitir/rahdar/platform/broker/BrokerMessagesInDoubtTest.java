package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.Messages;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.SendTarget;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.support.Targets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A send the broker never confirmed is in doubt, never a failure an operator would retry (ADR-0211). */
class BrokerMessagesInDoubtTest {

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

        assertThatThrownBy(() -> send())
                .isInstanceOf(SendInDoubtException.class)
                .hasMessageContaining("may or may not be on the broker");
    }

    @Test
    void aSendThatNeverLeftIsAConnectionFailure() {
        sendFails(Dispatch.NOT_SENT);

        assertThatThrownBy(() -> send()).isInstanceOf(BrokerConnectionException.class);
    }

    private void send() {
        messages.sendTo(
                clusterId,
                UUID.randomUUID(),
                ResourceRef.destination("orders"),
                SentMessages.of(MessageKind.TEXT, true, "x", Map.of(), Map.of()));
    }
}
