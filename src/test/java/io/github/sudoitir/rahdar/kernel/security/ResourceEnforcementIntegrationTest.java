package io.github.sudoitir.rahdar.kernel.security;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.internal.RoleService;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.UserService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.TeamAccessFixture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queue and address operations as the people of two teams on one cluster (team-access spec): what each sees,
 * what a refused resource looks like, composite operations, and where names may be created. Brokers are not
 * reached: every call that passes its checks is a dry run or a read the snapshot answers.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ResourceEnforcementIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    TeamService teamService;

    @Autowired
    RoleService roleService;

    @Autowired
    UserService userService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository brokerNodes;

    @Autowired
    QueueSnapshotUpsert snapshots;

    final JsonMapper json = new JsonMapper();

    MockMvc mvc;
    TeamAccessFixture fixture;
    UUID cluster;
    UUID orders;
    UUID billing;
    UsernamePasswordAuthenticationToken ordersViewer;
    UsernamePasswordAuthenticationToken ordersOperator;
    UsernamePasswordAuthenticationToken platformOperator;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        fixture = new TeamAccessFixture(teamService, roles, users);

        cluster = clusters.save(new ClusterEntity("team-" + UUID.randomUUID(), null, null))
                .getId();
        // A node with a management URL that is not live: a dry run reports it skipped, with no broker to reach.
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                cluster, "n1", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://127.0.0.1:1/console/jolokia");
        UUID nodeId = brokerNodes.save(node).getId();
        snapshots.upsertBatch(List.of(
                row(nodeId, "orders.in", 10),
                row(nodeId, "orders.out", 5),
                row(nodeId, "billing.in", 7),
                row(nodeId, "billing.payments", 3),
                row(nodeId, "misc.audit", 1)));

        orders = fixture.team("orders", cluster, "orders.#");
        billing = fixture.team("billing", cluster, "billing.#");
        ordersViewer = fixture.session(fixture.member(orders, "TEAM_VIEWER"));
        ordersOperator = fixture.session(fixture.member(orders, "TEAM_OPERATOR"));
        platformOperator = platformOperator();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(cluster);
    }

    private QueueRow row(UUID node, String name, long messages) {
        return new QueueRow(cluster, node, name, name, "ANYCAST", true, messages, 0, 0, 0, 0, 0, 0, false);
    }

    /** A user whose only access is the built-in Operator role, granted on every cluster. */
    private UsernamePasswordAuthenticationToken platformOperator() {
        String name = "operator-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        UUID userId = users.save(user).getId();
        userService.addGrant(
                userId,
                new GrantRequest(roles.findByName("OPERATOR").orElseThrow().getId(), "GLOBAL", null));
        return fixture.session(userId);
    }

    private String base() {
        return "/api/v1/clusters/" + cluster;
    }

    private MockHttpServletResponse call(
            MockHttpServletRequestBuilder request, UsernamePasswordAuthenticationToken session) throws Exception {
        return mvc.perform(request.with(authentication(session)).with(csrf()))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse postJson(String path, String body, UsernamePasswordAuthenticationToken session)
            throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body), session);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private List<String> names(MockHttpServletResponse response) throws Exception {
        return body(response)
                .get("data")
                .valueStream()
                .map(r -> r.get("queueName").asString())
                .toList();
    }

    // ---- lists and counts ---------------------------------------------------------------------

    @Test
    void aTeamOnlyUserListsOnlyTheirQueuesAndTheTotalsCountOnlyThose() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/queues"), ordersViewer);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(names(response)).containsExactlyInAnyOrder("orders.in", "orders.out");
        assertThat(body(response).get("count").asLong()).isEqualTo(2);
    }

    @Test
    void aSearchNeverCountsWhatTheCallerCannotRead() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/queues?q=billing"), ordersViewer);

        assertThat(names(response)).isEmpty();
        assertThat(body(response).get("count").asLong()).isZero();
    }

    @Test
    void rowsCarryTheActionsTheCallerHoldsOnThem() throws Exception {
        JsonNode viewerRow = body(call(get(base() + "/queues?q=orders.in"), ordersViewer))
                .get("data")
                .get(0);
        JsonNode operatorRow = body(call(get(base() + "/queues?q=orders.in"), ordersOperator))
                .get("data")
                .get(0);

        assertThat(viewerRow.get("allowedActions").valueStream().map(JsonNode::asString))
                .contains("queue:read", "message:read")
                .doesNotContain("queue:purge");
        assertThat(operatorRow.get("allowedActions").valueStream().map(JsonNode::asString))
                .contains("queue:purge", "queue:update", "queue:delete");
    }

    @Test
    void aClusterWidePlatformGrantSeesEveryQueueAndEveryAction() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/queues"), platformOperator);

        assertThat(names(response))
                .containsExactlyInAnyOrder("orders.in", "orders.out", "billing.in", "billing.payments", "misc.audit");
        assertThat(body(response)
                        .get("data")
                        .get(0)
                        .get("allowedActions")
                        .valueStream()
                        .map(JsonNode::asString))
                .contains("queue:purge", "queue:delete");
    }

    @Test
    void aUserWithNothingOnTheClusterDoesNotFindItEvenToList() throws Exception {
        UsernamePasswordAuthenticationToken stranger = fixture.session(fixture.member(
                fixture.team(
                        "elsewhere",
                        clusters.save(new ClusterEntity("other-" + UUID.randomUUID(), null, null))
                                .getId(),
                        "x.#"),
                "TEAM_VIEWER"));

        assertThat(call(get(base() + "/queues"), stranger).getStatus()).isEqualTo(404);
        assertThat(call(get(base() + "/addresses"), stranger).getStatus()).isEqualTo(404);
    }

    // ---- a refused resource looks like a missing one ------------------------------------------

    @Test
    void aQueueTheCallerCannotReadIsNotFoundExactlyLikeOneThatDoesNotExist() throws Exception {
        MockHttpServletResponse foreign = call(get(base() + "/queues/billing.payments/configuration"), ordersViewer);
        MockHttpServletResponse missing = call(get(base() + "/queues/nowhere.at.all/configuration"), ordersViewer);

        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(body(foreign).get("type")).isEqualTo(body(missing).get("type"));
        assertThat(body(foreign).get("detail").asString())
                .isEqualTo(body(missing).get("detail").asString().replace("nowhere.at.all", "billing.payments"));
    }

    @Test
    void aQueueTheCallerMayReadHasItsConfigurationRead() throws Exception {
        assertThat(call(get(base() + "/queues/orders.in/configuration"), ordersViewer)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aTeamViewerPurgingAReadableQueueIsToldWhichPermissionIsMissing() throws Exception {
        MockHttpServletResponse response =
                call(delete(base() + "/queues/orders.in/messages?dryRun=true"), ordersViewer);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode problem = body(response);
        assertThat(problem.get("type").asString()).endsWith("/resource-forbidden");
        assertThat(problem.get("detail").asString()).contains("queue:purge", "orders.in");
        assertThat(problem.get("permission").asString()).isEqualTo("queue:purge");
    }

    @Test
    void aTeamViewerBrowsingAForeignQueueDoesNotLearnItExists() throws Exception {
        assertThat(call(get(base() + "/queues/billing.in/messages"), ordersViewer)
                        .getStatus())
                .isEqualTo(404);
    }

    // ---- operations touching several resources ----------------------------------------------

    private static final String MOVE_TO_BILLING = "{\"messageIds\":[1],\"targetQueue\":\"billing.in\"}";

    @Test
    void movingToAnotherTeamsQueueIsRefusedBeforeAnythingIsMoved() throws Exception {
        MockHttpServletResponse response = postJson(
                base() + "/queues/orders.in/messages/actions/move?dryRun=true", MOVE_TO_BILLING, ordersOperator);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain("billing.in");
    }

    @Test
    void movingToAQueueTheOtherTeamSharedIsAllowed() throws Exception {
        fixture.share(billing, orders, cluster, "billing.in", "TEAM_OPERATOR");

        MockHttpServletResponse response = postJson(
                base() + "/queues/orders.in/messages/actions/move?dryRun=true", MOVE_TO_BILLING, ordersOperator);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aReadOnlyShareDoesNotAllowSendingToIt() throws Exception {
        fixture.share(billing, orders, cluster, "billing.in", "TEAM_VIEWER");

        MockHttpServletResponse response = postJson(
                base() + "/queues/orders.in/messages/actions/move?dryRun=true", MOVE_TO_BILLING, ordersOperator);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("permission").asString()).isEqualTo("message:send");
    }

    /** A purge plan with the given selection, written the way the console sends one. */
    private static String bulk(String selection) {
        return "{\"operation\":\"PURGE\"," + selection + ",\"disconnectConsumers\":false}";
    }

    @Test
    void aBulkPlanWithAForeignQueueIsRefusedAndNothingIsPlanned() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/bulk/preview", bulk("\"names\":[\"orders.in\",\"billing.in\"]"), ordersOperator);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(call(get(base() + "/bulk/runs"), ordersOperator).getContentAsString())
                .doesNotContain("PURGE");
    }

    @Test
    void aBulkPlanOfTheCallersOwnQueuesIsPlanned() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/bulk/preview", bulk("\"names\":[\"orders.in\",\"orders.out\"]"), ordersOperator);

        assertThat(response.getStatus())
                .describedAs(response.getContentAsString())
                .isEqualTo(201);
    }

    @Test
    void aBulkPlanBySearchNeverReachesQueuesTheCallerCannotRead() throws Exception {
        MockHttpServletResponse response = postJson(base() + "/bulk/preview", bulk("\"q\":\"in\""), ordersOperator);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("orders.in").doesNotContain("billing.in");
    }

    // ---- creating names ------------------------------------------------------------------------

    private static String createQueue(String name) {
        return "{\"address\":\"" + name + "\",\"name\":\"" + name + "\",\"routingType\":\"ANYCAST\"}";
    }

    @Test
    void aTeamOperatorMayCreateAQueueInsideTheTeamsPattern() throws Exception {
        assertThat(postJson(base() + "/queues?dryRun=true", createQueue("orders.retry"), ordersOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void creatingOutsideTheTeamsPatternIsRefusedNamingWhereTheUserMayCreate() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/queues?dryRun=true", createQueue("misc.temp"), ordersOperator);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("detail").asString()).contains("queue:create", "misc.temp", "orders.#");
    }

    @Test
    void aTeamViewerMayNotCreateInsideTheirOwnPatternEither() throws Exception {
        assertThat(postJson(base() + "/queues?dryRun=true", createQueue("orders.retry"), ordersViewer)
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aQueueCannotBeBoundToAnAddressOfAnotherTeam() throws Exception {
        MockHttpServletResponse response = postJson(
                base() + "/queues?dryRun=true",
                "{\"address\":\"billing.in\",\"name\":\"orders.spy\",\"routingType\":\"ANYCAST\"}",
                ordersOperator);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("permission").asString()).isEqualTo("address:create");
    }

    @Test
    void aPlatformOperatorMayCreateAnywhere() throws Exception {
        assertThat(postJson(base() + "/queues?dryRun=true", createQueue("misc.temp"), platformOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void anotherTeamsQueueCannotBeChangedOrDeleted() throws Exception {
        assertThat(call(delete(base() + "/queues/billing.in?dryRun=true"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
        assertThat(postJson(base() + "/queues/billing.in/pause?dryRun=true", "", ordersOperator)
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void anAddressIsDestroyedOnlyWithAddressDelete() throws Exception {
        assertThat(call(delete(base() + "/addresses/orders.in?dryRun=true"), ordersViewer)
                        .getStatus())
                .isEqualTo(403);
        assertThat(call(delete(base() + "/addresses/orders.in?dryRun=true"), ordersOperator)
                        .getStatus())
                .isEqualTo(200);
        assertThat(call(delete(base() + "/addresses/billing.in?dryRun=true"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
    }

    // ---- diverts ---------------------------------------------------------------------------------

    private static String divert(String address, String forwardingAddress) {
        return "{\"name\":\"d1\",\"address\":\"%s\",\"forwardingAddress\":\"%s\"}"
                .formatted(address, forwardingAddress);
    }

    @Test
    void aDivertBetweenTheTeamsOwnAddressesIsAllowed() throws Exception {
        assertThat(postJson(base() + "/diverts?dryRun=true", divert("orders.in", "orders.out"), ordersOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aDivertIntoAnotherTeamsAddressIsNotFoundAndNothingIsDiverted() throws Exception {
        assertThat(postJson(base() + "/diverts?dryRun=true", divert("orders.in", "billing.in"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
        assertThat(postJson(base() + "/diverts?dryRun=true", divert("billing.in", "orders.out"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void aTeamViewerMayNotWriteADivertOnTheirOwnAddresses() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/diverts?dryRun=true", divert("orders.in", "orders.out"), ordersViewer);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("permission").asString()).isEqualTo("divert:write");
    }

    // ---- capture and queries -------------------------------------------------------------------------

    private static String capture(String pattern) {
        return "{\"queuePattern\":\"%s\",\"mode\":\"CAPTURE\"}".formatted(pattern);
    }

    /** A member of Orders in a team role that may also capture. */
    private UsernamePasswordAuthenticationToken ordersCapturer() {
        String role = "capturer-" + UUID.randomUUID();
        roleService.create(new RoleRequest(role, List.of("queue:read", "capture:write"), false, true));
        return fixture.session(fixture.member(orders, role));
    }

    @Test
    void aCaptureOfNamesTheTeamOwnsIsAllowed() throws Exception {
        assertThat(postJson(base() + "/sql/index?dryRun=true", capture("orders.*"), ordersCapturer())
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aTeamOperatorWithoutCaptureWriteMayNotCaptureTheirOwnQueues() throws Exception {
        assertThat(postJson(base() + "/sql/index?dryRun=true", capture("orders.*"), ordersOperator)
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aCaptureThatCouldReachAnotherTeamsNamesIsRefused() throws Exception {
        for (String pattern : List.of("#", "billing.in", "*.in", "orders#")) {
            MockHttpServletResponse response =
                    postJson(base() + "/sql/index?dryRun=true", capture(pattern), ordersOperator);

            assertThat(response.getStatus()).as(pattern).isEqualTo(403);
            assertThat(body(response).get("permission").asString()).isEqualTo("capture:write");
        }
    }

    @Test
    void aPlatformOperatorMayCaptureWhatTheirGrantReaches() throws Exception {
        assertThat(postJson(base() + "/sql/index?dryRun=true", capture("#"), platformOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aQueryOfAnotherTeamsQueuesIsRefusedAndOneOfTheirOwnIsPlanned() throws Exception {
        assertThat(postJson(base() + "/sql/plan", "{\"sql\":\"SELECT * FROM \\\"billing.in\\\"\"}", ordersViewer)
                        .getStatus())
                .isEqualTo(403);
        assertThat(postJson(base() + "/sql/plan", "{\"sql\":\"SELECT * FROM \\\"#\\\"\"}", ordersViewer)
                        .getStatus())
                .isEqualTo(403);
        assertThat(postJson(base() + "/sql/plan", "{\"sql\":\"SELECT * FROM \\\"orders.in\\\"\"}", ordersViewer)
                        .getStatus())
                .isEqualTo(200);
    }

    // ---- request-reply ---------------------------------------------------------------------------------

    private static String expectation(String address) {
        return "{\"requestAddress\":\"%s\",\"samplePerMin\":10,\"capturePayload\":false}".formatted(address);
    }

    @Test
    void tracingNeedsRrWriteWhichATeamNeverGrants() throws Exception {
        assertThat(postJson(base() + "/rr/expectations", expectation("orders.in"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void expectationsAreListedOnlyForAddressesTheCallerMayRead() throws Exception {
        assertThat(postJson(base() + "/rr/expectations", expectation("orders.in"), platformOperator)
                        .getStatus())
                .isEqualTo(201);
        assertThat(postJson(base() + "/rr/expectations", expectation("billing.in"), platformOperator)
                        .getStatus())
                .isEqualTo(201);

        JsonNode listed = body(call(get(base() + "/rr/expectations"), ordersViewer));

        assertThat(listed.get("data")
                        .valueStream()
                        .map(e -> e.get("requestAddress").asString()))
                .containsExactly("orders.in");
    }

    // ---- flow, triage, events, metrics, audit -----------------------------------------------------------

    @Test
    void theFlowGraphDrawsOnlyWhatTheCallerMayRead() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/flow"), ordersViewer);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("orders.in").doesNotContain("billing");
    }

    @Test
    void consumerHealthRanksOnlyTheCallersQueues() throws Exception {
        assertThat(names(call(get(base() + "/consumer-health"), ordersViewer)))
                .containsExactlyInAnyOrder("orders.in", "orders.out");
        assertThat(call(get(base() + "/consumer-health?queue=billing.in"), ordersViewer)
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void eventsAboutOtherAddressesAreNeitherListedNorCounted() throws Exception {
        event("orders.in");
        event("billing.in");
        event(null);

        JsonNode page = body(call(get(base() + "/events"), ordersViewer));

        assertThat(page.get("data").valueStream().map(e -> e.get("address").asString()))
                .containsExactly("orders.in");
        assertThat(page.get("count").asLong()).isEqualTo(1);
        assertThat(body(call(get(base() + "/events"), platformOperator))
                        .get("count")
                        .asLong())
                .isEqualTo(3);
    }

    @Test
    void aSeriesIsReadPerQueueAndTheClusterTotalNeedsEveryQueue() throws Exception {
        assertThat(call(get(base() + "/metrics?metric=messageCount&subjectType=QUEUE&subject=orders.in"), ordersViewer)
                        .getStatus())
                .isEqualTo(200);
        assertThat(call(get(base() + "/metrics?metric=messageCount&subjectType=QUEUE&subject=billing.in"), ordersViewer)
                        .getStatus())
                .isEqualTo(404);
        assertThat(call(get(base() + "/metrics?metric=messageCount"), ordersViewer)
                        .getStatus())
                .isEqualTo(403);
        assertThat(call(get(base() + "/metrics?metric=messageCount"), platformOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theAuditTrailShowsOnlyEventsAboutWhatTheCallerMayRead() throws Exception {
        audited("QUEUE", "orders.in");
        audited("QUEUE", "billing.in");
        audited("ADDRESS", "billing.in");
        audited("CLUSTER", "prod");

        JsonNode mine = body(call(get(base() + "/audit"), ordersViewer));

        assertThat(mine.get("data").valueStream().map(e -> e.get("targetName").asString()))
                .containsExactly("orders.in");
        assertThat(mine.get("count").asLong()).isEqualTo(1);
        assertThat(body(call(get(base() + "/audit"), platformOperator))
                        .get("count")
                        .asLong())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void aRunOfAnotherTeamsQueuesIsNotFoundByItsOwnIdWithoutNamingAQueue() throws Exception {
        MockHttpServletResponse planned =
                postJson(base() + "/bulk/preview", bulk("\"names\":[\"orders.in\",\"billing.in\"]"), platformOperator);
        String run = body(planned).get("run").get("id").asString();

        for (MockHttpServletResponse response : List.of(
                call(get(base() + "/bulk/runs/" + run), ordersViewer),
                postJson(base() + "/bulk/runs/" + run + "/stop", "", ordersOperator))) {
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(body(response).get("detail").asString())
                    .isEqualTo("bulk run " + run + " does not exist.")
                    .doesNotContain("billing");
        }
    }

    @Test
    void anAuditEventDoesNotNameAQueueTheReaderMayNotReadInItsParameters() throws Exception {
        audited("QUEUE", "orders.in", Map.of("target", "billing.in"));
        audited("QUEUE", "orders.out", Map.of("target", "orders.in"));

        String trail = call(get(base() + "/audit"), ordersViewer).getContentAsString();

        assertThat(trail).doesNotContain("billing.in").contains("orders.in");
        assertThat(call(get(base() + "/audit"), platformOperator).getContentAsString())
                .contains("billing.in");
    }

    @Test
    void anEventThatNamesAnotherTeamsQueueIsLeftOut() throws Exception {
        event("orders.in", "billing.in");
        event("orders.in", "orders.out");

        JsonNode page = body(call(get(base() + "/events"), ordersViewer));

        assertThat(page.get("data").valueStream().map(e -> e.get("routingName").asString()))
                .containsExactly("orders.out");
        assertThat(page.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void aCallerWhoSeesTheClusterOnlyThroughATeamIsNotToldWhereItsNodesAreReached() throws Exception {
        for (String path : List.of("/api/v1/clusters/" + cluster, base() + "/topology")) {
            String team = call(get(path), ordersViewer).getContentAsString();
            String platform = call(get(path), platformOperator).getContentAsString();

            assertThat(team).as(path).doesNotContain("127.0.0.1");
            assertThat(platform).as(path).contains("127.0.0.1");
        }
    }

    // ---- alert rules -------------------------------------------------------------------------------------

    /** A user with alert:read and alert:write on the cluster through a grant, and a Team Operator role in Orders. */
    private UsernamePasswordAuthenticationToken ordersAlerter() {
        UUID role = roleService
                .create(new RoleRequest(
                        "alerter-" + UUID.randomUUID(), List.of("alert:read", "alert:write"), false, false))
                .id();
        UUID user = fixture.member(orders, "TEAM_OPERATOR");
        userService.addGrant(user, new GrantRequest(role, "CLUSTER", cluster));
        return fixture.session(user);
    }

    private static String rule(String name, String queuePattern) {
        return "{\"name\":\"%s\",\"kind\":\"METRIC_THRESHOLD\",\"metric\":\"messageCount\",\"comparator\":\"GT\",\"threshold\":10,\"forSeconds\":0,\"severity\":\"WARNING\",\"scope\":\"{\\\"queuePattern\\\":\\\"%s\\\"}\",\"enabled\":true}"
                .formatted(name, queuePattern);
    }

    @Test
    void aRuleWatchesOnlyQueuesItsAuthorMayRead() throws Exception {
        UsernamePasswordAuthenticationToken alerter = ordersAlerter();

        assertThat(postJson(base() + "/alerts/rules", rule("mine", "orders.*"), alerter)
                        .getStatus())
                .isEqualTo(201);
        MockHttpServletResponse refused = postJson(base() + "/alerts/rules", rule("theirs", "billing.*"), alerter);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("permission").asString()).isEqualTo("queue:read");
        assertThat(postJson(base() + "/alerts/rules", rule("everything", "*"), alerter)
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aRuleOnQueuesTheCallerMayNotReadIsNeitherListedNorChangeable() throws Exception {
        UsernamePasswordAuthenticationToken alerter = ordersAlerter();
        assertThat(postJson(base() + "/alerts/rules", rule("mine", "orders.*"), platformOperator)
                        .getStatus())
                .isEqualTo(201);
        MockHttpServletResponse theirs =
                postJson(base() + "/alerts/rules", rule("theirs", "billing.*"), platformOperator);
        UUID theirRule = UUID.fromString(body(theirs).get("id").asString());

        JsonNode listed = body(call(get(base() + "/alerts/rules"), alerter));

        assertThat(listed.get("data").valueStream().map(r -> r.get("name").asString()))
                .containsExactly("mine");
        assertThat(call(delete(base() + "/alerts/rules/" + theirRule), alerter).getStatus())
                .isEqualTo(404);
    }

    // ---- refusals are audited ----------------------------------------------------------------------------

    private List<JsonNode> refusals(UsernamePasswordAuthenticationToken session) throws Exception {
        return body(call(get(base() + "/audit?outcome=REFUSED"), session))
                .get("data")
                .valueStream()
                .toList();
    }

    @Test
    void aRefusedPurgeIsAnAuditEventNamingThePermissionAndTheQueue() throws Exception {
        assertThat(call(delete(base() + "/queues/orders.in/messages?dryRun=true"), ordersViewer)
                        .getStatus())
                .isEqualTo(403);

        List<JsonNode> events = refusals(platformOperator);

        assertThat(events).hasSize(1);
        JsonNode refused = events.get(0);
        assertThat(refused.get("action").asString()).isEqualTo("ACCESS_REFUSED");
        assertThat(refused.get("targetType").asString()).isEqualTo("QUEUE");
        assertThat(refused.get("targetName").asString()).isEqualTo("orders.in");
        assertThat(refused.get("username").asString()).isEqualTo(ordersViewerName());
        assertThat(json.readTree(refused.get("params").asString())
                        .get("permission")
                        .asString())
                .isEqualTo("queue:purge");
        assertThat(json.readTree(refused.get("params").asString())
                        .get("visibility")
                        .asString())
                .isEqualTo("forbidden");
    }

    @Test
    void aQueueHiddenFromTheCallerIsRecordedAsHidden() throws Exception {
        assertThat(call(delete(base() + "/queues/billing.in/messages?dryRun=true"), ordersViewer)
                        .getStatus())
                .isEqualTo(404);

        List<JsonNode> events = refusals(platformOperator);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("targetName").asString()).isEqualTo("billing.in");
        assertThat(json.readTree(events.get(0).get("params").asString())
                        .get("visibility")
                        .asString())
                .isEqualTo("hidden");
    }

    @Test
    void theSameRefusalRepeatedIsOneEventWithACount() throws Exception {
        for (int i = 0; i < 50; i++) {
            call(delete(base() + "/queues/orders.in/messages?dryRun=true"), ordersViewer);
        }

        List<JsonNode> events = refusals(platformOperator);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("affectedCount").asLong()).isEqualTo(50);
    }

    @Test
    void aRefusedBrokerChangeLeavesOneRefusedEventNamingThePermission() throws Exception {
        assertThat(postJson(base() + "/queues/orders.in/pause?dryRun=true", "", ordersViewer)
                        .getStatus())
                .isEqualTo(403);

        List<JsonNode> events = refusals(platformOperator);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("targetName").asString()).isEqualTo("orders.in");
        assertThat(events.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(json.readTree(events.get(0).get("params").asString())
                        .get("permission")
                        .asString())
                .isEqualTo("queue:pause");
    }

    @Test
    void aTeamMemberSeesTheRefusalsAboutTheirOwnQueuesOnly() throws Exception {
        call(delete(base() + "/queues/orders.in/messages?dryRun=true"), ordersViewer);
        call(delete(base() + "/queues/billing.in/messages?dryRun=true"), ordersViewer);

        assertThat(refusals(ordersViewer))
                .extracting(e -> e.get("targetName").asString())
                .containsExactly("orders.in");
        assertThat(refusals(platformOperator)).hasSize(2);
    }

    private String ordersViewerName() {
        return ((RahdarPrincipal) ordersViewer.getPrincipal()).getUsername();
    }

    // ---- helpers for the above ---------------------------------------------------------------------------

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditService audit;

    private void event(String address) {
        event(address, null);
    }

    private void event(String address, String routingName) {
        jdbc.update(
                "INSERT INTO broker_event (occurred_at, type, destination, subscription, cluster_id) VALUES (now(), 'openbroker.consumer.slow', ?, ?, ?)",
                address,
                routingName,
                cluster);
    }

    private void audited(String targetType, String targetName) {
        audited(targetType, targetName, Map.of());
    }

    private void audited(String targetType, String targetName, Map<String, String> params) {
        AuditEvent event = audit.begin(
                new Actor("alice", "127.0.0.1", "req", null),
                "TEST",
                targetType,
                targetName,
                cluster,
                null,
                params,
                false);
        audit.succeed(event, 1);
    }
}
