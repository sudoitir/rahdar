package io.github.sudoitir.rahdar.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.kernel.replica.BusFrame;
import io.github.sudoitir.rahdar.kernel.replica.BusMessage;
import io.github.sudoitir.rahdar.kernel.replica.BusResumed;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class SseHubTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RahdarBus bus = mock(RahdarBus.class);
    private final SseHub hub = new SseHub(bus, mapper, (s, c, f) -> f, new SimpleMeterRegistry());

    /** What the bus would do: hand the message back to this replica, which is how every frame arrives. */
    private void loopback() {
        doAnswer(invocation -> {
                    hub.onFrame((BusFrame) invocation.getArgument(0));
                    return null;
                })
                .when(bus)
                .publish(any(BusMessage.class));
    }

    @Test
    void publishReachesOnlySubscribersOfThatTopic() throws IOException {
        loopback();
        UUID clusterId = UUID.randomUUID();
        SseEmitter queuesEmitter = mock(SseEmitter.class);
        SseEmitter topologyEmitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(queuesEmitter, Set.of("subscriptions"), null, null, null));
        hub.register(clusterId, new Subscriber(topologyEmitter, Set.of("topology"), null, null, null));

        hub.publish(clusterId, "subscriptions");

        verify(queuesEmitter, timeout(2_000)).send(any(SseEmitter.SseEventBuilder.class));
        verify(topologyEmitter, after(200).never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aDeadEmitterIsDroppedOnTheNextPublish() throws IOException {
        loopback();
        UUID clusterId = UUID.randomUUID();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(dead, Set.of("subscriptions"), null, null, null));
        assertThat(hub.subscriberCount(clusterId)).isEqualTo(1);

        hub.publish(clusterId, "subscriptions");

        verify(dead, timeout(2_000)).completeWithError(any());
        assertThat(hub.subscriberCount(clusterId)).isZero();
    }

    @Test
    void publishToAClusterWithNoSubscribersIsANoOp() {
        loopback();
        UUID clusterId = UUID.randomUUID();

        assertThatCode(() -> hub.publish(clusterId, "subscriptions")).doesNotThrowAnyException();

        assertThat(hub.subscriberCount(clusterId)).isZero();
    }

    /**
     * The client's silence watchdog needs a frame to miss. A comment keeps the socket
     * warm and fires nothing an {@code EventSource} can observe (ADR-0052).
     */
    @Test
    void theHeartbeatIsANamedEventEverySubscriberReceives() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        // Subscribed to nothing: the keep-alive is not a topic.
        hub.register(clusterId, new Subscriber(emitter, Set.of(), null, null, null));

        hub.heartbeat();

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000)).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.PING);
    }

    @Test
    void aNewSubscriberIsGreetedWithAPingSoItsClientSeesTheStreamOpenAtOnce() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);

        hub.greet(new Subscriber(emitter, Set.of("subscriptions"), null, null, null));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.PING);
    }

    @Test
    void aDeadEmitterIsDroppedOnTheHeartbeat() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(dead, Set.of("subscriptions"), null, null, null));

        hub.heartbeat();

        await().atMost(Duration.ofSeconds(2)).until(() -> hub.subscriberCount(clusterId) == 0);
    }

    @Test
    void publishBroadcastsAFrameAndDeliversNothingItself() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of("events"), null, null, null));

        hub.publish(clusterId, "events", Map.of("seq", 7), "7");

        verify(bus).publish(new BusFrame(clusterId, "events", mapper.readTree("{\"seq\":7}"), "7"));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aSignalIsBroadcastWithoutData() {
        UUID clusterId = UUID.randomUUID();

        hub.publish(clusterId, "subscriptions");

        verify(bus).publish(new BusFrame(clusterId, "subscriptions", null, null));
    }

    @Test
    void aDatabaseThatCannotTakeASignalDoesNotFailThePublisher() {
        UUID clusterId = UUID.randomUUID();
        doThrow(new CannotGetJdbcConnectionException("pool exhausted"))
                .when(bus)
                .publish(any(BusMessage.class));

        assertThatCode(() -> {
                    hub.publish(clusterId, "subscriptions");
                    hub.publish(clusterId, "subscriptions");
                    hub.publish(clusterId, "events", Map.of("seq", 7), "7");
                })
                .doesNotThrowAnyException();

        verify(bus, times(3)).publish(any(BusMessage.class));
    }

    @Test
    void aFrameThatArrivesIsDeliveredOnceToTheLocalSubscribersOfItsTopic() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter wants = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(wants, Set.of("events"), null, null, null));
        hub.register(clusterId, new Subscriber(other, Set.of("subscriptions"), null, null, null));

        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("{\"seq\":7}"), "7"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(wants, timeout(2_000)).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:events", "id:7");
        verify(other, after(200).never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aBufferingSubscriberGetsLiveFramesAfterTheReplayAndNoRepeat() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        Subscriber subscriber = new Subscriber(emitter, Set.of("events", "subscriptions"), null, null, null);
        subscriber.buffer();
        hub.register(clusterId, subscriber);
        hub.replayTo(clusterId, subscriber, "events", "replayed", "11");
        hub.replayTo(clusterId, subscriber, "events", "replayed", "12");

        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("12"), "12"));
        hub.onFrame(new BusFrame(clusterId, "subscriptions", null, null));
        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("13"), "13"));
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));

        hub.release(subscriber, Map.of("events", 12L));
        hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree("14"), "14"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000).times(5)).send(sent.capture());
        assertThat(sent.getAllValues().stream().map(SseHubTest::render))
                .satisfiesExactly(
                        a -> assertThat(a).contains("id:11"),
                        a -> assertThat(a).contains("id:12"),
                        a -> assertThat(a).contains("event:subscriptions"),
                        a -> assertThat(a).contains("id:13"),
                        a -> assertThat(a).contains("id:14"));
    }

    @Test
    void theBusComingBackTellsEverySubscriberToResync() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of(), null, null, null));

        hub.onBusResumed(new BusResumed());

        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000)).send(frame.capture());
        assertThat(render(frame.getValue())).contains("event:" + SseHub.RESYNC);
    }

    @Test
    void closingTellsEverySubscriberToReconnectBeforeItIsCompleted() throws IOException {
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(emitter, Set.of(), null, null, null));

        hub.closeAll();

        InOrder order = inOrder(emitter);
        ArgumentCaptor<SseEmitter.SseEventBuilder> frame = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        order.verify(emitter, timeout(2_000)).send(frame.capture());
        order.verify(emitter, timeout(2_000)).complete();
        assertThat(render(frame.getValue())).contains("event:" + SseHub.RECONNECT);
        assertThat(hub.clientCount()).isZero();
    }

    @Test
    void aSubscriberWhoseEmitterBlocksDelaysNobody() throws Exception {
        UUID clusterId = UUID.randomUUID();
        CountDownLatch stall = new CountDownLatch(1);
        SseEmitter stalled = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    stall.await();
                    return null;
                })
                .when(stalled)
                .send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        hub.register(clusterId, new Subscriber(stalled, Set.of("subscriptions"), null, null, null));
        hub.register(clusterId, new Subscriber(healthy, Set.of("subscriptions"), null, null, null));

        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            hub.onFrame(new BusFrame(clusterId, "subscriptions", null, null, BusFrame.About.queue("orders." + i)));
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("dispatch").isLessThan(Duration.ofSeconds(1));
        verify(healthy, timeout(2_000).times(3)).send(any(SseEmitter.SseEventBuilder.class));
        stall.countDown();
    }

    @Test
    void aSubscriberThatFallsTooFarBehindLosesItsQueueToOneResync() throws Exception {
        UUID clusterId = UUID.randomUUID();
        int total = Subscriber.OUTBOUND_CAPACITY + 500;
        CountDownLatch stall = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    stall.await();
                    sent.add(render(invocation.getArgument(0)));
                    return null;
                })
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(emitter, Set.of("events"), null, null, null));

        for (int i = 0; i < total; i++) {
            hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree(Integer.toString(i)), Integer.toString(i)));
        }
        stall.countDown();

        String last = "id:" + (total - 1);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !sent.isEmpty() && sent.getLast().contains(last));
        assertThat(sent)
                .as("resync frames")
                .filteredOn(f -> f.contains("event:resync"))
                .hasSize(1);
        assertThat(sent).as("frames written").hasSizeLessThan(total);
    }

    @Test
    void framesAreWrittenInTheOrderTheyArrived() throws Exception {
        UUID clusterId = UUID.randomUUID();
        List<String> sent = new CopyOnWriteArrayList<>();
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
                    sent.add(render(invocation.getArgument(0)));
                    return null;
                })
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        hub.register(clusterId, new Subscriber(emitter, Set.of("events"), null, null, null));

        for (int i = 0; i < 300; i++) {
            hub.onFrame(new BusFrame(clusterId, "events", mapper.readTree(Integer.toString(i)), Integer.toString(i)));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 300);
        for (int i = 0; i < 300; i++) {
            assertThat(sent.get(i)).contains("id:" + i + "\n");
        }
    }

    private static String render(SseEmitter.SseEventBuilder builder) {
        StringBuilder out = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            out.append(part.getData());
        }
        return out.toString();
    }

    @Test
    void removeDeregistersOneSubscriber() {
        UUID clusterId = UUID.randomUUID();
        Subscriber s = new Subscriber(mock(SseEmitter.class), Set.of("topology"), null, null, null);
        hub.register(clusterId, s);
        hub.remove(clusterId, s);
        assertThat(hub.subscriberCount(clusterId)).isZero();
    }

    @Test
    void aSubscriberIsSentOnlyWhatItsAccessLeavesOfAFrame() throws IOException {
        SseHub trimming = new SseHub(
                bus,
                mapper,
                (subscriber, clusterId, frame) -> {
                    if (frame.about() == null) {
                        return null;
                    }
                    List<String> kept = frame.about().queues().stream()
                            .filter(q -> q.startsWith("orders."))
                            .toList();
                    return kept.isEmpty()
                            ? null
                            : new Subscriber.Held(
                                    frame.topic(), frame.data(), frame.id(), BusFrame.About.of(kept, List.of()));
                },
                new SimpleMeterRegistry());
        doAnswer(invocation -> {
                    trimming.onFrame((BusFrame) invocation.getArgument(0));
                    return null;
                })
                .when(bus)
                .publish(any(BusMessage.class));
        UUID clusterId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        trimming.register(clusterId, new Subscriber(emitter, Set.of("subscriptions"), null, null, null));

        trimming.publishAbout(clusterId, "subscriptions", List.of("billing.in"), List.of());
        trimming.publish(clusterId, "subscriptions");
        trimming.publishAbout(clusterId, "subscriptions", List.of("orders.in", "billing.in"), List.of());

        ArgumentCaptor<SseEmitter.SseEventBuilder> sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, timeout(2_000)).send(sent.capture());
        verify(emitter, after(200).times(1)).send(any(SseEmitter.SseEventBuilder.class));
        String wire = sent.getValue().build().stream()
                .map(d -> String.valueOf(d.getData()))
                .collect(Collectors.joining());
        assertThat(wire).contains("orders.in").doesNotContain("billing.in");
    }
}
