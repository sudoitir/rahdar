package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.rahdar.platform.broker.BrokerClientFactory;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.rahdar.support.wire.WireRecorder;
import java.time.Duration;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

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

    /** A Jolokia client of the test's own for the shared broker, outside Rahdar's rate limiter. */
    public static JolokiaBrokerClient jolokiaClient() {
        return jolokiaClient(jolokiaUrl());
    }

    /**
     * A Jolokia client for any test broker, with the converters Rahdar's own clients use:
     * an older broker's agent labels its JSON {@code text/plain}.
     */
    public static JolokiaBrokerClient jolokiaClient(String url) {
        JsonMapper mapper = JsonMapper.builder().build();
        RestClient rest = RestClient.builder()
                .configureMessageConverters(b ->
                        b.configureMessageConvertersList(c -> BrokerClientFactory.applyJolokiaConverters(c, mapper)))
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(BROKER_USER, BROKER_PASSWORD);
                    return execution.execute(request, body);
                })
                .build();
        return new JolokiaBrokerClient(rest, url, mapper);
    }
}
