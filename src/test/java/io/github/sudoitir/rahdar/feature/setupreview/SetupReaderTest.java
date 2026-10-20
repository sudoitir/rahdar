package io.github.sudoitir.rahdar.feature.setupreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One batched read of a node, and how each part of it fails on its own (ADR-0106). */
class SetupReaderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final UUID NODE_ID = UUID.randomUUID();

    private final ArtemisNode client = mock(ArtemisNode.class);
    private final SetupReader reader = new SetupReader(MAPPER);

    @BeforeEach
    void brokerResolves() {
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b1\"");
    }

    private static ClusterNode node(Boolean active, String artemisNodeId) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(NODE_ID);
        when(node.getName()).thenReturn("node-a");
        when(node.getActive()).thenReturn(active);
        when(node.getNativeNodeId()).thenReturn(artemisNodeId);
        return node;
    }

    private static ManagementResponse ok(String json) {
        return new ManagementResponse(200, MAPPER.readTree(json), null, null, null);
    }

    private static ManagementResponse failed(int status, String error) {
        return new ManagementResponse(status, null, error, null, null);
    }

    private void answer(ManagementResponse... responses) {
        when(client.batch(anyList())).thenReturn(List.of(responses));
    }

    @Test
    void readsAllThreePartsOfAHealthyNode() {
        answer(
                ok("{\"NodeID\":\"reported\",\"Active\":true}"),
                ok("{\"deadLetterAddress\":\"DLQ\"}"),
                ok("{\"org.x:name=\\\"my-cluster\\\"\":{\"Started\":true}}"));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());

        NodeRead read = reader.read(client, node(true, "known"));

        assertThat(read.readable()).isTrue();
        assertThat(read.artemisNodeId()).isEqualTo("reported");
        assertThat(read.live()).isTrue();
        assertThat(read.defaultAddressSettings().get("deadLetterAddress").asString())
                .isEqualTo("DLQ");
        assertThat(read.addressSettingsError()).isNull();
        assertThat(read.clusterConnections()).containsOnlyKeys("my-cluster");
        assertThat(read.clusterConnectionsError()).isNull();
    }

    @Test
    void theBrokersOwnActiveFlagOverridesTheLastScrape() {
        answer(ok("{\"Active\":false}"), ok("{}"), failed(404, null));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());

        assertThat(reader.read(client, node(true, "known")).live()).isFalse();
    }

    @Test
    void anAbsentOrNonBooleanActiveKeepsTheKnownState() {
        answer(ok("{\"Active\":\"yes\"}"), ok("{}"), failed(404, null));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());

        assertThat(reader.read(client, node(true, "known")).live()).isTrue();
        assertThat(reader.read(client, node(null, "known")).live()).isFalse();
    }

    @Test
    void aMissingOrBlankReportedNodeIdFallsBackToTheKnownOne() {
        answer(ok("{\"NodeID\":\" \"}"), ok("{}"), failed(404, null));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());
        assertThat(reader.read(client, node(true, "known")).artemisNodeId()).isEqualTo("known");

        answer(ok("{\"NodeID\":null}"), ok("{}"), failed(404, null));
        assertThat(reader.read(client, node(true, "known")).artemisNodeId()).isEqualTo("known");

        answer(ok("{}"), ok("{}"), failed(404, null));
        assertThat(reader.read(client, node(true, "known")).artemisNodeId()).isEqualTo("known");
    }

    @Test
    void aBrokerWithNoClusterConnectionAnswers404AndThatIsNone() {
        answer(ok("{}"), ok("{}"), failed(404, "no such mbean"));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());

        NodeRead read = reader.read(client, node(true, null));

        assertThat(read.clusterConnections()).isEmpty();
        assertThat(read.clusterConnectionsError()).isNull();
    }

    @Test
    void otherClusterConnectionFailuresLeaveThatPartNullWithItsReason() {
        answer(ok("{}"), ok("{}"), failed(500, "boom"));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());
        NodeRead withError = reader.read(client, node(true, null));
        assertThat(withError.clusterConnections()).isNull();
        assertThat(withError.clusterConnectionsError()).contains("boom");

        answer(ok("{}"), ok("{}"), failed(500, null));
        assertThat(reader.read(client, node(true, null)).clusterConnectionsError())
                .contains("status 500");

        // an OK answer that is not an object is not "none" either
        answer(ok("{}"), ok("{}"), ok("[]"));
        assertThat(reader.read(client, node(true, null)).clusterConnections()).isNull();
    }

    @Test
    void defaultSettingsThatFailOrAreNotAnObjectLeaveThatPartNull() {
        answer(ok("{}"), failed(500, "denied"), failed(404, null));
        NodeRead failedRead = reader.read(client, node(true, null));
        assertThat(failedRead.defaultAddressSettings()).isNull();
        assertThat(failedRead.addressSettingsError())
                .isEqualTo("The default address settings could not be read: denied");
        assertThat(failedRead.readable()).isTrue();

        answer(ok("{}"), ok("\"text\""), failed(404, null));
        when(client.parsed(ArgumentMatchers.any())).thenAnswer(i -> ((ManagementResponse) i.getArgument(0)).value());
        NodeRead scalar = reader.read(client, node(true, null));
        assertThat(scalar.defaultAddressSettings()).isNull();
        assertThat(scalar.addressSettingsError()).contains("status 200");
    }

    @Test
    void aBatchThatAnswersTheWrongNumberOfEntriesIsUnreadable() {
        answer(ok("{}"));

        NodeRead read = reader.read(client, node(true, "known"));

        assertThat(read.readable()).isFalse();
        assertThat(read.unavailableReason()).isEqualTo("The node answered 1 of 3 batched requests.");
        assertThat(read.artemisNodeId()).isEqualTo("known");
        assertThat(read.live()).isTrue();
    }

    @Test
    void aBrokerMBeanThatCannotBeReadIsUnreadableWithItsReason() {
        answer(failed(403, "forbidden"), ok("{}"), ok("{}"));
        assertThat(reader.read(client, node(true, null)).unavailableReason())
                .isEqualTo("The broker MBean could not be read: forbidden");

        answer(failed(500, null), ok("{}"), ok("{}"));
        assertThat(reader.read(client, node(true, null)).unavailableReason()).endsWith("status 500");
    }

    @Test
    void aConnectionFailureUsesItsMessageOrTheKindsDefault() {
        when(client.batch(anyList()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "no route"));
        assertThat(reader.read(client, node(false, null)).unavailableReason()).isEqualTo("no route");

        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.CREDENTIALS_REJECTED, null))
                .when(client)
                .batch(anyList());
        assertThat(reader.read(client, node(false, null)).unavailableReason())
                .isEqualTo(BrokerConnectionException.Kind.CREDENTIALS_REJECTED.defaultMessage());
    }

    @Test
    void anUnexpectedRuntimeFailureIsUnreadableNotThrown() {
        when(client.batch(anyList())).thenThrow(new IllegalStateException("garbled"));

        NodeRead read = reader.read(client, node(true, null));

        assertThat(read.unavailableReason()).isEqualTo("The node's answer could not be read: garbled");
    }

    @Test
    void nameStripsTheObjectNameDownToTheClusterConnection() {
        assertThat(SetupReader.name("org.x:broker=\"b\",component=cluster-connections,name=\"rahdar-dev\""))
                .isEqualTo("rahdar-dev");
        assertThat(SetupReader.name("org.x:name=plain,other=1")).isEqualTo("plain");
        assertThat(SetupReader.name("no-name-here")).isEqualTo("no-name-here");
        assertThat(SetupReader.name("x:name=\"")).isEqualTo("\"");
    }
}
