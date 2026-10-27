package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.MessagingObserver;
import io.github.sudoitir.openbroker.messaging.Observation;
import io.github.sudoitir.openbroker.messaging.OperationInfo;
import io.github.sudoitir.openbroker.messaging.OperationType;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Broker messaging is traced through Rahdar's own OpenTelemetry, and Rahdar never writes trace context into
 * a message: what it sends, moves or relays keeps the properties its producer gave it (ADR-0211).
 */
class BrokerTelemetryTest {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(new Collecting()))
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    @Test
    void aSendIsASpanAndWritesNothingIntoTheMessage() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sdk);
        BrokerTelemetry telemetry = new BrokerTelemetry(provider, new BrokerLinks(new SimpleMeterRegistry()));
        Map<String, String> written = new ConcurrentHashMap<>();
        ResourceId queue = new ResourceId(KindId.of("artemis.queue"), ResourcePath.of("orders"));

        Observation send = telemetry
                .observer()
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
        assertThat(spans).extracting(SpanData::getName).containsExactly("send orders");
    }

    @Test
    void withoutOpenTelemetryNothingIsObserved() {
        assertThat(BrokerTelemetry.none().observer()).isSameAs(MessagingObserver.none());
    }

    private final class Collecting implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
