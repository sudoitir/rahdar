package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerSystem;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectionReport;
import io.github.sudoitir.openbroker.ConnectionSchema;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.OptionSpec;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.spi.BrokerDriver;
import io.github.sudoitir.openbroker.spi.DriverContext;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerAutoConfiguration;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerConnectOptions;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerProperties;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.SslBundleTls;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions.Entry;
import io.github.sudoitir.rahdar.platform.broker.NodeDirectory.KnownNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * A cluster's connection keeps the driver options it was registered with.
 *
 * <p>A registered Kafka cluster is reached with {@code SASL_PLAINTEXT} and a SCRAM mechanism, and the driver builds
 * its client from the spec it holds. A reconfiguration replaces that spec outright, so a node list kept for
 * comparison has to be the completed spec the driver was given; keeping the bare node list made the guard
 * re-reconfigure on every call and the client fall back to plaintext, which a SASL broker refuses. This is the
 * regression that made a live cluster unreadable.
 */
class BrokerGatewayOptionsTest {

    private static final UUID CLUSTER = UUID.fromString("01a195b6-10ea-7cf3-9621-5d783c0c9af3");

    private static final Map<String, String> SECURITY =
            Map.of("security.protocol", "SASL_PLAINTEXT", "sasl.mechanism", "SCRAM-SHA-256");

    /** Records the spec of every connect and reconfiguration, and answers calls with nothing. */
    private static final class RecordingDriver implements BrokerDriver {

        private final List<ClusterSpec> connected = new ArrayList<>();
        private final List<ClusterSpec> reconfigured = new ArrayList<>();

        @Override
        public BrokerSystem system() {
            return new BrokerSystem("kafka", "Apache Kafka", "[2.8.0,)", 1, Set.of("org.apache.kafka."), List.of());
        }

        @Override
        public ConnectionSchema schema() {
            return new ConnectionSchema(List.of(option("security.protocol"), option("sasl.mechanism")));
        }

        private static OptionSpec option(String key) {
            return new OptionSpec(
                    key, OptionSpec.Type.STRING, false, false, Optional.empty(), List.of(), key, Optional.empty());
        }

        @Override
        public ConnectionReport verify(ClusterSpec cluster, CredentialSource credentials, DriverContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClusterConnection connect(ClusterSpec cluster, CredentialSource credentials, DriverContext context) {
            connected.add(cluster);
            return mock(ClusterConnection.class, invocation -> {
                if (invocation.getMethod().getName().equals("reconfigure")) {
                    reconfigured.add(invocation.getArgument(0));
                    return null;
                }
                if (invocation.getMethod().getName().equals("transport")
                        || invocation.getMethod().getName().equals("notifications")) {
                    return Optional.empty();
                }
                return null;
            });
        }
    }

    /** The system's options: the bootstrap servers, and the security settings a Kafka cluster is registered with. */
    private static ConnectionOptions sasl() {
        ConnectionOptions options = mock(ConnectionOptions.class);
        when(options.system()).thenReturn("kafka");
        when(options.entry()).thenReturn(Entry.BOOTSTRAP);
        when(options.credentials(any())).thenReturn(plane -> new Credentials.None());
        when(options.tls(any())).thenReturn(Optional.empty());
        when(options.spec(any(), any()))
                .thenAnswer(call -> new ClusterSpec(
                        call.getArgument(1),
                        List.of(
                                new Endpoint(Plane.DATA, "kafka", "broker-1:9093"),
                                new Endpoint(Plane.DATA, "kafka", "broker-2:9093")),
                        SECURITY));
        return options;
    }

    private static BrokerGateway gateway(BrokerDriver driver, NodeDirectory directory, ConnectionOptions options) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SslBundles.class, () -> mock(SslBundles.class));
        context.registerBean(
                BrokerTimeouts.class,
                () -> new BrokerTimeouts(new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000)));
        context.registerBean(RahdarCallInterceptor.class, () -> mock(RahdarCallInterceptor.class));
        context.registerBean(BrokerConnectOptionsCustomizer.class);
        context.registerBean(BrokerLinks.class, () -> new BrokerLinks(new SimpleMeterRegistry()));
        context.register(OpenBrokerAutoConfiguration.class);
        context.refresh();
        OpenBrokerConnectOptions connectOptions = context.getBean(OpenBrokerConnectOptions.class);
        return new BrokerGateway(
                BrokerDrivers.of(List.of(driver)),
                directory,
                List.of(options),
                context.getBean(SslBundleTls.class),
                connectOptions,
                context.getBean(OpenBrokerProperties.class),
                new BrokerTelemetry(context.getBean(BrokerLinks.class), connectOptions));
    }

    private static NodeDirectory directory(List<KnownNode> nodes) {
        NodeDirectory directory = mock(NodeDirectory.class);
        when(directory.system(CLUSTER)).thenReturn("kafka");
        when(directory.nodes()).thenReturn(nodes);
        return directory;
    }

    private static KnownNode broker(String name, String address) {
        return new KnownNode(UUID.randomUUID(), CLUSTER, name, List.of(new Endpoint(Plane.DATA, "kafka", address)));
    }

    @Test
    void theSecurityOptionsSurviveTheReconfigurationThatFollowsANewNode() {
        RecordingDriver driver = new RecordingDriver();
        KnownNode one = broker("broker-1", "192.0.2.11:9093");
        // A node joins after the connection was opened, which is what a discovery cycle does.
        NodeDirectory directory = directory(List.of(one));

        BrokerGateway gateway = gateway(driver, directory, sasl());
        gateway.cluster(CLUSTER);
        when(directory.nodes()).thenReturn(List.of(one, broker("broker-2", "192.0.2.12:9093")));
        gateway.cluster(CLUSTER);

        assertThat(driver.connected)
                .singleElement()
                .satisfies(spec -> assertThat(spec.options()).containsAllEntriesOf(SECURITY));
        assertThat(driver.reconfigured)
                .singleElement()
                .satisfies(spec -> assertThat(spec.options())
                        .as("a reconfiguration must not drop the cluster's security settings")
                        .containsAllEntriesOf(SECURITY));
    }

    @Test
    void anUnchangedNodeSetIsNotReconfiguredAtAll() {
        RecordingDriver driver = new RecordingDriver();
        NodeDirectory directory = directory(List.of(broker("broker-1", "192.0.2.11:9093")));

        BrokerGateway gateway = gateway(driver, directory, sasl());
        gateway.cluster(CLUSTER);
        gateway.cluster(CLUSTER);

        assertThat(driver.connected).hasSize(1);
        assertThat(driver.reconfigured).isEmpty();
    }

    @Test
    void aProbedNodeIsAddedWithoutDroppingTheSecurityOptions() {
        RecordingDriver driver = new RecordingDriver();
        BrokerGateway gateway = gateway(driver, directory(List.of()), sasl());

        try (ProbeSession session = gateway.probe(CLUSTER)) {
            session.node("https://broker-1:8161/console/jolokia");
            session.node("https://broker-2:8161/console/jolokia");
        }

        assertThat(driver.reconfigured)
                .singleElement()
                .satisfies(spec -> assertThat(spec.options())
                        .as("a probe that adds a node must not drop the cluster's security settings")
                        .containsAllEntriesOf(SECURITY));
    }
}
