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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Each lifecycle route requires its own permission on the queue or address it acts on: a caller who may read
 * it and lacks the permission is refused with a 403 naming it, and a caller who may not read it, or has no
 * grant on the cluster, cannot learn that either exists.
 *
 * <p>The second half matters as much as the first: {@code ClusterAccessGuard}
 * answers {@code 404}, not {@code 403}, precisely so that probing for cluster ids
 * tells an unauthorized caller nothing. A lifecycle route that leaked a
 * {@code 403} would be a hole in that, on the newest and most destructive surface
 * the product has.
 */
class QueueLifecycleAuthorizationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        clusterId = clusters.save(new ClusterEntity("auth-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /** A caller holding exactly {@code permissions} on this cluster, and nothing else. */
    private UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "scoped", Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static final String CREATE_BODY = """
            {"kind":"artemis.queue","name":"q","destination":"artemis.address/a","attributes":{"routingType":"ANYCAST"}}""";

    // ---- each route demands its own permission ---------------------------

    @Test
    void createNeedsTheCreatePermission() throws Exception {
        // Reading the cluster is not enough: the refusal is a 403 that names the permission.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/subscriptions")
                        .with(csrf())
                        .with(authentication(callerWith(Permissions.CLUSTER_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(result.getResponse().getContentAsString())
                            .contains(ResourcePermissions.SUBSCRIPTION_CREATE);
                });
    }

    @Test
    void purgeDoesNotImplyDestroy() throws Exception {
        // Emptying a queue and removing it are different authorities.
        mvc.perform(MockMvcRequestBuilders.delete(
                                "/api/v1/clusters/" + clusterId + "/resources/artemis.queue/q?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(
                                Permissions.CLUSTER_READ,
                                Permissions.SUBSCRIPTION_READ,
                                MessagePermissions.SUBSCRIPTION_PURGE))))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(result.getResponse().getContentAsString())
                            .contains(ResourcePermissions.SUBSCRIPTION_DELETE);
                });
    }

    @Test
    void updateDoesNotImplyPause() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(
                                "/api/v1/clusters/" + clusterId + "/resources/artemis.queue/q:pause?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(
                                Permissions.CLUSTER_READ,
                                Permissions.SUBSCRIPTION_READ,
                                ResourcePermissions.SUBSCRIPTION_UPDATE))))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(result.getResponse().getContentAsString())
                            .contains(ResourcePermissions.SUBSCRIPTION_PAUSE);
                });
    }

    @Test
    void createDoesNotImplyDelete() throws Exception {
        mvc.perform(MockMvcRequestBuilders.delete(
                                "/api/v1/clusters/" + clusterId + "/resources/artemis.address/a?dryRun=true")
                        .with(csrf())
                        .with(authentication(callerWith(
                                Permissions.CLUSTER_READ,
                                Permissions.DESTINATION_READ,
                                ResourcePermissions.SUBSCRIPTION_CREATE,
                                ResourcePermissions.DESTINATION_CREATE))))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(result.getResponse().getContentAsString())
                            .contains(ResourcePermissions.DESTINATION_DELETE);
                });
    }

    @Test
    void aQueueTheCallerMayNotReadIsNotFoundWhateverElseTheyHold() throws Exception {
        mvc.perform(MockMvcRequestBuilders.delete(
                                "/api/v1/clusters/" + clusterId + "/resources/artemis.queue/q?dryRun=true")
                        .with(csrf())
                        .with(authentication(
                                callerWith(Permissions.CLUSTER_READ, ResourcePermissions.SUBSCRIPTION_DELETE))))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

    // ---- the grant does not leak cluster existence -----------------------

    @Test
    void aCallerWithoutTheGrantCannotTellARealClusterFromAnInventedOne() throws Exception {
        UUID invented = UUID.randomUUID();
        // Every grant is on some other cluster, so neither of these two is known to the caller.
        RahdarPrincipal principal = new RahdarPrincipal(
                null,
                "elsewhere",
                Set.of(new Grant(
                        Grant.ScopeType.CLUSTER,
                        UUID.randomUUID(),
                        Set.of(Permissions.CLUSTER_READ, ResourcePermissions.SUBSCRIPTION_CREATE))),
                false);
        var caller = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());

        int real = mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/subscriptions")
                        .with(csrf())
                        .with(authentication(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getStatus();

        int fake = mvc.perform(MockMvcRequestBuilders.post("/api/v1/clusters/" + invented + "/subscriptions")
                        .with(csrf())
                        .with(authentication(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(real).isEqualTo(404);
        assertThat(fake).isEqualTo(real);
    }

    @Test
    void theRightPermissionGetsPastTheGuard() throws Exception {
        // The control: with the grants, the request is no longer a 404 or a 403 — proving the
        // refusals above come from the guard and not from the route being absent.
        int status = mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/clusters/" + clusterId + "/subscriptions?dryRun=true")
                                .with(csrf())
                                .with(authentication(callerWith(
                                        Permissions.CLUSTER_READ,
                                        Permissions.SUBSCRIPTION_READ,
                                        Permissions.DESTINATION_READ,
                                        ResourcePermissions.SUBSCRIPTION_CREATE,
                                        ResourcePermissions.DESTINATION_CREATE)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_BODY))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotIn(403, 404);
    }
}
