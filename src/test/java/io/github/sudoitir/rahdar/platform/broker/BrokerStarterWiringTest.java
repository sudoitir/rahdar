package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ConnectOptions;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.TlsProvider;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.Observation;
import io.github.sudoitir.openbroker.messaging.OperationInfo;
import io.github.sudoitir.openbroker.messaging.OperationType;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerAutoConfiguration;
import io.github.sudoitir.openbroker.spring.boot.autoconfigure.OpenBrokerConnectOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The broker client is wired by OpenBroker's starter with Rahdar's choices on top (ADR-0211, ADR-0212):
 * the live Settings timeouts, no operator context on the SDK's threads, metrics tagged with the cluster, a
 * message left exactly as its producer wrote it, and no connection derived from Spring's own settings.
 */
class BrokerStarterWiringTest {

    private final BrokerTimeouts timeouts =
            new BrokerTimeouts(new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000));
    private final MeterRegistry meters = new SimpleMeterRegistry();

    /** Rahdar's own application.yml is applied, so its openbroker.* keys are under test too. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, OpenBrokerAutoConfiguration.class))
            .withBean(BrokerTimeouts.class, () -> timeouts)
            .withBean(MeterRegistry.class, () -> meters)
            .withBean(RahdarCallInterceptor.class, () -> mock(RahdarCallInterceptor.class))
            .withBean(BrokerConnectOptionsCustomizer.class);

    @Test
    void theOptionsCarryTheLiveTimeouts() {
        runner.run(context -> {
            OpenBrokerConnectOptions factory = context.getBean(OpenBrokerConnectOptions.class);

            assertThat(factory.forConnection("c", "artemis", TlsProvider.none()).timeouts())
                    .isSameAs(timeouts);

            timeouts.set(Duration.ofSeconds(7), Duration.ofSeconds(21));
            ConnectOptions options = factory.forConnection("c", "artemis", TlsProvider.none());

            assertThat(options.timeouts().connect()).isEqualTo(Duration.ofSeconds(7));
            assertThat(options.timeouts().call()).isEqualTo(Duration.ofSeconds(21));
        });
    }

    @Test
    void aCallbackIsNeverWrappedWithTheCallersContext() {
        runner.run(context -> {
            ConnectOptions options =
                    context.getBean(OpenBrokerConnectOptions.class).forConnection("c", "artemis", TlsProvider.none());
            Runnable callback = () -> {};

            assertThat(options.contextPropagation().apply(callback)).isSameAs(callback);
        });
    }

    @Test
    void aSendCountsForItsClusterAndWritesNothingIntoTheMessage() {
        runner.run(context -> {
            String cluster = UUID.randomUUID().toString();
            ConnectOptions options = context.getBean(OpenBrokerConnectOptions.class)
                    .forConnection(cluster, "artemis", TlsProvider.none());
            Map<String, String> written = new ConcurrentHashMap<>();
            ResourceId queue = new ResourceId(KindId.of("artemis.queue"), ResourcePath.of("orders"));

            Observation send = options.observer()
                    .onSend(
                            new OperationInfo(
                                    "activemq",
                                    OperationType.SEND,
                                    "send",
                                    queue,
                                    Optional.empty(),
                                    NodeId.of("node-1"),
                                    Optional.empty(),
                                    OptionalInt.empty(),
                                    1),
                            OutboundMessage.of(MessageBody.of("x".getBytes())),
                            written::put);
            send.end(null);

            assertThat(written).isEmpty();
            assertThat(meters.getMeters())
                    .anySatisfy(meter -> assertThat(meter.getId().getTag("openbroker.connection"))
                            .isEqualTo(cluster));
        });
    }

    @Test
    void aSendIsOneSpanAndOneCountAndTheMessageIsUntouched() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        runner.withBean(OpenTelemetry.class, () -> openTelemetry).run(context -> {
            String cluster = UUID.randomUUID().toString();
            ConnectOptions options = context.getBean(OpenBrokerConnectOptions.class)
                    .forConnection(cluster, "artemis", TlsProvider.none());
            Map<String, String> written = new ConcurrentHashMap<>();

            options.observer()
                    .onSend(send(), OutboundMessage.of(MessageBody.of("x".getBytes())), written::put)
                    .end(null);

            assertThat(spans.getFinishedSpanItems()).hasSize(1);
            assertThat(written).isEmpty();
            assertThat(meters.find("messaging.client.sent.messages")
                            .tag("openbroker.connection", cluster)
                            .counters())
                    .singleElement()
                    .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0));
        });
    }

    private static OperationInfo send() {
        return new OperationInfo(
                "activemq",
                OperationType.SEND,
                "send",
                new ResourceId(KindId.of("artemis.queue"), ResourcePath.of("orders")),
                Optional.empty(),
                NodeId.of("node-1"),
                Optional.empty(),
                OptionalInt.empty(),
                1);
    }

    @Test
    void springsOwnBrokerSettingsCreateNoConnection() {
        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "spring.artemis.mode=native",
                        "spring.artemis.broker-url=tcp://localhost:61616")
                .run(context -> assertThat(context.getBeansOfType(ClusterConnection.class))
                        .isEmpty());
    }
}
