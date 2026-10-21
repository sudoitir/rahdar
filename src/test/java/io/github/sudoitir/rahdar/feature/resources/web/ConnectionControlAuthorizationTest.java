package io.github.sudoitir.rahdar.feature.resources.web;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code connection:close} is implied by nothing (ADR-0057 D7).
 *
 * <p>Authority over a cluster's messages says nothing about authority to
 * disconnect the applications producing and consuming them, and the refusal is a
 * {@code 404} rather than a {@code 403} so probing tells an unauthorized caller
 * nothing about which cluster ids exist.
 */
class ConnectionControlAuthorizationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;
    private UUID clusterId;
    private final UUID nodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        clusterId = clusters.save(new ClusterEntity("conn-auth-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "scoped", Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private int status(String path, String... permissions) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(csrf()).with(authentication(callerWith(permissions))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String connectionClose(UUID cluster) {
        return "/api/v1/clusters/" + cluster + "/nodes/" + nodeId + "/connections/abc/close?dryRun=true";
    }

    @Test
    void everyMessagePermissionTogetherDoesNotGrantAClose() throws Exception {
        assertThat(status(
                        connectionClose(clusterId),
                        Permissions.CLUSTER_READ,
                        MessagePermissions.MESSAGE_READ,
                        MessagePermissions.MESSAGE_SEND,
                        MessagePermissions.MESSAGE_MOVE,
                        MessagePermissions.MESSAGE_DELETE,
                        MessagePermissions.SUBSCRIPTION_PURGE))
                .isEqualTo(404);
    }

    @Test
    void theQueueLifecyclePermissionsDoNotGrantACloseEither() throws Exception {
        assertThat(status(
                        "/api/v1/clusters/" + clusterId + "/resources/artemis.address/orders:close?dryRun=true",
                        Permissions.CLUSTER_READ,
                        ResourcePermissions.SUBSCRIPTION_CREATE,
                        ResourcePermissions.SUBSCRIPTION_DELETE,
                        ResourcePermissions.SUBSCRIPTION_PAUSE))
                .isEqualTo(404);
    }

    /**
     * The grant is what makes the difference. Without it the address close is a
     * {@code 404}; with it the request reaches the fan-out and fails on the cluster
     * having no manageable node — a {@code 502}. A bare "denied returns 404" would
     * also pass if the route 404'd for some unrelated reason, so only the pair
     * proves the guard is what produced it.
     */
    @Test
    void theGrantIsWhatMakesTheDifference() throws Exception {
        String path = "/api/v1/clusters/" + clusterId + "/resources/artemis.address/orders:close?dryRun=true";

        assertThat(status(path, Permissions.CLUSTER_READ)).isEqualTo(404);
        assertThat(status(path, Permissions.CLUSTER_READ, ResourcePermissions.CONNECTION_CLOSE))
                .isEqualTo(502);
    }

    @Test
    void aCallerWithoutTheGrantCannotTellARealClusterFromAnInventedOne() throws Exception {
        UUID invented = UUID.randomUUID();
        assertThat(status(connectionClose(clusterId), Permissions.CLUSTER_READ))
                .isEqualTo(status(connectionClose(invented), Permissions.CLUSTER_READ));
    }
}
