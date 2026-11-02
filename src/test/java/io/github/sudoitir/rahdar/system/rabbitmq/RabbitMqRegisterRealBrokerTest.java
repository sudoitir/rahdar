package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * A RabbitMQ cluster is checked and registered through the API against a real broker (ADR-0219): the check lists every
 * endpoint it tried with the outcome, names the cluster, tells a wrong address from a dead one, and stores nothing; the
 * registration stores the cluster and its nodes with the AMQP endpoints the operator gave.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RabbitMqRegisterRealBrokerTest extends PostgresIntegrationTest {

    private static RabbitMqBrokers rabbit;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    private MockMvc mvc;
    private final String run = "rmq-register-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void startRabbit() {
        rabbit = new RabbitMqBrokers().start();
    }

    @AfterAll
    static void stopRabbit() {
        rabbit.close();
    }

    @BeforeEach
    void mvc() {
        mvc = webAppContextSetup(webContext).build();
    }

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").toList().toString();
    }

    private String body(String name, List<String> management, List<String> amqp, String user, String password) {
        return "{\"system\":\"rabbitmq\",\"name\":\"" + run + "-" + name + "\",\"connection\":{\"system\":\"rabbitmq\","
                + "\"managementUrls\":" + quoted(management) + ",\"amqpEndpoints\":" + quoted(amqp)
                + ",\"credentials\":{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}}}";
    }

    private String body(String name, List<String> management, List<String> amqp) {
        return body(name, management, amqp, RabbitMqBrokers.USER, RabbitMqBrokers.PASSWORD);
    }

    private static String first(String json, String filter) {
        return JsonPath.<List<String>>read(json, filter).getFirst();
    }

    private MockHttpServletResponse send(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
    }

    private String check(String body) throws Exception {
        MockHttpServletResponse response = send("/api/v1/clusters?dryRun=true", body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    @Test
    void theCheckListsEveryEndpointWithItsOutcomeNamesTheClusterAndStoresNothing() throws Exception {
        int before = clusters.findAll().size();

        String check = check(body("check", List.of(rabbit.managementUrl()), List.of(rabbit.amqpEndpoint())));

        assertThat((Object) JsonPath.read(check, "$.failure")).isNull();
        assertThat((Boolean) JsonPath.read(check, "$.supported")).isTrue();
        assertThat((String) JsonPath.read(check, "$.clusterId")).startsWith("rabbit@");
        assertThat((Integer) JsonPath.read(check, "$.nodes.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(check, "$.nodes[0].outcome")).isEqualTo("OK");
        assertThat((String) JsonPath.read(check, "$.version.text")).isEqualTo(RabbitMqBrokers.RELEASE);
        assertThat((Integer) JsonPath.read(check, "$.endpoints.length()")).isEqualTo(2);
        assertThat(first(check, "$.endpoints[?(@.plane=='MANAGEMENT')].outcome"))
                .isEqualTo("OK");
        assertThat(first(check, "$.endpoints[?(@.plane=='DATA')].outcome")).isEqualTo("OK");
        assertThat(clusters.findAll()).hasSize(before);
    }

    @Test
    void aManagementAddressThatIsNotRabbitMqIsWrongEndpoint() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            byte[] page = "<html>hello</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            try (var out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        other.start();
        try {
            String address = "http://127.0.0.1:" + other.getAddress().getPort();

            String check = check(body("wrong", List.of(address), List.of()));

            assertThat((String) JsonPath.read(check, "$.failure.cause")).isEqualTo("WRONG_ENDPOINT");
            assertThat((String) JsonPath.read(check, "$.failure.next")).contains("management plugin");
            assertThat((String) JsonPath.read(check, "$.endpoints[0].outcome")).isEqualTo("UNREACHABLE");
        } finally {
            other.stop(0);
        }
    }

    @Test
    void anAmqpEndpointNobodyListensOnIsPartlyReachedAndDataUnreachableWhenNoneAnswers() throws Exception {
        String partly = check(
                body("partly", List.of(rabbit.managementUrl()), List.of(rabbit.amqpEndpoint(), "amqp://127.0.0.1:1")));

        assertThat((Object) JsonPath.read(partly, "$.failure")).isNull();
        assertThat(first(partly, "$.endpoints[?(@.address=='amqp://127.0.0.1:1')].outcome"))
                .isEqualTo("UNREACHABLE");
        assertThat(first(partly, "$.endpoints[?(@.address=='" + rabbit.amqpEndpoint() + "')].outcome"))
                .isEqualTo("OK");

        String none = check(body("none", List.of(rabbit.managementUrl()), List.of("amqp://127.0.0.1:1")));

        assertThat((String) JsonPath.read(none, "$.failure.cause")).isEqualTo("DATA_UNREACHABLE");
        assertThat(first(none, "$.endpoints[?(@.plane=='MANAGEMENT')].outcome")).isEqualTo("OK");
        MockHttpServletResponse refused = send(
                "/api/v1/clusters", body("none-store", List.of(rabbit.managementUrl()), List.of("amqp://127.0.0.1:1")));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void badCredentialsFailTheCheckAsAuthentication() throws Exception {
        String check = check(
                body("bad", List.of(rabbit.managementUrl()), List.of(), RabbitMqBrokers.USER, "not-the-password"));

        assertThat((String) JsonPath.read(check, "$.failure.cause")).isEqualTo("AUTHENTICATION");
        assertThat((String) JsonPath.read(check, "$.failure.next")).contains("user name and password");
        assertThat((String) JsonPath.read(check, "$.endpoints[0].outcome")).isEqualTo("UNREACHABLE");
    }

    @Test
    void aManagementAddressNobodyListensOnIsRefused() throws Exception {
        String check = check(body("down", List.of("http://127.0.0.1:1"), List.of()));

        assertThat((String) JsonPath.read(check, "$.failure.cause")).isEqualTo("REFUSED");
    }

    @Test
    void registeringStoresTheClusterAndItsNodesWithTheAmqpEndpointsOnly() throws Exception {
        MockHttpServletResponse created = send(
                "/api/v1/clusters", body("store", List.of(rabbit.managementUrl()), List.of(rabbit.amqpEndpoint())));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        String detail = created.getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(detail, "$.id"));

        assertThat((String) JsonPath.read(detail, "$.system.id")).isEqualTo("rabbitmq");
        assertThat(detail).doesNotContain(RabbitMqBrokers.PASSWORD);
        List<BrokerNodeEntity> stored = nodes.findByClusterIdOrderByNameAsc(id);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getName()).startsWith("rabbit@");
        assertThat(stored.getFirst().getEndpoints())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.plane()).isEqualTo(Plane.DATA));

        String again = check(body("store-again", List.of(rabbit.managementUrl()), List.of(rabbit.amqpEndpoint())));
        assertThat((String) JsonPath.read(again, "$.alreadyRegistered.id")).isEqualTo(id.toString());
        mvc.perform(get("/api/v1/clusters/{id}", id)).andReturn();
    }
}
