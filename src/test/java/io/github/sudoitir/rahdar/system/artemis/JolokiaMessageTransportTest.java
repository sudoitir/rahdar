package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.SendSpec;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.TransportTarget;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JolokiaMessageTransportTest {

    private final ArtemisNodes artemisNodes = mock(ArtemisNodes.class);

    @Test
    void headersAreSentUnderTheBrokersOwnNamesAsStringsAndTheBrokerAccountSends() {
        BrokerConnections connections = mock(BrokerConnections.class);
        ArtemisNode client = mock(ArtemisNode.class);
        MessageOperations ops = mock(MessageOperations.class);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(client);
        when(connections.settingsFor(any())).thenReturn(BrokerConnectionSettings.basicAuth(null, "bob", "pw"));
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");

        new JolokiaMessageTransport(connections, mock(MessageBrowser.class), ops, artemisNodes)
                .send(
                        new TransportTarget(UUID.randomUUID(), UUID.randomUUID(), "q", "q", "ANYCAST", "u", null),
                        new SendSpec(
                                MessageKind.TEXT,
                                true,
                                "hi",
                                Map.of("correlationId", "c-1", "type", "order", "groupId", "g"),
                                Map.of("orderId", "A-1", "n", 7, "ok", true)));

        ArgumentCaptor<MessageOperations.Outgoing> sent = ArgumentCaptor.forClass(MessageOperations.Outgoing.class);
        verify(ops).send(eq(client), anyString(), sent.capture(), eq(new MessageOperations.Sender("bob", "pw")));
        assertThat(sent.getValue().body()).isEqualTo("hi");
        assertThat(sent.getValue().headers())
                .containsOnly(
                        Map.entry("JMSCorrelationID", "c-1"),
                        Map.entry("JMSType", "order"),
                        Map.entry("_AMQ_GROUP_ID", "g"),
                        Map.entry("orderId", "A-1"),
                        Map.entry("n", "7"),
                        Map.entry("ok", "true"));
    }

    @Test
    void theKindMapsToTheArtemisMessageType() {
        BrokerConnections connections = mock(BrokerConnections.class);
        ArtemisNode client = mock(ArtemisNode.class);
        MessageOperations ops = mock(MessageOperations.class);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(client);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        JolokiaMessageTransport transport =
                new JolokiaMessageTransport(connections, mock(MessageBrowser.class), ops, artemisNodes);
        TransportTarget target =
                new TransportTarget(UUID.randomUUID(), UUID.randomUUID(), "q", "q", "ANYCAST", "u", null);

        transport.send(target, new SendSpec(MessageKind.TEXT, true, "hi", Map.of(), Map.of()));
        transport.send(target, new SendSpec(MessageKind.BYTES, true, "aGk=", Map.of(), Map.of()));

        ArgumentCaptor<MessageOperations.Outgoing> sent = ArgumentCaptor.forClass(MessageOperations.Outgoing.class);
        verify(ops, times(2)).send(eq(client), anyString(), sent.capture(), any());
        assertThat(sent.getAllValues())
                .extracting(MessageOperations.Outgoing::type)
                .containsExactly(3, 4);
    }

    @Test
    void anApplicationPropertyNamedLikeAHeaderKeepsItsName() {
        BrokerConnections connections = mock(BrokerConnections.class);
        ArtemisNode client = mock(ArtemisNode.class);
        MessageOperations ops = mock(MessageOperations.class);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(client);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");

        new JolokiaMessageTransport(connections, mock(MessageBrowser.class), ops, artemisNodes)
                .send(
                        new TransportTarget(UUID.randomUUID(), UUID.randomUUID(), "q", "q", "ANYCAST", "u", null),
                        new SendSpec(
                                MessageKind.TEXT,
                                true,
                                "hi",
                                Map.of("type", "header-type"),
                                Map.of("type", "property-type", "groupId", "not-a-group", "JMSXGroupID", "g")));

        ArgumentCaptor<MessageOperations.Outgoing> sent = ArgumentCaptor.forClass(MessageOperations.Outgoing.class);
        verify(ops).send(eq(client), anyString(), sent.capture(), eq(new MessageOperations.Sender("", "")));
        assertThat(sent.getValue().headers())
                .containsOnly(
                        Map.entry("JMSType", "header-type"),
                        Map.entry("type", "property-type"),
                        Map.entry("groupId", "not-a-group"),
                        Map.entry("_AMQ_GROUP_ID", "g"));
    }
}
