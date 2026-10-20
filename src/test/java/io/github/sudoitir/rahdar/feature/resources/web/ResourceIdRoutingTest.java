package io.github.sudoitir.rahdar.feature.resources.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A resource id travels as one URL segment, so a name that holds a slash or a percent sign arrives as
 * {@code %2F} or {@code %25}, encoded once more. Over a real HTTP client, so Tomcat and the firewall are
 * in the path, such a subscription is listed with its id, reached by it, and a custom method is split off
 * the same segment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceIdRoutingTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NAME = "orders/eu%1";
    private static final String ID = "artemis.queue/orders%2Feu%251";
    /** The id once more percent-encoded, as one path segment. */
    private static final String SEGMENT = "orders%252Feu%25251";

    @LocalServerPort
    int port;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerGateway gateway;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http =
            HttpClient.newBuilder().cookieHandler(cookies).build();
    private UUID clusterId;

    @BeforeEach
    void setUp() throws Exception {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://localhost:8161/console/jolokia");
        UUID nodeId = nodes.save(node).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId, nodeId, "orders.addr", NAME, "ANYCAST", true, 3, 1, 0, 0, 0, 0, 0, false, "artemis.queue")));
        ClusterConnection connection = FixtureJolokia.cluster(Map.of(nodeId, new FixtureJolokia()));
        when(gateway.cluster(clusterId)).thenReturn(connection);

        String username = "routing-" + UUID.randomUUID().toString().substring(0, 8);
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        UUID userId = users.save(user).getId();
        UUID role = roles.save(new RoleEntity("routing-" + UUID.randomUUID(), false))
                .getId();
        rolePermissions.save(new RolePermissionEntity(role, Permissions.WILDCARD));
        userRoles.save(new UserRoleEntity(userId, role, "GLOBAL", ScopeIds.GLOBAL));
        send("GET", "/api/v1/auth/providers");
        assertThat(send(
                                "POST",
                                "/api/v1/auth/login",
                                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))
                        .statusCode())
                .isEqualTo(200);
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return send(method, path, null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        String xsrf = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("X-XSRF-TOKEN", xsrf);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), BodyHandlers.ofString());
    }

    @Test
    void aNameWithASlashAndAPercentSignIsListedWithItsIdAndAddressedByIt() throws Exception {
        HttpResponse<String> list = send("GET", "/api/v1/clusters/" + clusterId + "/subscriptions");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].id")).isEqualTo(ID);
        assertThat(JsonPath.<String>read(list.body(), "$.data[0].name")).isEqualTo(NAME);

        HttpResponse<String> gone =
                send("GET", "/api/v1/clusters/" + clusterId + "/resources/artemis.queue/" + SEGMENT + "-gone");
        assertThat(gone.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(gone.body(), "$.detail")).contains(NAME + "-gone");
    }

    @Test
    void aCustomMethodIsSplitOffTheSameSegment() throws Exception {
        HttpResponse<String> missing = send(
                "POST",
                "/api/v1/clusters/" + clusterId + "/resources/artemis.queue/" + SEGMENT + "-gone:pause?dryRun=true");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(missing.body(), "$.detail")).contains(NAME + "-gone");
    }

    @Test
    void anIdThatDoesNotParseIsAnInvalidResourceId() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/v1/clusters/" + clusterId + "/resources/not-a-kind/" + SEGMENT);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.type")).endsWith("invalid-resource-id");
    }
}
