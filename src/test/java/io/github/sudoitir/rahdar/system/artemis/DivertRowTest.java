package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.BrokerXmlSnippets;
import java.io.StringReader;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The divert MBean's attribute names, pinned against a payload copied verbatim
 * from a running Artemis 2.56.0 broker.
 *
 * <p>These names are the whole contract of {@code DivertRow}, and a typo in one
 * produces a silently blank column rather than a failure. The payload is also the
 * evidence for ADR-0065 D2: every attribute the broker exposes is here, and none of
 * them says where the divert came from.
 */
class DivertRowTest {

    private static final String OBSERVED = """
            {
              "ForwardingAddress": "PROBE.OUT",
              "RetroactiveResource": false,
              "Address": "PROBE.IN",
              "Filter": null,
              "TransformerPropertiesAsJSON": "{}",
              "Exclusive": false,
              "TransformerProperties": {},
              "TransformerClassName": null,
              "RoutingName": "probe.routing",
              "UniqueName": "probe.divert",
              "RoutingType": "STRIP"
            }
            """;

    @Test
    void readsEveryFieldTheBrokerActuallyReports() {
        JsonNode attributes = JsonMapper.builder().build().readTree(OBSERVED);
        UUID nodeId = UUID.randomUUID();

        DivertRow row = DivertRow.parse(attributes, nodeId, "node-a");

        assertThat(row.nodeId()).isEqualTo(nodeId);
        assertThat(row.nodeName()).isEqualTo("node-a");
        assertThat(row.uniqueName()).isEqualTo("probe.divert");
        assertThat(row.routingName()).isEqualTo("probe.routing");
        assertThat(row.address()).isEqualTo("PROBE.IN");
        assertThat(row.forwardingAddress()).isEqualTo("PROBE.OUT");
        assertThat(row.routingType()).isEqualTo("STRIP");
        assertThat(row.filter()).isNull();
        assertThat(row.transformerClassName()).isNull();
        assertThat(row.exclusive()).isFalse();
        assertThat(row.retroactiveResource()).isFalse();
    }

    /**
     * The remedy is generated from the operator's own values so it can be pasted
     * rather than retyped. What is unset is omitted, so the broker's default stands
     * instead of being overwritten with an empty element.
     */
    @Test
    void theBrokerXmlRemedyCarriesTheEnteredValuesAndOmitsWhatWasNotEntered() {
        String xml = BrokerXmlSnippets.forDivert("audit-copy", null, "ORDER.IN", "AUDIT.IN", false, null, null);

        assertThat(xml)
                .contains("<divert name=\"audit-copy\">")
                .contains("<routing-name>audit-copy</routing-name>")
                .contains("<address>ORDER.IN</address>")
                .contains("<forwarding-address>AUDIT.IN</forwarding-address>")
                .contains("<exclusive>false</exclusive>")
                .doesNotContain("<filter")
                .doesNotContain("<routing-type>");

        assertThat(BrokerXmlSnippets.forDivert("d", "rn", "A", "B", true, "type = 'x'", "anycast"))
                .contains("<routing-name>rn</routing-name>")
                .contains("<filter string=\"type = 'x'\"/>")
                .contains("<routing-type>ANYCAST</routing-type>")
                .contains("<exclusive>true</exclusive>");
    }

    @Test
    void theRemedyStaysWellFormedWhateverTheFilterContains() throws Exception {
        String filter = "a < 3 AND b = \"x\" AND c <> 'y&z'";

        String xml = BrokerXmlSnippets.forDivert("d", null, "A", "B", false, filter, null);

        Document doc =
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertThat(((Element) doc.getElementsByTagName("filter").item(0)).getAttribute("string"))
                .isEqualTo(filter);
    }
}
