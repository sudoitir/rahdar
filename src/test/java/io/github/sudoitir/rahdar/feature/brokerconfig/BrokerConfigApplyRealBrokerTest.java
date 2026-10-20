package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The apply the Configuration screen sends, against a real Artemis: every section of a
 * declaration reaches the broker, and applying it again writes nothing.
 *
 * <p>The real run posts without {@code dryRun}, the way the screen's Apply did before the
 * query helper spelled out {@code dryRun=false}. The endpoint used to default that to a
 * preview, so the operator's Apply wrote nothing and reported nothing.
 *
 * <p>An existing queue or address that differs from the declaration converges through
 * the apply (ADR-0082) rather than staying drift the apply cannot close.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class BrokerConfigApplyRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    BrokerConfigService configs;

    @Autowired
    BrokerConfigOperations ops;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    ObjectMapper mapper;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String orders = "C1.REAL." + run + ".ORDERS";
    private final String audit = "C1.REAL." + run + ".AUDIT";
    private final String auditQueue = "C1.REAL." + run + ".AUDIT.KEEP";
    private final String match = "C1.REAL." + run + ".#";
    private final String divert = "c1-real-" + run;
    private final String events = "C3.REAL." + run + ".EVENTS";
    private final String fanout = "C3.REAL." + run + ".FANOUT";
    private final String mixed = "C3.REAL." + run + ".MIXED";
    private final String mixedWork = "C3.REAL." + run + ".MIXED.WORK";

    private MockMvc mvc;
    private UUID clusterId;
    private ArtemisNode client;
    private String broker;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "config-real-" + run,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
    }

    @AfterEach
    void cleanUp() {
        quietly(() -> divertOps.destroyDivert(client, broker, divert));
        quietly(() -> queueOps.destroyQueue(client, broker, orders, false));
        quietly(() -> queueOps.deleteAddress(client, broker, orders));
        quietly(() -> queueOps.destroyQueue(client, broker, auditQueue, false));
        quietly(() -> queueOps.deleteAddress(client, broker, audit));
        quietly(() -> ops.removeAddressSettings(client, broker, match));
        quietly(() -> ops.removeSecuritySettings(client, broker, match));
        quietly(() -> queueOps.destroyQueue(client, broker, events, false));
        quietly(() -> queueOps.deleteAddress(client, broker, events));
        quietly(() -> queueOps.deleteAddress(client, broker, fanout));
        quietly(() -> queueOps.destroyQueue(client, broker, mixedWork, false));
        quietly(() -> queueOps.deleteAddress(client, broker, mixed));
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    @Test
    void anApplyWithoutDryRunWritesEverySectionAndASecondApplyFindsNothingToDo() throws Exception {
        configs.save(clusterId, document(), null, "c1", BrokerConfigService.Source.EDIT);

        JsonNode preview = apply("?dryRun=true", "{}");
        assertThat(preview.path("outcome").asString()).isEqualTo("DRY_RUN");
        List<String> acknowledge = StreamSupport.stream(
                        preview.path("plan").path("hazards").spliterator(), false)
                .filter(h -> "HIGH".equals(h.path("hazardClass").asString()))
                .map(h -> h.path("id").asString())
                .toList();

        JsonNode applied = apply(
                "",
                mapper.writeValueAsString(Map.of(
                        "expectedPlanHash",
                        preview.path("plan").path("planHash").asString(),
                        "acknowledgedHazards",
                        acknowledge)));

        assertThat(applied.path("dryRun").asBoolean()).isFalse();
        assertThat(applied.path("outcome").asString()).isEqualTo("APPLIED");

        Set<String> addresses = names("AddressNames");
        assertThat(addresses).contains(orders, audit);
        assertThat(names("QueueNames")).contains(orders, auditQueue);
        assertThat(divertOps.find(client, divert)).isPresent();
        assertThat(exec(BrokerConfigOperations.GET_ADDRESS_SETTINGS, match).asString())
                .contains("\"maxDeliveryAttempts\":7");
        assertThat(exec(BrokerConfigOperations.GET_ROLES, match).asString()).contains("c1-role");

        JsonNode again = apply("", "{}");
        assertThat(again.path("dryRun").asBoolean()).isFalse();
        assertThat(again.path("plan").path("stepCount").asInt()).isZero();
        assertThat(again.path("nodes")
                        .valueStream()
                        .flatMap(n -> n.path("steps").valueStream()))
                .allMatch(s -> "ALREADY".equals(s.path("status").asString()));
    }

    @Test
    void anApplyConvergesAnExistingQueueAndAddressThatDifferFromTheDeclaration() throws Exception {
        // Live: events is ANYCAST only, its queue is exclusive with no consumer limit;
        // fanout accepts both routing types and has no queue.
        queueOps.createAddress(client, broker, events, "ANYCAST");
        queueOps.createQueue(
                client,
                broker,
                Map.of(
                        "name",
                        events,
                        "address",
                        events,
                        "routing-type",
                        "ANYCAST",
                        "durable",
                        true,
                        "exclusive",
                        true));
        queueOps.createAddress(client, broker, fanout, "ANYCAST,MULTICAST");
        configs.save(
                clusterId,
                new BrokerConfigDocument(
                        BrokerConfigDocument.CURRENT_VERSION,
                        List.of(
                                new AddressDecl(
                                        events,
                                        Set.of("ANYCAST", "MULTICAST"),
                                        List.of(new QueueDecl(
                                                events, "ANYCAST", "region = 'eu'", true, 5, null, null, null, null))),
                                new AddressDecl(fanout, Set.of("MULTICAST"), List.of())),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                null,
                "c3",
                BrokerConfigService.Source.EDIT);

        JsonNode preview = apply("?dryRun=true", "{}");
        assertThat(preview.path("plan").path("findings").valueStream()).isEmpty();
        assertThat(preview.path("plan").path("stepCount").asInt()).isEqualTo(3);
        assertThat(preview.path("plan").path("hazards").valueStream())
                .allMatch(h -> "LOW".equals(h.path("hazardClass").asString()))
                .extracting(h -> h.path("kind").asString())
                .containsOnly("ROUTING_TYPE_CHANGE");

        JsonNode applied = apply(
                "",
                mapper.writeValueAsString(Map.of(
                        "expectedPlanHash",
                        preview.path("plan").path("planHash").asString())));
        assertThat(applied.path("outcome").asString())
                .describedAs(applied.toString())
                .isEqualTo("APPLIED");

        assertThat(routingTypes(events)).containsExactlyInAnyOrder("ANYCAST", "MULTICAST");
        assertThat(routingTypes(fanout)).containsExactly("MULTICAST");
        JsonNode queue = client.single(ManagementRequest.read(
                        MBeanNames.queue(broker, events, events, "ANYCAST"), "MaxConsumers", "Filter", "Exclusive"))
                .value();
        assertThat(queue.path("MaxConsumers").asInt()).isEqualTo(5);
        assertThat(queue.path("Filter").asString()).isEqualTo("region = 'eu'");
        // Not declared, so the read-merge update kept it (ADR-0082 D1).
        assertThat(queue.path("Exclusive").asBoolean()).isTrue();

        JsonNode again = apply("", "{}");
        assertThat(again.path("plan").path("stepCount").asInt()).isZero();
        assertThat(again.path("nodes")
                        .valueStream()
                        .flatMap(n -> n.path("steps").valueStream()))
                .allMatch(s -> "ALREADY".equals(s.path("status").asString()));
    }

    @Test
    void aRoutingTypeAQueueIsBoundToIsKeptAndReportedWhileTheRestOfTheApplyProceeds() throws Exception {
        queueOps.createAddress(client, broker, mixed, "ANYCAST,MULTICAST");
        queueOps.createQueue(
                client,
                broker,
                Map.of("name", mixedWork, "address", mixed, "routing-type", "ANYCAST", "durable", true));
        configs.save(
                clusterId,
                new BrokerConfigDocument(
                        BrokerConfigDocument.CURRENT_VERSION,
                        List.of(
                                new AddressDecl(mixed, Set.of("MULTICAST"), List.of()),
                                new AddressDecl(fanout, Set.of("MULTICAST"), List.of())),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                null,
                "c3",
                BrokerConfigService.Source.EDIT);

        // The broker refuses to drop ANYCAST while mixedWork is bound (AMQ229209): a finding
        // that names the queue, not a step that would halt the node before fanout is created.
        JsonNode preview = apply("?dryRun=true", "{}");
        assertThat(preview.path("plan").path("hazards").valueStream()).isEmpty();
        assertThat(preview.path("plan").path("stepCount").asInt()).isEqualTo(1);
        assertThat(preview.path("plan").path("findings").valueStream())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.path("kind").asString()).isEqualTo("DIVERGENT_ADDRESS");
                    assertThat(f.path("detail").asString()).contains(mixedWork).contains("ANYCAST");
                });

        JsonNode applied = apply(
                "",
                mapper.writeValueAsString(Map.of(
                        "expectedPlanHash",
                        preview.path("plan").path("planHash").asString())));

        assertThat(applied.path("outcome").asString())
                .describedAs(applied.toString())
                .isEqualTo("APPLIED");
        assertThat(routingTypes(mixed)).containsExactlyInAnyOrder("ANYCAST", "MULTICAST");
        assertThat(routingTypes(fanout)).containsExactly("MULTICAST");
    }

    private Set<String> routingTypes(String address) {
        JsonNode value = client.single(ManagementRequest.read(MBeanNames.address(broker, address), "RoutingTypes"))
                .attribute("RoutingTypes");
        return Set.copyOf(value.valueStream().map(JsonNode::asString).toList());
    }

    private BrokerConfigDocument document() {
        return new BrokerConfigDocument(
                BrokerConfigDocument.CURRENT_VERSION,
                List.of(
                        new AddressDecl(
                                orders,
                                Set.of("ANYCAST"),
                                List.of(new QueueDecl(orders, "ANYCAST", null, true, null, null, null, null, null))),
                        new AddressDecl(
                                audit,
                                Set.of("MULTICAST"),
                                List.of(new QueueDecl(
                                        auditQueue, "MULTICAST", null, true, null, null, null, null, null)))),
                List.of(new AddressSettingDecl(match, Map.of("maxDeliveryAttempts", 7))),
                List.of(new SecuritySettingDecl(
                        match, Map.of(PermissionType.SEND, Set.of("c1-role"), PermissionType.CONSUME, Set.of("amq")))),
                List.of(new DivertDecl(divert, orders, audit, null, false, null, null, null)),
                List.of());
    }

    private JsonNode apply(String query, String body) throws Exception {
        var response = mvc.perform(post("/api/v1/clusters/" + clusterId + "/config/apply" + query)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        String json = response.getContentAsString();
        assertThat(response.getStatus()).describedAs(json).isEqualTo(200);
        return mapper.readTree(json);
    }

    private Set<String> names(String attribute) {
        JsonNode value =
                client.single(ManagementRequest.read(broker, attribute)).attribute(attribute);
        return Set.copyOf(value.valueStream().map(JsonNode::asString).toList());
    }

    private JsonNode exec(String operation, String argument) {
        ManagementResponse res = client.single(ManagementRequest.exec(broker, operation, argument));
        assertThat(res.ok()).describedAs("%s: %s", operation, res.error()).isTrue();
        return res.value();
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException _) {
            // already gone, or never created
        }
    }
}
