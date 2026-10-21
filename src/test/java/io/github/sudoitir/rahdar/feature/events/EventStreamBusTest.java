package io.github.sudoitir.rahdar.feature.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.RahdarApplication;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.kernel.stream.Subscriber;
import io.github.sudoitir.rahdar.platform.broker.BrokerEvent;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Two replicas, this context and a second one started against the same database: what one publishes
 * reaches the stream clients of both, once each, and a batch of broker events reaches them with their
 * {@code seq} as the SSE id only after it has committed.
 */
class EventStreamBusTest extends PostgresIntegrationTest {

    @Autowired
    SseHub hubA;

    @Autowired
    BrokerEventWriter writer;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final UUID clusterId = UUID.randomUUID();
    private ConfigurableApplicationContext other;
    private SseHub hubB;

    @BeforeEach
    void startTheOtherReplica() {
        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        other = new SpringApplicationBuilder(RahdarApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
        hubB = other.getBean(SseHub.class);
        jdbc.update(
                "INSERT INTO cluster (id, name, system) VALUES (:id, :name, 'artemis')",
                Map.of("id", clusterId, "name", "bus-" + clusterId));
    }

    @AfterEach
    void stopTheOtherReplica() {
        other.close();
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    @Test
    void aFramePublishedOnOneReplicaReachesTheClientsOfBothOnce() throws Exception {
        SseEmitter onA = subscribe(hubA, "subscriptions");
        SseEmitter onB = subscribe(hubB, "subscriptions");

        hubA.publish(clusterId, "subscriptions");

        assertThat(frames(onA, "subscriptions", 1)).singleElement().asString().contains("event:subscriptions");
        assertThat(frames(onB, "subscriptions", 1)).singleElement().asString().contains("event:subscriptions");
    }

    @Test
    void brokerEventsReachBothReplicasWithTheirSeqAsIdOnceEach() throws Exception {
        SseEmitter onA = subscribe(hubA, "events");
        SseEmitter onB = subscribe(hubB, "events");

        writer.accept(event("CONSUMER_CREATED"));
        writer.accept(event("SESSION_CREATED"));
        writer.flush();

        List<String> seqs = jdbc.queryForList(
                "SELECT seq::text FROM broker_event WHERE cluster_id = :c ORDER BY broker_event.seq",
                Map.of("c", clusterId),
                String.class);
        assertThat(seqs).hasSize(2);
        for (SseEmitter emitter : List.of(onA, onB)) {
            List<String> frames = frames(emitter, "events", 2);
            assertThat(frames.get(0)).contains("event:events", "id:" + seqs.get(0), "CONSUMER_CREATED");
            assertThat(frames.get(1)).contains("event:events", "id:" + seqs.get(1), "SESSION_CREATED");
        }
    }

    @Test
    void aRolledBackFlushReachesNoClient() throws Exception {
        SseEmitter onA = subscribe(hubA, "events");
        writer.accept(event("CONSUMER_CREATED"));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            writer.flush();
            status.setRollbackOnly();
        });

        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
        // Keep-alives may arrive; an events frame may not.
        assertThat(sent(onA, "events")).isEmpty();
    }

    private final RahdarPrincipal administrator = new RahdarPrincipal(
            UUID.randomUUID(), "admin", Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("*"))), false);

    private SseEmitter subscribe(SseHub hub, String topic) {
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of(topic), null, null, administrator));
        return emitter;
    }

    /** The frames a client was sent. Waits for {@code expected} of them, then a moment more to catch a repeat. */
    /**
     * The frames of {@code topic} a client was sent. Waits for {@code expected} of them, then a moment
     * more to catch a repeat; keep-alive pings that land in between are not counted.
     */
    private static List<String> frames(SseEmitter emitter, String topic, int expected) throws IOException {
        await().atMost(Duration.ofSeconds(5)).until(() -> sent(emitter, topic).size() >= expected);
        await().pollDelay(Duration.ofMillis(500)).until(() -> true);
        return sent(emitter, topic);
    }

    private static List<String> sent(SseEmitter emitter, String topic) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeast(0)).send(sent.capture());
        return sent.getAllValues().stream()
                .map(EventStreamBusTest::render)
                .filter(frame -> frame.contains("event:" + topic + "\n"))
                .toList();
    }

    private static String render(SseEmitter.SseEventBuilder builder) {
        StringBuilder out = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            out.append(part.getData());
        }
        return out.toString();
    }

    private BrokerEvent event(String type) {
        return new BrokerEvent(
                clusterId,
                null,
                type,
                Instant.now(),
                "some.address",
                "rn",
                "c1",
                "s1",
                "conn1",
                "1.2.3.4:5",
                "alice",
                Map.of("_AMQ_NotifType", type));
    }
}
