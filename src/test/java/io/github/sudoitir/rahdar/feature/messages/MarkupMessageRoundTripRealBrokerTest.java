package io.github.sudoitir.rahdar.feature.messages;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Registrations;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rahdar shows what a broker holds and sends what an operator writes, and its XSS defences (ADR-0168) sit
 * at output and in the browser, so the server must hand back, byte for byte, a message body and headers
 * that are markup, on a queue whose name is markup too. This runs them through the whole filter chain and
 * the real endpoints against a real Artemis: sent, browsed, and opened.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class MarkupMessageRoundTripRealBrokerTest extends PostgresIntegrationTest {

    private static final String SCRIPT = "<script>alert(1)</script>";

    private static final List<String> BODIES = List.of(
            SCRIPT,
            "<html><body onload=\"alert(1)\">" + SCRIPT + "&nbsp;&amp;&lt;</body></html>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- <b>note</b> -->\n<order id=\"1&amp;2\"><note><![CDATA["
                    + SCRIPT + " & </note> ]]></note><name>Zoë 日本語</name></order>\n",
            "{\"html\":\"<img src=x onerror=alert(1)>\",\"close\":\"</script>\",\"amp\":\"a&b\"}",
            "select '<b>&amp;</b>' from queues -- <script>");

    private final JsonMapper json = new JsonMapper();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String queue = "XSS." + run + ".<q>&co";

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    WebApplicationContext webContext;

    private MockMvc mvc;
    private UUID clusterId;
    private ArtemisNode client;
    private String broker;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var attempt = clusterService.register(Registrations.artemis(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "markup-real-" + run,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
        queueOps.createAddress(client, broker, queue, "ANYCAST");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", queue);
        config.put("address", queue);
        config.put("routing-type", "ANYCAST");
        config.put("durable", true);
        queueOps.createQueue(client, broker, config);
    }

    @AfterEach
    void cleanUp() {
        try {
            queueOps.destroyQueue(client, broker, queue, true);
            queueOps.deleteAddress(client, broker, queue);
        } catch (RuntimeException _) {
            // already gone
        }
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private UsernamePasswordAuthenticationToken admin() {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "admin", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    /** The queue's id goes in as a URI variable, so it is encoded into the path the way a browser does. */
    private static final String MESSAGES = "/api/v1/clusters/{cluster}/resources/artemis.queue/{queue}/messages";

    /** The queue's id without its kind, which the route takes as one segment, to be encoded once more. */
    private String segment() {
        String id = ResourceId.of("artemis.queue", queue).format();
        return id.substring(id.indexOf('/') + 1);
    }

    private JsonNode ok(RequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus())
                .as("%s %s %s", response.getErrorMessage(), response.getHeaderNames(), response.getContentAsString())
                .isEqualTo(200);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void markupInABodyAHeaderAndAQueueNameComesBackByteForByte() throws Exception {
        for (String body : BODIES) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("kind", "TEXT");
            request.put("durable", true);
            request.put("body", body);
            request.put(
                    "properties", Map.of("note", "<img src=x onerror=alert(2)> & \"quoted\"", "html", "<b>&amp;</b>"));
            ok(post(MESSAGES, clusterId, segment())
                    .with(csrf())
                    .with(authentication(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request)));
        }

        // A send is acknowledged before every broker version lists the message in a browse, so wait for all of them.
        JsonNode page = await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> ok(get(MESSAGES, clusterId, segment()).with(authentication(admin()))),
                        listed -> listed.path("data").size() == BODIES.size());

        List<String> seen = new ArrayList<>();
        for (JsonNode row : page.path("data")) {
            JsonNode detail = ok(get(
                            MESSAGES + "/{id}",
                            clusterId,
                            segment(),
                            row.path("position").asString())
                    .with(authentication(admin())));
            seen.add(detail.path("body").asString());
            assertThat(detail.path("stringProperties").path("note").asString())
                    .isEqualTo("<img src=x onerror=alert(2)> & \"quoted\"");
            assertThat(detail.path("stringProperties").path("html").asString()).isEqualTo("<b>&amp;</b>");
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(BODIES);
    }
}
