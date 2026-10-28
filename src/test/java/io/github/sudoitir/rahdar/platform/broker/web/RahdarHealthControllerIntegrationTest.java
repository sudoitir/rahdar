package io.github.sudoitir.rahdar.platform.broker.web;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import io.github.sudoitir.rahdar.platform.broker.NodeCallHealth;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/v1/system/health} against the real security chain: settings-read only, a node
 * that has never been measured reads as unavailable and never as zero, and a failure message is
 * redacted.
 */
class RahdarHealthControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NodeCallHealth calls;

    @Autowired
    ReplicaRegistry replicas;

    @Autowired
    JdbcTemplate jdbc;

    private MockMvc mvc;
    private UUID clusterId;
    private final List<UUID> replicaRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        replicaRows.forEach(id -> jdbc.update("DELETE FROM rahdar_replica WHERE id = ?", id));
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private static UsernamePasswordAuthenticationToken callerWith(String... permissions) {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "reader", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(permissions))), false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @Test
    void aCallerWithoutSettingsReadIsRefused() throws Exception {
        mvc.perform(get("/api/v1/system/health").with(authentication(callerWith("cluster:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnmeasuredNodeIsUnavailableNotZeroAndAFailureIsRedacted() throws Exception {
        String url = "http://health-" + UUID.randomUUID() + ":8161/console/jolokia";
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "health-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "quiet", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl(url);
        nodes.save(node);
        calls.failed(url, "login failed, password=hunter2");

        mvc.perform(get("/api/v1/system/health").with(authentication(callerWith(SettingsPermissions.SETTINGS_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.nodes[?(@.name=='quiet')].managementP95Millis")
                        .value(Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.nodes[?(@.name=='quiet')].lastError")
                        .value(Matchers.contains(Matchers.not(Matchers.containsString("hunter2")))))
                .andExpect(jsonPath("$.dbPool.max").isNumber());
    }

    private void replica(String state, String stopped, String heartbeatAge) {
        UUID id = UUID.randomUUID();
        replicaRows.add(id);
        jdbc.update(
                "INSERT INTO rahdar_replica (started_at, heartbeat_at, stopped_at, host, version, state, id)"
                        + " VALUES (now() - interval '1 day', now() - ?::interval, " + stopped
                        + ", ?, 'test', ?, ?)",
                heartbeatAge,
                "host-" + state + "-" + heartbeatAge.replace(' ', '-'),
                state,
                id);
    }

    @Test
    void replicasAreListedWithTheirClustersTheAnsweringOneIsMarkedAndAGoneOneIsDegraded() throws Exception {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "owned-" + UUID.randomUUID(), null, null))
                .getId();
        jdbc.update(
                "INSERT INTO cluster_lease (expires_at, replica_id, cluster_id) VALUES (now() + interval '1 minute', ?, ?)",
                replicas.id(),
                clusterId);
        replica("ready", "NULL", "1 minute");
        replica("draining", "NULL", "1 second");
        replica("stopped", "now()", "2 minutes");
        replica("ready", "NULL", "1 hour");

        mvc.perform(get("/api/v1/system/health").with(authentication(callerWith(SettingsPermissions.SETTINGS_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeringReplica").value(replicas.id().toString()))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.replicas[?(@.self==true)].id")
                        .value(Matchers.contains(replicas.id().toString())))
                .andExpect(jsonPath("$.replicas[?(@.self==true)].ownedClusters[0].id")
                        .value(Matchers.contains(clusterId.toString())))
                .andExpect(jsonPath("$.replicas[?(@.host=='host-ready-1-minute')].state")
                        .value(Matchers.contains("GONE")))
                .andExpect(jsonPath("$.replicas[?(@.host=='host-ready-1-minute')].degraded")
                        .value(Matchers.contains(true)))
                .andExpect(jsonPath("$.replicas[?(@.host=='host-draining-1-second')].state")
                        .value(Matchers.contains("DRAINING")))
                .andExpect(jsonPath("$.replicas[?(@.host=='host-stopped-2-minutes')].state")
                        .value(Matchers.contains("STOPPED")))
                .andExpect(jsonPath("$.replicas[?(@.host=='host-stopped-2-minutes')].degraded")
                        .value(Matchers.contains(false)))
                .andExpect(
                        jsonPath("$.replicas[?(@.host=='host-ready-1-hour')]").isEmpty());
    }
}
