package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.feature.resources.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pre-close read, through the driver, against the row shapes a real broker returns.
 *
 * <p>The one that matters is the confirmation token. ADR-0057 D3 says an operator confirms against
 * something they can recognise, and a session row carries no remote address and, for a CORE client,
 * a blank client id. If the snapshot does not reach for the connection's identity, the token silently
 * degrades to the opaque connection id, which is exactly what the ADR forbids.
 */
class ConnectionOperationsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SEARCH =
            "{\"status\":200,\"value\":[\"org.apache.activemq.artemis:broker=\\\"b\\\"\"]}";

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

    private final FixtureJolokia server = new FixtureJolokia();
    private final ConnectionOperations ops = new ConnectionOperations();

    @AfterEach
    void stop() {
        server.close();
    }

    /** One list operation's answer: its rows in the double-encoded envelope Artemis answers with. */
    private static String list(String rows) {
        return "{\"status\":200,\"value\":" + JSON.writeValueAsString("{\"data\":" + rows + ",\"count\":1}") + "}";
    }

    private void answer(String... bodies) {
        for (String body : bodies) {
            server.expect().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
    }

    @Test
    void aSessionWithNoClientIdIsIdentifiedByItsConnectionsRemoteAddress() {
        // The session, then its connection and its consumers together.
        answer(SEARCH, list(SESSION_ROW), "[" + list(CONNECTION_ROW) + "," + list(CONSUMER_ROW) + "]");

        ConnectionSnapshot snapshot = ops.readSession(server.target(), "sess-1");

        assertThat(snapshot.remoteAddress()).isEqualTo("172.21.0.5:53156");
        // The token a human types: never the opaque id.
        assertThat(snapshot.label()).isEqualTo("172.21.0.5:53156").isNotEqualTo("7762766a");
        assertThat(snapshot.user()).isEqualTo("artemis");
        assertThat(snapshot.consumerCount()).isEqualTo(2);
        assertThat(snapshot.messagesInTransit()).isEqualTo(7L);
    }

    @Test
    void aConnectionSnapshotCarriesTheIdentityAndTheInFlightCount() {
        // The connection and its sessions together, then the one session's consumers.
        answer(SEARCH, "[" + list(CONNECTION_ROW) + "," + list(SESSION_ROW) + "]", list(CONSUMER_ROW));

        ConnectionSnapshot snapshot = ops.readConnection(server.target(), "7762766a");

        assertThat(snapshot.remoteAddress()).isEqualTo("172.21.0.5:53156");
        assertThat(snapshot.user()).isEqualTo("artemis");
        assertThat(snapshot.sessionCount()).isEqualTo(1);
        assertThat(snapshot.consumerCount()).isEqualTo(2);
        assertThat(snapshot.messagesInTransit()).isEqualTo(7L);
    }

    @Test
    void anEmptyListMeansTheTargetIsGone() {
        answer(SEARCH, list("[]"));

        assertThat(ops.readSession(server.target(), "sess-1")).isNull();
    }

    /** Reads are filtered on the broker, so one row comes back rather than a full dump. */
    @Test
    void readsAskTheBrokerToFilterRatherThanFetchingEveryRow() {
        answer(SEARCH, list("[]"));

        ops.readSession(server.target(), "sess-1");

        List<String> sent = server.requests();
        assertThat(sent.get(1))
                .contains("listSessions(java.lang.String,int,int)")
                .contains("{\\\"field\\\":\\\"id\\\",\\\"operation\\\":\\\"EQUALS\\\",\\\"value\\\":\\\"sess-1\\\"}");
    }
}
