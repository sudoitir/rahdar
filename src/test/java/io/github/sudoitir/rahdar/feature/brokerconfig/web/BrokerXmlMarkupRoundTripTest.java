package io.github.sudoitir.rahdar.feature.brokerconfig.web;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The XSS defences (ADR-0168) work at output and in the browser, so nothing between the editor and the
 * database may reject, escape, strip or re-encode what an operator writes. This sends a {@code broker.xml}
 * fragment full of markup through the whole filter chain and the real endpoints, saves it, and reads it
 * back as a document and as XML.
 */
class BrokerXmlMarkupRoundTripTest extends PostgresIntegrationTest {

    /** Names and values that look like markup, in an XML comment, an attribute, an element and a CDATA section. */
    private static final String ADDRESS = "orders&co<new>\"'";

    private static final String QUEUE = "q&1<b>";
    private static final String FILTER = "color = '<red>' AND size > 3 AND note <> '&'";
    private static final String MATCH = "a&b<c>.#";
    private static final String DEAD_LETTER = "DLQ<x>&y]]>z";

    private static final String XML = """
            <core xmlns="urn:activemq:core">
              <!-- <script>alert(1)</script> & <![CDATA[ not real ]]> -->
              <addresses>
                <address name="orders&amp;co&lt;new&gt;&quot;&apos;">
                  <anycast>
                    <queue name="q&amp;1&lt;b&gt;">
                      <filter string="color = '&lt;red&gt;' AND size &gt; 3 AND note &lt;&gt; '&amp;'"/>
                    </queue>
                  </anycast>
                </address>
              </addresses>
              <address-settings>
                <address-setting match="a&amp;b&lt;c&gt;.#">
                  <dead-letter-address><![CDATA[DLQ<x>&y]]]]><![CDATA[>z]]></dead-letter-address>
                </address-setting>
              </address-settings>
            </core>
            """;

    private final JsonMapper json = new JsonMapper();

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        clusterId = clusters.save(new ClusterEntity("markup-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private UsernamePasswordAuthenticationToken admin() {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "admin", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private String body(RequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void markupInABrokerXmlFragmentSurvivesImportSaveAndExportUnchanged() throws Exception {
        String base = "/api/v1/clusters/" + clusterId + "/artemis/config";

        JsonNode imported = json.readTree(body(
                post(base + "/import-xml")
                        .with(csrf())
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML),
                200));
        assertThat(imported.path("errors")).isEmpty();
        assertThat(imported.path("unsupported")).isEmpty();
        JsonNode document = imported.path("document");
        assertThat(document.at("/addresses/0/name").asString()).isEqualTo(ADDRESS);
        assertThat(document.at("/addresses/0/queues/0/name").asString()).isEqualTo(QUEUE);
        assertThat(document.at("/addresses/0/queues/0/filter").asString()).isEqualTo(FILTER);
        assertThat(document.at("/addressSettings/0/match").asString()).isEqualTo(MATCH);
        assertThat(document.at("/addressSettings/0/values").toString()).contains(json.writeValueAsString(DEAD_LETTER));

        // Saved as the editor would send it, and read back as the document.
        body(
                put(base)
                        .with(csrf())
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document\":" + document
                                + ",\"expectedRevision\":null,\"note\":\"<b>markup</b> & more\"}"),
                201);
        JsonNode saved = json.readTree(body(get(base).with(authentication(admin())), 200));
        assertThat(saved.path("document")).isEqualTo(document);
        assertThat(saved.toString()).contains(json.writeValueAsString("<b>markup</b> & more"));

        // Exported, the fragment is well-formed XML whose escaping is the codec's, and importing it gives the same
        // document.
        String exported = body(get(base + "/export-xml").with(authentication(admin())), 200);
        assertThat(exported).contains("orders&amp;co&lt;new&gt;").doesNotContain("<script>");
        JsonNode again = json.readTree(body(
                post(base + "/import-xml")
                        .with(csrf())
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_XML)
                        .content(exported),
                200));
        assertThat(again.path("errors")).isEmpty();
        assertThat(again.path("document")).isEqualTo(document);
    }
}
