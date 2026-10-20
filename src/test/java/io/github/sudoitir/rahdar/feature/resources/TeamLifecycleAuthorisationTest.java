package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import io.github.sudoitir.rahdar.support.TeamAccessFixture;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    BrokerGateway gateway;

    final JsonMapper json = new JsonMapper();
    BrokerTarget targetOne;
    BrokerTarget targetTwo;

    MockMvc mvc;
    UUID cluster;
    UsernamePasswordAuthenticationToken ordersOperator;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        TeamAccessFixture fixture = new TeamAccessFixture(teamService, roles, users);
        cluster = clusters.save(new ClusterEntity("divert-" + UUID.randomUUID(), null, null))
                .getId();
        UUID one = node("one", ONE);
        UUID two = node("two", TWO);
        targetOne = Targets.target();
        targetTwo = Targets.target();
        when(gateway.cluster(cluster)).thenReturn(targetOne.connection());
        when(gateway.node(cluster, one)).thenReturn(targetOne);
        when(gateway.node(cluster, two)).thenReturn(targetTwo);
        UUID orders = fixture.team("orders", cluster, "orders.#");
        fixture.team("billing", cluster, "billing.#");
        ordersOperator = fixture.session(fixture.member(orders, "TEAM_OPERATOR"));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(cluster);
    }

    private UUID node(String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                cluster, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        return brokerNodes.save(n).getId();
    }

    private static Resource divert(String from, String to) {
        return Targets.divert("feed", from, to);
    }

    private MockHttpServletResponse deleteDivert(String name) throws Exception {
        return mvc.perform(delete("/api/v1/clusters/" + cluster + "/artemis/diverts/" + name + "?dryRun=true")
                        .with(authentication(ordersOperator))
                        .with(csrf()))
                .andReturn()
                .getResponse();
    }

    @Test
    void aDivertThatRunsBetweenTheTeamsAddressesOnEveryNodeMayBeDeleted() throws Exception {
        Targets.holding(targetOne, divert("orders.in", "orders.out"));
        Targets.holding(targetTwo, divert("orders.in", "orders.out"));

        assertThat(deleteDivert("feed").getStatus()).isEqualTo(200);
    }

    @Test
    void aSameNamedDivertOfAnotherTeamOnAnotherNodeIsNotFoundAndIsNeverDestroyed() throws Exception {
        Targets.holding(targetOne, divert("orders.in", "orders.out"));
        Targets.holding(targetTwo, divert("billing.in", "billing.out"));

        MockHttpServletResponse response = deleteDivert("feed");

        assertThat(response.getStatus()).isEqualTo(404);
        verify(Targets.operations(targetOne), never()).execute(any(), any(), any());
        verify(Targets.operations(targetTwo), never()).execute(any(), any(), any());
    }

    @Test
    void whatIsNotFoundForAnUnreadableDivertIsWhatAMissingOneSays() throws Exception {
        Targets.holding(targetOne, divert("billing.in", "orders.out"));
        Targets.holding(targetTwo, divert("billing.in", "orders.out"));

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
        for (BrokerTarget target : List.of(targetOne, targetTwo)) {
            when(Targets.operations(target).execute(eq(Targets.id("artemis.address", "orders.in")), any(), any()))
                    .thenThrow(Targets.failure(ErrorReasons.HAS_CHILDREN, Map.of(), "bound"));
            Targets.holding(
                    target,
                    Targets.queue("orders.a", "orders.in", 0, 0),
                    Targets.queue("billing.x", "orders.in", 0, 0),
                    Targets.queue("billing.y", "orders.in", 0, 0));
        }

        MockHttpServletResponse response = mvc.perform(
                        delete("/api/v1/clusters/" + cluster + "/resources/artemis.address/orders.in")
                                .with(authentication(ordersOperator))
                                .with(csrf()))
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString())
                .contains("3 queue(s) bound to it: orders.a and 2 others")
                .doesNotContain("billing");
    }
}
