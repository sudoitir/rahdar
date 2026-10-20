package io.github.sudoitir.rahdar.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.BridgeOperations;
import io.github.sudoitir.rahdar.system.artemis.BridgeRow;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The bridge lifecycle against a real Artemis (ADR-0091).
 *
 * <p>This test exists because every one of the facts it asserts was measured rather than
 * assumed, and each of them is one the broker will not tell you about if you get it wrong.
 * {@code createBridge} answers 200 for a document it silently ignores, so a suite that only
 * checked the call's status code would pass against a broker that deployed nothing.
 *
 * <p>The broker here is 2.44.0, the version {@code compose.dev.yaml} pins and the version the
 * measurement in this change's {@code tasks.md} was taken on.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class BridgeLifecycleRealBrokerTest extends PostgresIntegrationTest {

    private static final String TRANSFORMER =
            "org.apache.activemq.artemis.core.server.transformer.AddHeadersTransformer";

    /** Declared in the mounted dev fixture, which is the broker.xml this container runs. */
    private static final String CONNECTOR = "backup-connector";

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerConnections connections;

    @Autowired
    BridgeOperations bridgeOps;

    @Autowired
    QueueLifecycleOperations queueOps;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String source = "BRIDGE.REAL." + run + ".SRC";
    private final String bridge = "bridge-real-" + run;

    private UUID clusterId;
    private ArtemisNode client;
    private String broker;

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "bridge-real-" + run,
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
        queueOps.createQueue(client, broker, Map.of("name", source, "address", source, "routing-type", "ANYCAST"));
    }

    @AfterEach
    void cleanUp() {
        quietly(() -> bridgeOps.destroyBridge(client, broker, bridge));
        quietly(() -> bridgeOps.destroyBridge(client, broker, bridge + "-concurrent"));
        quietly(() -> queueOps.destroyQueue(client, broker, source, true));
        clusters.deleteById(clusterId);
    }

    /** The whole lifecycle: create, verify, re-create, change, remove. */
    @Test
    void createsVerifiesChangesAndRemovesABridge() {
        Map<String, Object> config = config(bridge, Map.of("filter-string", "kind='a'"));

        assertThat(bridgeOps.createVerified(client, broker, config)).isEqualTo(NodeStatus.APPLIED);

        Map<String, BridgeRow> deployed = bridgeOps.instancesOf(client, List.of(bridge));
        assertThat(deployed).containsOnlyKeys(bridge);
        BridgeRow row = deployed.get(bridge);
        assertThat(row.queueName()).isEqualTo(source);
        assertThat(row.filterString()).isEqualTo("kind='a'");
        assertThat(row.staticConnectors()).containsExactly(CONNECTOR);

        // An identical create is ALREADY, not a second bridge — the idempotence every
        // re-run of an apply depends on.
        assertThat(bridgeOps.createVerified(client, broker, config)).isEqualTo(NodeStatus.ALREADY);

        // There is no updateBridge, so a change is a destroy and a create (ADR-0091 D3).
        bridgeOps.destroyBridge(client, broker, bridge);
        assertThat(bridgeOps.instancesOf(client, List.of(bridge))).isEmpty();

        Map<String, Object> changed = config(bridge, Map.of("filter-string", "kind='b'"));
        assertThat(bridgeOps.createVerified(client, broker, changed)).isEqualTo(NodeStatus.APPLIED);
        assertThat(bridgeOps.instancesOf(client, List.of(bridge)).get(bridge).filterString())
                .isEqualTo("kind='b'");

        bridgeOps.destroyBridge(client, broker, bridge);
        assertThat(bridgeOps.instancesOf(client, List.of(bridge))).isEmpty();
    }

    /**
     * A bridge of the same name with a different configuration is a failure naming the
     * fields, never a silent success — the case an apply must not report as applied.
     */
    @Test
    void refusesABridgeOfTheSameNameThatDiffers() {
        bridgeOps.createVerified(client, broker, config(bridge, Map.of("filter-string", "kind='a'")));

        assertThatThrownBy(() ->
                        bridgeOps.createVerified(client, broker, config(bridge, Map.of("filter-string", "kind='z'"))))
                .hasMessageContaining("filter");
    }

    /**
     * Concurrency renames the object. Without this the read-back reports a healthy
     * concurrent bridge as never deployed, and destroy by the declared name is what
     * takes every instance away.
     */
    @Test
    void accountsForConcurrencyInTheNamesItVerifiesAndRemoves() {
        String name = bridge + "-concurrent";
        Map<String, Object> config = config(name, Map.of("concurrency", 2));

        assertThat(bridgeOps.createVerified(client, broker, config)).isEqualTo(NodeStatus.APPLIED);
        assertThat(BridgeOperations.instanceNames(config)).containsExactly(name + "-0", name + "-1");
        assertThat(bridgeOps.instancesOf(client, BridgeOperations.instanceNames(config)))
                .containsOnlyKeys(name + "-0", name + "-1");

        // Destroy takes the declared name and removes both instances.
        bridgeOps.destroyBridge(client, broker, name);
        assertThat(bridgeOps.instancesOf(client, BridgeOperations.instanceNames(config)))
                .isEmpty();
    }

    /**
     * The transformer is the nested {@code transformer-configuration}, and the broker
     * reports the class and its properties back in full — which is why a transformer is
     * compared like any other field rather than excluded from comparison (ADR-0091 D7).
     */
    @Test
    void roundTripsATransformerAndItsProperties() {
        Map<String, Object> config = config(
                bridge,
                Map.of("transformer-configuration", Map.of("class-name", TRANSFORMER, "properties", Map.of("a", "1"))));

        assertThat(bridgeOps.createVerified(client, broker, config)).isEqualTo(NodeStatus.APPLIED);

        BridgeRow row = bridgeOps.instancesOf(client, List.of(bridge)).get(bridge);
        assertThat(row.transformerClassName()).isEqualTo(TRANSFORMER);
        assertThat(row.transformerProperties()).containsEntry("a", "1");
    }

    /**
     * The measured failure mode this whole verification exists for: the broker accepts a
     * document whose keys it does not recognise, answers 200, and deploys nothing. A
     * read-back is the only thing that catches it.
     */
    @Test
    void reportsADocumentTheBrokerSilentlyIgnoredAsNotDeployed() {
        Map<String, Object> camelCase = new LinkedHashMap<>();
        camelCase.put("name", bridge);
        camelCase.put("queueName", source);
        camelCase.put("staticConnectors", List.of(CONNECTOR));

        assertThatThrownBy(() -> bridgeOps.createVerified(client, broker, camelCase))
                .hasMessageContaining(bridge);
        assertThat(bridgeOps.instancesOf(client, List.of(bridge))).isEmpty();
    }

    private Map<String, Object> config(String name, Map<String, Object> extra) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", name);
        config.put("queue-name", source);
        config.put("static-connectors", List.of(CONNECTOR));
        config.putAll(extra);
        return config;
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException _) {
            // Best-effort cleanup; the run's names are unique so a leftover cannot collide.
        }
    }
}
