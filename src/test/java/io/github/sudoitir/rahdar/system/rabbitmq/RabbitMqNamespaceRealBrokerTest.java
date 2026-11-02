package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * The RabbitMQ namespace against a real broker (ADR-0223): the nodes; a virtual host created, changed and deleted after
 * a preview, with the etag refused as stale when the host moved; bindings; a policy previewed with what it matches;
 * permissions; and shovels, each change leaving its audit row.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RabbitMqNamespaceRealBrokerTest extends PostgresIntegrationTest {

    private static RabbitMqBrokers rabbit;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    JdbcClient jdbc;

    private MockMvc mvc;
    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String vhost = "ns-" + run;

    @BeforeAll
    static void startRabbit() {
        rabbit = new RabbitMqBrokers().start();
    }

    @AfterAll
    static void stopRabbit() {
        rabbit.close();
    }

    @BeforeEach
    void register() throws Exception {
        mvc = webAppContextSetup(webContext).build();
        rabbit.vhost(vhost);
        MockHttpServletResponse created = mvc.perform(post("/api/v1/clusters")
                        .contentType(APPLICATION_JSON)
                        .content("{\"system\":\"rabbitmq\",\"name\":\"rmq-ns-" + run
                                + "\",\"connection\":{\"system\":\"rabbitmq\",\"managementUrls\":[\""
                                + rabbit.managementUrl() + "\"],\"amqpEndpoints\":[\"" + rabbit.amqpEndpoint()
                                + "\"],\"credentials\":{\"username\":\"" + RabbitMqBrokers.USER + "\",\"password\":\""
                                + RabbitMqBrokers.PASSWORD + "\"}}}"))
                .andReturn()
                .getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        cluster = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
    }

    @AfterEach
    void unregister() {
        clusters.deleteById(cluster);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(
                        body == null
                                ? request
                                : request.contentType(APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
    }

    private String ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MockHttpServletResponse response = send(request, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        return response.getContentAsString();
    }

    /** A route whose last segment is a resource id: its slashes are encoded, so the id is one path segment. */
    private URI route(String prefix, String id, String suffix) {
        return URI.create(api(prefix) + id.replace("/", "%2F") + suffix);
    }

    private static String first(String json, String filter) {
        return JsonPath.<List<String>>read(json, filter).getFirst();
    }

    private String api(String path) {
        return "/api/v1/clusters/" + cluster + "/rabbitmq" + path;
    }

    private List<String> audited(String action) {
        return jdbc.sql("SELECT outcome FROM audit_event WHERE cluster_id = ? AND action = ? ORDER BY ts")
                .param(cluster)
                .param(action)
                .query(String.class)
                .list();
    }

    @Test
    void theNodesAreListedWithTheirListeners() throws Exception {
        String nodes = ok(get(api("/nodes")), null);

        assertThat((String) JsonPath.read(nodes, "$.clusterName")).startsWith("rabbit@");
        assertThat((String) JsonPath.read(nodes, "$.nodes[0].name")).startsWith("rabbit@");
        assertThat((Boolean) JsonPath.read(nodes, "$.nodes[0].running")).isTrue();
        assertThat(nodes).as(nodes).contains("\"version\":\"" + RabbitMqBrokers.RELEASE + "\"");
        assertThat((Integer) JsonPath.read(nodes, "$.nodes[0].listeners.length()"))
                .isPositive();
    }

    @Test
    void aVirtualHostIsCreatedChangedAndDeletedAfterAPreviewEachTimeAndAStaleEtagIsRefused() throws Exception {
        String name = "made-" + run;
        String createBody = "{\"name\":\"" + name + "\",\"description\":\"first\",\"tags\":[\"test\"]}";
        String preview = ok(post(api("/vhosts:preview")), createBody);
        assertThat((String) JsonPath.read(preview, "$.identityBasis")).isEqualTo("CONFIGURATION");
        String etag = JsonPath.read(preview, "$.etag");

        String created = ok(
                post(api("/vhosts")),
                "{\"name\":\"" + name + "\",\"description\":\"first\",\"tags\":[\"test\"],\"etag\":\"" + etag + "\"}");
        assertThat((String) JsonPath.read(created, "$.identityBasis")).isEqualTo("CONFIGURATION");
        String listed = ok(get(api("/vhosts")), null);
        assertThat(first(listed, "$.items[?(@.name=='" + name + "')].description"))
                .isEqualTo("first");

        String update = ok(patch(api("/vhosts/" + name + ":preview")), "{\"description\":\"second\"}");
        String updateEtag = JsonPath.read(update, "$.etag");
        // The host changes between the preview and the call: the etag no longer holds.
        rabbit.call("PUT", "/api/vhosts/" + name, "{\"description\":\"moved\"}");
        assertThat(send(patch(api("/vhosts/" + name)), "{\"description\":\"second\",\"etag\":\"" + updateEtag + "\"}")
                        .getStatus())
                .isEqualTo(412);
        String again = ok(patch(api("/vhosts/" + name + ":preview")), "{\"description\":\"second\"}");
        ok(
                patch(api("/vhosts/" + name)),
                "{\"description\":\"second\",\"etag\":\"" + JsonPath.read(again, "$.etag") + "\"}");
        assertThat(ok(get(api("/vhosts")), null)).contains("second");

        String deletion = ok(delete(api("/vhosts/" + name + ":preview")), null);
        String deleteEtag = JsonPath.read(deletion, "$.etag");
        assertThat(send(delete(api("/vhosts/" + name)), null).getStatus()).isGreaterThanOrEqualTo(400);
        ok(delete(api("/vhosts/" + name + "?etag=" + deleteEtag)), null);
        assertThat(ok(get(api("/vhosts")), null)).doesNotContain("\"" + name + "\"");

        assertThat(audited("CREATE_NAMESPACE")).containsExactly("SUCCESS");
        assertThat(audited("UPDATE_NAMESPACE")).contains("SUCCESS");
        assertThat(audited("DELETE_NAMESPACE")).contains("SUCCESS");
    }

    @Test
    void aBindingIsPreviewedCreatedListedAndDeleted() throws Exception {
        rabbit.exchange(vhost, "x-" + run, "direct");
        rabbit.queue(vhost, "q-" + run, "classic");
        String body = "{\"vhost\":\"" + vhost + "\",\"source\":\"x-" + run + "\",\"destination\":\"q-" + run
                + "\",\"destinationType\":\"queue\",\"routingKey\":\"orders\"";

        String preview = ok(post(api("/bindings:preview")), body + "}");
        ok(post(api("/bindings")), body + ",\"etag\":\"" + JsonPath.read(preview, "$.etag") + "\"}");

        String listed = ok(get(api("/bindings?vhost=" + vhost + "&source=x-" + run)), null);
        assertThat((Integer) JsonPath.read(listed, "$.items.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(listed, "$.items[0].routingKey")).isEqualTo("orders");
        assertThat((String) JsonPath.read(listed, "$.items[0].destination")).isEqualTo("q-" + run);
        String id = JsonPath.read(listed, "$.items[0].id");

        String deletion = ok(delete(route("/bindings/", id, ":preview")), null);
        ok(delete(route("/bindings/", id, "?etag=" + JsonPath.read(deletion, "$.etag"))), null);
        assertThat((Integer) JsonPath.read(
                        ok(get(api("/bindings?vhost=" + vhost + "&source=x-" + run)), null), "$.items.length()"))
                .isZero();
        assertThat(audited("CREATE_ROUTE")).containsExactly("SUCCESS");
        assertThat(audited("DELETE_ROUTE")).containsExactly("SUCCESS");
    }

    @Test
    void aPolicyIsPreviewedWithWhatItMatchesSetAndCleared() throws Exception {
        rabbit.queue(vhost, "pol-a-" + run, "classic");
        rabbit.queue(vhost, "pol-b-" + run, "classic");
        rabbit.queue(vhost, "other-" + run, "classic");
        String body = "{\"pattern\":\"^pol-.*\",\"applyTo\":\"queues\",\"priority\":3,"
                + "\"definition\":{\"max-length\":\"100\"}";

        String preview = ok(put(api("/policies/" + vhost + "/limit:preview")), body + "}");
        assertThat((Integer) JsonPath.read(preview, "$.matches.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(preview, "$.matches[0].name")).startsWith("pol-");
        assertThat((Boolean) JsonPath.read(preview, "$.partial")).isFalse();
        ok(put(api("/policies/" + vhost + "/limit")), body + ",\"etag\":\"" + JsonPath.read(preview, "$.etag") + "\"}");

        String listed = ok(get(api("/policies?vhost=" + vhost)), null);
        assertThat((String) JsonPath.read(listed, "$.items[0].name")).isEqualTo("limit");
        assertThat((Integer) JsonPath.read(listed, "$.items[0].matches")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(listed, "$.items[0].priority")).isEqualTo(3);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(rabbit.call("GET", "/api/queues/" + vhost + "/pol-a-" + run, null)
                                .body())
                        .contains("\"policy\":\"limit\""));

        String clearing = ok(delete(api("/policies/" + vhost + "/limit:preview")), null);
        ok(delete(api("/policies/" + vhost + "/limit?etag=" + JsonPath.read(clearing, "$.etag"))), null);
        assertThat((Integer) JsonPath.read(ok(get(api("/policies?vhost=" + vhost)), null), "$.items.length()"))
                .isZero();
        assertThat(audited("SET_POLICY")).containsExactly("SUCCESS");
        assertThat(audited("CLEAR_POLICY")).containsExactly("SUCCESS");
    }

    @Test
    void aUsersPermissionsAreSetAndCleared() throws Exception {
        String user = "u-" + run;
        rabbit.call("PUT", "/api/users/" + user, "{\"password\":\"pw\",\"tags\":\"\"}");
        String body = "{\"configure\":\"^app-.*\",\"write\":\".*\",\"read\":\"^app-.*\"";

        String preview = ok(put(api("/permissions/" + vhost + "/" + user + ":preview")), body + "}");
        ok(
                put(api("/permissions/" + vhost + "/" + user)),
                body + ",\"etag\":\"" + JsonPath.read(preview, "$.etag") + "\"}");

        assertThat(first(ok(get(api("/users")), null), "$.items[?(@.name=='" + user + "')].name"))
                .isEqualTo(user);
        String listed = ok(get(api("/permissions?vhost=" + vhost + "&user=" + user)), null);
        assertThat((String) JsonPath.read(listed, "$.items[0].configure")).isEqualTo("^app-.*");
        assertThat((String) JsonPath.read(listed, "$.items[0].write")).isEqualTo(".*");

        String clearing = ok(delete(api("/permissions/" + vhost + "/" + user + ":preview")), null);
        ok(delete(api("/permissions/" + vhost + "/" + user + "?etag=" + JsonPath.read(clearing, "$.etag"))), null);
        assertThat((Integer) JsonPath.read(
                        ok(get(api("/permissions?vhost=" + vhost + "&user=" + user)), null), "$.items.length()"))
                .isZero();
        assertThat(audited("SET_ACCESS_RULE")).containsExactly("SUCCESS");
        assertThat(audited("CLEAR_ACCESS_RULE")).containsExactly("SUCCESS");
    }

    @Test
    void aShovelIsListedWithoutCredentialsAndDeleted() throws Exception {
        rabbit.queue(vhost, "from-" + run, "classic");
        rabbit.queue(vhost, "to-" + run, "classic");
        rabbit.call(
                "PUT",
                "/api/parameters/shovel/" + vhost + "/move-" + run,
                "{\"value\":{\"src-protocol\":\"amqp091\",\"src-uri\":\"amqp://" + RabbitMqBrokers.USER + ":"
                        + RabbitMqBrokers.PASSWORD + "@localhost/" + vhost + "\",\"src-queue\":\"from-" + run
                        + "\",\"dest-protocol\":\"amqp091\",\"dest-uri\":\"amqp://" + RabbitMqBrokers.USER + ":"
                        + RabbitMqBrokers.PASSWORD + "@localhost/" + vhost + "\",\"dest-queue\":\"to-" + run + "\"}}");

        String listed = ok(get(api("/shovels?vhost=" + vhost)), null);
        assertThat((Boolean) JsonPath.read(listed, "$.available")).isTrue();
        assertThat((Integer) JsonPath.read(listed, "$.items.length()")).isEqualTo(1);
        assertThat(listed).doesNotContain(RabbitMqBrokers.PASSWORD);
        String id = JsonPath.read(listed, "$.items[0].id");

        String deletion = ok(delete(route("/shovels/", id, ":preview")), null);
        ok(delete(route("/shovels/", id, "?etag=" + JsonPath.read(deletion, "$.etag"))), null);
        assertThat((Integer) JsonPath.read(ok(get(api("/shovels?vhost=" + vhost)), null), "$.items.length()"))
                .isZero();
        assertThat(audited("DELETE_ROUTE")).containsExactly("SUCCESS");
    }
}
