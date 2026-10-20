package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectOptions;
import io.github.sudoitir.openbroker.ConnectRequest;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.support.wire.WireRecorder;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Duration;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that need a real Apache ActiveMQ Artemis broker — the Core
 * protocol client, {@code activemq.notifications}, and faithful message I/O all
 * need one, and nothing in the suite booted a broker before Phase 4.
 *
 * <p>The container is a process-wide singleton, started once in a {@code static}
 * block and never stopped (Ryuk cleans it up at JVM exit). Same reasoning as
 * {@link PostgresIntegrationTest}: Spring caches contexts across test classes, so
 * a per-class {@code @Container} that stops after each class would leave a later
 * class's cached context pointing at a dead broker.
 *
 * <p>The mounted {@code broker.xml} is the dev-compose primary fixture, which is
 * a complete config already carrying {@code NotificationActiveMQServerPlugin} and
 * the {@code activemq.notifications} {@code consume} + {@code createNonDurableQueue}
 * permissions. Its replication {@code ha-policy} and static cluster-connection
 * name a backup peer that is not present here; the broker still becomes live
 * (its {@code check-for-active-server} probe finds no active server) and the
 * unreachable cluster-connection only logs, it does not block startup.
 */
public abstract class ArtemisIntegrationTest {

    /** The broker under test; CI sets {@code artemis.image} once per supported-range end. */
    public static final String IMAGE =
            System.getProperty("artemis.image", "apache/artemis:" + BrokerVersion.LATEST_TESTED);

    public static final String BROKER_USER = "artemis";
    public static final String BROKER_PASSWORD = "artemis";

    protected static final GenericContainer<?> ARTEMIS = new GenericContainer<>(IMAGE)
            .withEnv("ARTEMIS_USER", BROKER_USER)
            .withEnv("ARTEMIS_PASSWORD", BROKER_PASSWORD)
            .withEnv("ANONYMOUS_LOGIN", "false")
            .withCopyFileToContainer(
                    // The dev-compose primary fixture is the single source of truth for the
                    // broker.xml Rahdar expects; path is relative to the module root, the CWD
                    // during `mvn test`.
                    MountableFile.forHostPath("deploy/compose/dev/artemis/primary/broker.xml"),
                    "/var/lib/artemis-instance/etc-override/broker.xml")
            .withExposedPorts(61616, 8161)
            .waitingFor(
                    Wait.forLogMessage(".*Artemis Console available.*", 1).withStartupTimeout(Duration.ofSeconds(120)))
            .withReuse(true);

    static {
        ARTEMIS.start();
    }

    /** Core protocol URL for the mapped acceptor port. */
    public static String coreUrl() {
        return "tcp://%s:%d".formatted(ARTEMIS.getHost(), ARTEMIS.getMappedPort(61616));
    }

    /**
     * Jolokia base URL for the mapped console port.
     *
     * <p>Public, and the container with it, because a test can only extend one
     * base and the ones that need a real broker <em>and</em> the Spring context
     * extend {@link PostgresIntegrationTest}. Touching this method initialises the
     * class and therefore starts the shared container, which is the point.
     */
    public static String jolokiaUrl() {
        return WireRecorder.proxied(
                "http://%s:%d/console/jolokia".formatted(ARTEMIS.getHost(), ARTEMIS.getMappedPort(8161)));
    }

    /** The shared broker's management agent, through the broker SDK, outside Rahdar's rate limiter. */
    public static ArtemisNode jolokiaClient() {
        return jolokiaClient(jolokiaUrl());
    }

    /** Any test broker's management agent, through the broker SDK, with the test account. */
    public static ArtemisNode jolokiaClient(String url) {
        return ArtemisNodes.node(target(url));
    }

    /** A test broker as one SDK node, for code that takes a {@link BrokerTarget}. */
    public static BrokerTarget target(String url) {
        ClusterConnection connection = DRIVERS.connect(new ConnectRequest(
                "artemis",
                ClusterSpec.ofNodes(
                        List.of(new NodeSpec(NodeId.of(url), List.of(new Endpoint(Plane.MANAGEMENT, "jolokia", url))))),
                CredentialSource.of(Credentials.of(BROKER_USER, BROKER_PASSWORD)),
                ConnectOptions.defaults()));
        return new BrokerTarget(connection, NodeId.of(url));
    }

    /** One driver registry for the test JVM, as an application keeps one. */
    private static final BrokerDrivers DRIVERS = BrokerDrivers.load();
}
