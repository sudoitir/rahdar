package io.github.sudoitir.rahdar.kernel.security;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The authorization counterpart to {@link EndpointProtectionTest}. That test
 * proves an <em>anonymous</em> request is rejected; this one proves an
 * <em>authenticated</em> caller cannot read a cluster it holds no grant on.
 *
 * <p>Both are needed, and the gap between them is not hypothetical: five
 * services ({@code CrossNodeAggregator}, {@code PagedListService},
 * {@code MetricQueryService}, {@code AuditQueryService},
 * {@code BrokerEventService}) once shipped with no scope check at all, so any
 * authenticated user could read any cluster's queues, metrics, audit trail, and
 * broker events. Authentication was tested; authorization was not.
 *
 * <p>Like its sibling, this reflects over every registered endpoint rather than
 * a hand-maintained list, so a new cluster-addressed controller is covered the
 * day it is written.
 *
 * <p>The assertion is {@code 404}, not {@code 403}, per
 * {@code ClusterAccessGuard}: revealing whether a cluster id exists to someone
 * with no grant on it would leak what the authorization spec says must stay
 * hidden. Each endpoint is called twice — once for a cluster the caller <em>can</em>
 * see and once for a cluster it cannot — because a bare "B returns 404" would
 * also pass if the endpoint 404'd for some unrelated reason. Only the pair
 * proves the guard is what made the difference.
 */
class ClusterScopeAuthorizationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository brokerNodes;

    /** Stands in for every non-cluster path variable: a valid UUID and a valid name. */
    private static final UUID PLACEHOLDER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /**
     * Endpoints addressed by a sub-resource the fixture does not create (a queue,
     * a message, a flow), so they return {@code 404} for absence even on the
     * granted cluster and the control call cannot prove the guard is what produced
     * the other {@code 404}.
     *
     * <p>They are still covered by the leak assertion — a missing guard would have
     * to turn the no-grant call into something other than {@code 404} to pass — but
     * the proof is weaker here, and saying so is better than a fixture that
     * pretends otherwise. Seeding a real queue, message, and flow would make these
     * exact; that is the upgrade when one of them is next touched.
     */
    private static final Set<String> NO_CONTROL_POSSIBLE = Set.of(
            "/api/v1/clusters/{clusterId}/queues/{queueName}/messages",
            "/api/v1/clusters/{clusterId}/queues/{queueName}/messages/{messageId}",
            // Queue-addressed, and the fixture has no queue: the locator finds none on
            // any node and the read is a 404 for absence, with or without a grant.
            "/api/v1/clusters/{clusterId}/queues/{queueName}/configuration",
            "/api/v1/clusters/{clusterId}/rr/flows/{flowId}",
            // A declaration revision and an apply record the fixture does not create.
            "/api/v1/clusters/{clusterId}/config/revisions/{number}",
            "/api/v1/clusters/{clusterId}/config/applies/{id}",
            // A bulk run the fixture does not create.
            "/api/v1/clusters/{clusterId}/bulk/runs/{runId}",
            // A transfer run the fixture does not create.
            "/api/v1/clusters/{clusterId}/transfers/runs/{runId}",
            // A broker event and an audit event the fixture does not create.
            "/api/v1/clusters/{clusterId}/events/{seq}",
            "/api/v1/clusters/{clusterId}/audit/{id}");

    private UUID visible;
    private UUID hidden;

    /**
     * A real node on the granted cluster. Node-addressed reads would otherwise
     * {@code 404} for absence even with a grant, and their control call could not
     * tell "no grant" from "no such node" — the weakness the exemption list above
     * describes. Seeding one is the upgrade that doc asks for, taken here because
     * this change adds the first node-addressed read.
     */
    private UUID visibleNode;

    @AfterEach
    void cleanUp() {
        if (visible != null) {
            clusters.deleteById(visible);
        }
        if (hidden != null) {
            clusters.deleteById(hidden);
        }
        visibleNode = null;
    }

    @Test
    void aClusterAddressedReadIsInvisibleWithoutAGrantOnThatCluster() throws Exception {
        // Two real clusters. Both must exist, or every endpoint would 404 for
        // absence rather than for authorization and the test would pass vacuously.
        visible = clusters.save(new ClusterEntity("visible-" + UUID.randomUUID(), null, null))
                .getId();
        hidden = clusters.save(new ClusterEntity("hidden-" + UUID.randomUUID(), null, null))
                .getId();
        // No management URL on purpose: the read then answers "unavailable, and here
        // is why" with a 200 rather than reaching for a broker that is not there,
        // which is exactly the control this needs.
        visibleNode = brokerNodes
                .save(BrokerNodeEntity.fromSeed(
                        visible, "node-a", "STANDALONE", UUID.randomUUID().toString()))
                .getId();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        // Every read permission, but scoped to ONE cluster. The point under test is
        // the scope, not the permission: with a narrower set, endpoints gated on
        // message:read or alert:read would 404 for the granted cluster too and the
        // control below could not tell "no grant" from "wrong permission".
        RahdarPrincipal scoped = new RahdarPrincipal(
                null,
                "scoped-user",
                Set.of(new Grant(
                        Grant.ScopeType.CLUSTER,
                        visible,
                        Set.of(
                                Permissions.CLUSTER_READ,
                                MessagePermissions.MESSAGE_READ,
                                AlertPermissions.ALERT_READ,
                                SettingsPermissions.SETTINGS_READ,
                                ClusterPermissions.ENVIRONMENT_READ))),
                false);
        var auth = UsernamePasswordAuthenticationToken.authenticated(scoped, null, scoped.getAuthorities());

        List<String> leaked = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            // Reads only. A mutating endpoint's guard deserves the same coverage but
            // cannot be probed by firing it at a live fixture.
            if (!methods.isEmpty() && !methods.contains(RequestMethod.GET)) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/v1/clusters/{clusterId}")) {
                    continue;
                }
                int hiddenStatus = statusFor(mvc, pattern, hidden, auth, PLACEHOLDER);
                if (hiddenStatus != 404) {
                    leaked.add("GET " + pattern + " -> " + hiddenStatus + " for a cluster with no grant");
                    continue;
                }
                // The control: the same call against a cluster the caller CAN see must
                // not 404, or the 404 above proves nothing about the guard.
                int visibleStatus = statusFor(mvc, pattern, visible, auth, visibleNode);
                if (visibleStatus == 404 && !NO_CONTROL_POSSIBLE.contains(pattern)) {
                    inconclusive.add("GET " + pattern + " -> 404 even for a granted cluster");
                }
            }
        }

        assertThat(leaked)
                .describedAs("cluster-addressed reads must be invisible without a grant on that cluster")
                .isEmpty();
        assertThat(inconclusive)
                .describedAs("these endpoints 404 regardless of grant, so they prove nothing — fix the fixture")
                .isEmpty();
    }

    /**
     * Query strings for endpoints with a required parameter. Without these the
     * request fails argument binding with a {@code 400} and never reaches the
     * guard, which would make the probe meaningless. A new endpoint with a
     * required parameter shows up as a {@code 400} in the failure list above —
     * that is the signal to add a row here, not to relax the assertion.
     */
    private static final Map<String, String> REQUIRED_PARAMS =
            Map.of("/api/v1/clusters/{clusterId}/metrics", "?metric=openbroker.backlog");

    private static int statusFor(
            MockMvc mvc, String pattern, UUID clusterId, UsernamePasswordAuthenticationToken auth, UUID nodeId)
            throws Exception {
        String path = pattern.replace("{clusterId}", clusterId.toString())
                // A node that really exists on the granted cluster, so a node-addressed
                // read's control call is not a 404 for absence.
                .replace("{nodeId}", nodeId.toString())
                // The numeric path variables; they must parse as a number or binding
                // fails before the guard runs. Not derivable from the name (the flow id
                // is a UUID), so they are listed rather than pattern-matched.
                .replace("{messageId}", "1")
                .replace("{number}", "1")
                .replace("{seq}", "1")
                .replace("{id}", "1")
                // Everything else takes a UUID: parses as a UUID where one is expected,
                // and is a harmless string where a name is.
                .replaceAll("\\{[^}]+}", PLACEHOLDER.toString());
        return mvc.perform(
                        MockMvcRequestBuilders.request(HttpMethod.GET, path + REQUIRED_PARAMS.getOrDefault(pattern, ""))
                                .with(authentication(auth)))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
