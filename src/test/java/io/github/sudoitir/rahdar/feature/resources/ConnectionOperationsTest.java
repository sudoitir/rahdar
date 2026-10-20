package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.feature.resources.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pre-close read, against the row shapes a real broker returns
 * (`src/test/resources/jolokia/list-*.json`).
 *
 * <p>The one that matters is the confirmation token. ADR-0057 D3 says an operator
 * confirms against something they can recognise, and a session row carries no
 * remote address and — for a CORE client, which is the ordinary case in this
 * project's own captured fixture — a blank {@code clientID}. If the snapshot does
 * not reach for the connection's identity, the token silently degrades to the
 * opaque connection id, which is exactly what the ADR forbids.
 */
class ConnectionOperationsTest {

    private final ObjectMapper mapper = new JsonMapper();
    private final ConnectionOperations ops = new ConnectionOperations(mapper);

    /** One {@code listX} envelope, in the double-encoded shape Artemis actually answers with. */
    private JolokiaResponse page(String rowsJson) {
        String envelope = "{\"data\":" + rowsJson + ",\"count\":1}";
        return new JolokiaResponse(200, mapper.valueToTree(envelope), null, null, null);
    }

    private static final String SESSION_ROW = """
            [{"id":"sess-1","user":"artemis","validatedUser":"artemis","creationTime":"Thu Sep 03 13:10:38 UTC 2026",\
            "consumerCount":2,"producerCount":0,"connectionID":"7762766a","clientID":""}]""";

    private static final String CONNECTION_ROW = """
            [{"connectionID":"7762766a","remoteAddress":"172.21.0.5:53156","users":"artemis",\
            "creationTime":"Thu Sep 03 13:09:06 UTC 2026","implementation":"RemotingConnectionImpl",\
            "protocol":"CORE","clientID":"","localAddress":"tcp:///172.21.0.3:61616","sessionCount":2}]""";

    private static final String CONSUMER_ROW = """
            [{"id":"1849","session":"sess-1","clientID":"","user":"artemis","protocol":"CORE",\
            "queue":"SPIKE.A.q000","address":"SPIKE.A","remoteAddress":"127.0.0.1:35926",\
            "messagesInTransit":"7","messagesDelivered":"0","status":"OK"}]""";

    /** A client whose single/batch calls answer in the order the operation asks. */
    private ArtemisNode clientAnswering(String... pages) {
        ArtemisNode client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        List<JolokiaResponse> queue =
                new ArrayList<>(List.of(pages).stream().map(this::page).toList());
        when(client.single(any())).thenAnswer(i -> queue.remove(0));
        when(client.batch(anyList())).thenAnswer(i -> {
            List<?> requests = i.getArgument(0);
            List<JolokiaResponse> out = new ArrayList<>();
            for (int n = 0; n < requests.size(); n++) {
                out.add(queue.remove(0));
            }
            return out;
        });
        when(client.parsed(any())).thenAnswer(i -> {
            JolokiaResponse entry = i.getArgument(0);
            return entry.valueParsed(mapper);
        });
        return client;
    }

    @Test
    void aSessionWithNoClientIdIsIdentifiedByItsConnectionsRemoteAddress() {
        // listSessions(id) → listConnections(connectionID) → listConsumers(session).
        ArtemisNode client = clientAnswering(SESSION_ROW, CONNECTION_ROW, CONSUMER_ROW);

        ConnectionSnapshot snapshot = ops.readSession(client, "sess-1");

        assertThat(snapshot.remoteAddress()).isEqualTo("172.21.0.5:53156");
        // The token a human types — never the opaque id.
        assertThat(snapshot.label()).isEqualTo("172.21.0.5:53156").isNotEqualTo("7762766a");
        assertThat(snapshot.user()).isEqualTo("artemis");
        assertThat(snapshot.consumerCount()).isEqualTo(2);
        assertThat(snapshot.messagesInTransit()).isEqualTo(7L);
    }

    @Test
    void aConnectionSnapshotCarriesTheIdentityAndTheInFlightCount() {
        // listConnections + listSessions batched, then listConsumers per session.
        ArtemisNode client = clientAnswering(CONNECTION_ROW, SESSION_ROW, CONSUMER_ROW);

        ConnectionSnapshot snapshot = ops.readConnection(client, "7762766a");

        assertThat(snapshot.remoteAddress()).isEqualTo("172.21.0.5:53156");
        assertThat(snapshot.sessionCount()).isEqualTo(1);
        assertThat(snapshot.consumerCount()).isEqualTo(2);
        assertThat(snapshot.messagesInTransit()).isEqualTo(7L);
    }

    /** The broker answers {@code false} rather than raising, and that is the already-gone verdict. */
    @Test
    void aCloseTheBrokerAnsweredFalseToIsReportedAsNotClosed() {
        ArtemisNode client = mock(ArtemisNode.class);
        when(client.single(any())).thenReturn(new JolokiaResponse(200, mapper.valueToTree(false), null, null, null));

        assertThat(ops.closeConnection(client, "mbean", "gone")).isFalse();
    }

    @Test
    void anEmptyPageMeansTheTargetIsGone() {
        ArtemisNode client = clientAnswering("[]");

        assertThat(ops.readSession(client, "sess-1")).isNull();
    }

    /** Reads are filtered broker-side, so one row comes back rather than a full dump. */
    @Test
    void readsAskTheBrokerToFilterRatherThanFetchingEveryRow() {
        ArtemisNode client = clientAnswering(SESSION_ROW);
        ops.connectionIdOfSession(client, "sess-1");

        ArgumentCaptor<JolokiaRequest> captured = ArgumentCaptor.forClass(JolokiaRequest.class);
        Mockito.verify(client).single(captured.capture());

        JsonNode filter =
                mapper.readTree((String) captured.getValue().arguments().get(0));
        assertThat(filter.get("field").asString()).isEqualTo("id");
        assertThat(filter.get("operation").asString()).isEqualTo("EQUALS");
        assertThat(filter.get("value").asString()).isEqualTo("sess-1");
    }
}
