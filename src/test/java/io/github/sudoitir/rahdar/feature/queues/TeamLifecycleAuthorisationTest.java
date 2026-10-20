package io.github.sudoitir.rahdar.feature.queues;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.TeamAccessFixture;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A divert is destroyed wherever it is deployed, so a team may delete it only when it runs between addresses of
 * theirs on every node, and a divert that is anyone else's is not found, like one that does not exist. A refusal to
 * delete an address names only the queues the team may read.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class TeamLifecycleAuthorisationTest extends PostgresIntegrationTest {

    private static final String ONE = "http://one:8161/console/jolokia";
    private static final String TWO = "http://two:8161/console/jolokia";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    TeamService teamService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository brokerNodes;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    @MockitoBean
    DivertOperations divertOps;

    @MockitoBean
    QueueLifecycleOperations ops;

    final JsonMapper json = new JsonMapper();
    final ArtemisNode clientOne = mock(ArtemisNode.class);
    final ArtemisNode clientTwo = mock(ArtemisNode.class);

    MockMvc mvc;
    UUID cluster;
    UsernamePasswordAuthenticationToken ordersOperator;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        TeamAccessFixture fixture = new TeamAccessFixture(teamService, roles, users);
        cluster = clusters.save(new ClusterEntity("divert-" + UUID.randomUUID(), null, null))
                .getId();
        node("one", ONE);
        node("two", TWO);
        when(artemisNodes.on(eq(cluster), any(UUID.class))).thenReturn(clientOne);
        when(artemisNodes.on(eq(cluster), any(UUID.class))).thenReturn(clientTwo);
        when(clientOne.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"one\"");
        when(clientTwo.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"two\"");
        UUID orders = fixture.team("orders", cluster, "orders.#");
        fixture.team("billing", cluster, "billing.#");
        ordersOperator = fixture.session(fixture.member(orders, "TEAM_OPERATOR"));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(cluster);
    }

    private void node(String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                cluster, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        brokerNodes.save(n);
    }

    private static DivertRow divert(String from, String to) {
        return new DivertRow(null, null, "feed", "feed", from, to, null, "STRIP", null, Map.of(), false, false);
    }

    private MockHttpServletResponse deleteDivert(String name) throws Exception {
        return mvc.perform(delete("/api/v1/clusters/" + cluster + "/diverts/" + name + "?dryRun=true")
                        .with(authentication(ordersOperator))
                        .with(csrf()))
                .andReturn()
                .getResponse();
    }

    @Test
    void aDivertThatRunsBetweenTheTeamsAddressesOnEveryNodeMayBeDeleted() throws Exception {
        when(divertOps.find(clientOne, "feed")).thenReturn(Optional.of(divert("orders.in", "orders.out")));
        when(divertOps.find(clientTwo, "feed")).thenReturn(Optional.of(divert("orders.in", "orders.out")));

        assertThat(deleteDivert("feed").getStatus()).isEqualTo(200);
    }

    @Test
    void aSameNamedDivertOfAnotherTeamOnAnotherNodeIsNotFoundAndIsNeverDestroyed() throws Exception {
        when(divertOps.find(clientOne, "feed")).thenReturn(Optional.of(divert("orders.in", "orders.out")));
        when(divertOps.find(clientTwo, "feed")).thenReturn(Optional.of(divert("billing.in", "billing.out")));

        MockHttpServletResponse response = deleteDivert("feed");

        assertThat(response.getStatus()).isEqualTo(404);
        verify(divertOps, never()).destroyDivert(any(), any(), any());
    }

    @Test
    void whatIsNotFoundForAnUnreadableDivertIsWhatAMissingOneSays() throws Exception {
        when(divertOps.find(any(), eq("feed"))).thenReturn(Optional.of(divert("billing.in", "orders.out")));
        when(divertOps.find(any(), eq("nothing"))).thenReturn(Optional.empty());

        MockHttpServletResponse unreadable = deleteDivert("feed");
        MockHttpServletResponse missing = deleteDivert("nothing");

        assertThat(unreadable.getStatus()).isEqualTo(404);
        assertThat(json.readTree(unreadable.getContentAsString()).get("detail").asString())
                .isEqualTo("divert feed does not exist.");
        assertThat(json.readTree(missing.getContentAsString()).get("detail").asString())
                .isEqualTo("divert nothing does not exist.");
    }

    @Test
    void anAddressThatStillHasQueuesBoundNamesOnlyTheTeamsAndCountsTheRest() throws Exception {
        when(divertOps.listDiverts(any(), any(), any())).thenReturn(List.of());
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.BOUND_QUEUES, "bound"))
                .when(ops)
                .deleteAddress(any(), any(), eq("orders.in"));
        when(ops.boundQueues(any(), any())).thenReturn(List.of("orders.a", "billing.x", "billing.y"));

        MockHttpServletResponse response = mvc.perform(delete("/api/v1/clusters/" + cluster + "/addresses/orders.in")
                        .with(authentication(ordersOperator))
                        .with(csrf()))
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString())
                .contains("3 queue(s) bound to it: orders.a and 2 others")
                .doesNotContain("billing");
    }
}
