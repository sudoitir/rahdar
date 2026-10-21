package io.github.sudoitir.rahdar.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.kernel.replica.BusFrame;
import io.github.sudoitir.rahdar.kernel.stream.Subscriber.Held;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** The bounds of a subscriber's queue and the life of its drain thread (ADR-0197). */
class SubscriberTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AtomicLong clock = new AtomicLong(1);
    private final Map<String, AtomicInteger> dropped = new ConcurrentHashMap<>();
    private final AtomicInteger closings = new AtomicInteger();
    private final Subscriber.Listener listener = new Subscriber.Listener(
            (reason, frames) ->
                    dropped.computeIfAbsent(reason, r -> new AtomicInteger()).addAndGet(frames),
            closings::incrementAndGet);
    private final CopyOnWriteArrayList<String> written = new CopyOnWriteArrayList<>();
    private final AtomicInteger completed = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);

    private Subscriber subscriber() {
        Subscriber s = new Subscriber(new SseEmitter(0L), Set.of("subscriptions", "events"), null, null, null);
        s.nanos = clock::get;
        return s;
    }

    /** Starts the drain on a writer that blocks on the first frame until released. */
    private void drainBlocked(Subscriber s) {
        s.startDrain(
                frame -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    written.add(frame.topic());
                    return true;
                },
                completed::incrementAndGet,
                listener);
    }

    private static Held signal(String topic, String queue) {
        return new Held(topic, null, null, BusFrame.About.queue(queue));
    }

    private static Held event(int i, int size) {
        return new Held("events", MAPPER.valueToTree("x".repeat(size)), Integer.toString(i), null);
    }

    @Test
    void aQueuedSignalIsReplacedByANewerOneAboutTheSameThing() throws Exception {
        Subscriber s = subscriber();
        drainBlocked(s);
        s.enqueue(signal("subscriptions", "block")); // taken by the writer, which blocks on it
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 50; i++) {
            s.enqueue(signal("subscriptions", "orders"));
            s.enqueue(signal("subscriptions", "payments"));
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(2)).until(() -> written.size() == 3);
        assertThat(dropped.get("coalesced")).hasValue(98);
        assertThat(dropped).doesNotContainKey("overflow");
    }

    @Test
    void theQueueNeverHoldsMoreThanItsFrameBound() {
        Subscriber s = subscriber();
        drainBlocked(s);
        s.enqueue(event(0, 1));
        for (int i = 1; i <= Subscriber.OUTBOUND_CAPACITY + 10; i++) {
            s.enqueue(event(i, 1));
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(2)).until(() -> written.contains("resync"));
        assertThat(written).hasSizeLessThanOrEqualTo(Subscriber.OUTBOUND_CAPACITY);
        assertThat(dropped.get("overflow").get()).isPositive();
    }

    @Test
    void theQueueNeverHoldsMoreThanItsByteBound() {
        Subscriber s = subscriber();
        drainBlocked(s);
        s.enqueue(event(0, 1));
        int each = 100_000;
        for (int i = 1; i <= (int) (Subscriber.OUTBOUND_BYTES / each) + 2; i++) {
            s.enqueue(event(i, each));
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(2)).until(() -> written.contains("resync"));
        assertThat(written).hasSizeLessThan(12);
    }

    @Test
    void threeOverflowsInAMinuteSendReconnectAndEndTheStream() {
        Subscriber s = subscriber();
        drainBlocked(s);
        s.enqueue(event(0, 1));
        for (int round = 0; round < Subscriber.OVERFLOWS_BEFORE_DROP; round++) {
            for (int i = 0; i <= Subscriber.OUTBOUND_CAPACITY; i++) {
                s.enqueue(event(i, 1));
            }
            clock.addAndGet(Duration.ofSeconds(10).toNanos());
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(2)).until(() -> completed.get() == 1);
        assertThat(written).last().isEqualTo(SseHub.RECONNECT);
        assertThat(closings).hasValue(1);
    }

    @Test
    void overflowsMoreThanAMinuteApartAreForgiven() {
        Subscriber s = subscriber();
        s.stopDrain(); // nothing writes, so the queue only fills
        s.startDrain(frame -> true, completed::incrementAndGet, listener);
        for (int round = 0; round < 2 * Subscriber.OVERFLOWS_BEFORE_DROP; round++) {
            for (int i = 0; i <= Subscriber.OUTBOUND_CAPACITY; i++) {
                s.enqueue(event(i, 1));
            }
            clock.addAndGet(Subscriber.OVERFLOW_WINDOW.toNanos() + 1);
        }

        assertThat(closings).hasValue(0);
        assertThat(dropped.get("overflow").get()).isGreaterThanOrEqualTo(2 * Subscriber.OVERFLOWS_BEFORE_DROP);
    }

    @Test
    void aWriteBlockedForTooLongClosesTheStreamAndEndsTheWriter() throws Exception {
        Subscriber s = subscriber();
        AtomicBoolean interrupted = new AtomicBoolean();
        s.startDrain(
                frame -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException _) {
                        interrupted.set(true);
                    }
                    return false;
                },
                completed::incrementAndGet,
                listener);
        s.enqueue(event(0, 1));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        clock.addAndGet(Subscriber.WRITE_BLOCKED_LIMIT.toNanos() + 1);

        s.enqueue(event(1, 1));

        await().atMost(Duration.ofSeconds(2)).until(() -> completed.get() == 1);
        assertThat(interrupted).isTrue();
        assertThat(closings).hasValue(1);
        assertThat(dropped).containsKey("blocked");
    }

    @Test
    void aHoldBufferPastItsBoundBecomesAResync() {
        Subscriber s = subscriber();
        s.startDrain(
                frame -> {
                    written.add(frame.topic());
                    return true;
                },
                completed::incrementAndGet,
                listener);
        s.buffer();
        for (int i = 0; i <= Subscriber.OUTBOUND_CAPACITY + 5; i++) {
            s.deliver(event(i, 1));
        }
        s.release(Map.of());

        await().atMost(Duration.ofSeconds(2)).until(() -> !written.isEmpty());
        assertThat(written).containsExactly(SseHub.RESYNC);
        assertThat(dropped.get("replay").get()).isPositive();
        assertThat(closings).hasValue(0);
    }

    @Test
    void theWriterEndsWhenTheStreamIsCompleted() {
        Subscriber s = subscriber();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        s.startDrain(
                frame -> {
                    threads.add(Thread.currentThread());
                    return true;
                },
                completed::incrementAndGet,
                listener);
        s.enqueue(event(0, 1));
        s.finish();

        await().atMost(Duration.ofSeconds(2)).until(() -> completed.get() == 1);
        await().atMost(Duration.ofSeconds(2)).until(() -> threads.getFirst().getState() == Thread.State.TERMINATED);
    }

    @Test
    void theWriterEndsWhenAWriteFails() {
        Subscriber s = subscriber();
        s.startDrain(frame -> false, completed::incrementAndGet, listener);
        s.enqueue(event(0, 1));

        await().atMost(Duration.ofSeconds(2)).until(() -> completed.get() == 1);
    }

    @Test
    void noWriterStartsForAStreamThatEndedBeforeItStarted() throws Exception {
        Subscriber s = subscriber();
        s.stopDrain();
        s.startDrain(
                frame -> {
                    started.countDown();
                    return true;
                },
                completed::incrementAndGet,
                listener);
        s.enqueue(event(0, 1));

        assertThat(started.await(300, TimeUnit.MILLISECONDS))
                .as("a writer started")
                .isFalse();
    }

    @Test
    void theWriterEndsWhenTheStreamIsRemoved() {
        Subscriber s = subscriber();
        s.startDrain(frame -> true, completed::incrementAndGet, listener);
        s.stopDrain();

        await().atMost(Duration.ofSeconds(2)).until(() -> completed.get() == 1);
    }
}
