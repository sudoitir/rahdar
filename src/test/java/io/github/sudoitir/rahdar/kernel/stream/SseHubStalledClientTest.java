package io.github.sudoitir.rahdar.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.kernel.replica.BusFrame;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * A client that stops reading must not hold up the bus: over a real connection, a signal sent behind a
 * frame for a blocked emitter still reaches its listener, and a healthy client still gets the frame.
 */
class SseHubStalledClientTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    DataSourceProperties datasource;

    @Test
    void aBlockedEmitterDelaysNeitherAnotherClientNorASignalListener() throws Exception {
        List<ReplicaSignal> signals = new CopyOnWriteArrayList<>();
        SseHub[] hub = new SseHub[1];
        RahdarBus bus = new RahdarBus(jdbc, mapper, datasource, event -> {
            if (event instanceof BusFrame frame) {
                hub[0].onFrame(frame);
            } else if (event instanceof ReplicaSignal signal) {
                signals.add(signal);
            }
        });
        hub[0] = new SseHub(bus, mapper, (s, c, f) -> f, new SimpleMeterRegistry());
        CountDownLatch stall = new CountDownLatch(1);
        UUID clusterId = UUID.randomUUID();
        SseEmitter stalled = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    stall.await();
                    return null;
                })
                .when(stalled)
                .send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        hub[0].register(clusterId, new Subscriber(stalled, Set.of("subscriptions"), null, null, null));
        hub[0].register(clusterId, new Subscriber(healthy, Set.of("subscriptions"), null, null, null));
        bus.start();
        try {
            await().atMost(Duration.ofSeconds(10)).until(bus::isListening);

            hub[0].publishAbout(clusterId, "subscriptions", List.of("orders.a"), List.of());
            hub[0].publishAbout(clusterId, "subscriptions", List.of("orders.b"), List.of());
            bus.publish(new ReplicaSignal("settings", "after"));

            await().atMost(Duration.ofSeconds(5)).until(() -> !signals.isEmpty());
            assertThat(signals).containsExactly(new ReplicaSignal("settings", "after"));
            verify(healthy, timeout(5_000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            stall.countDown();
            bus.stop();
        }
    }
}
