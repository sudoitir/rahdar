package io.github.sudoitir.rahdar.platform.scrape;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A queue whose backlog became unknown has moved, and an unknown backlog is never mistaken for an empty one. */
class StreamSignalsTest {

    private final SseHub hub = mock(SseHub.class);
    private final StreamSignals signals = new StreamSignals(hub);
    private final UUID cluster = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();

    private QueueRow row(Long backlog, long consumers) {
        return new QueueRow(
                cluster, node, "orders", "orders", null, true, backlog, consumers, 0, 0, 0, 0, 0, false, "k", Map.of());
    }

    @Test
    void anUnchangedScrapeIsSilentAndABacklogThatBecameUnknownIsAMove() {
        signals.afterQueueScrape(cluster, List.of(row(0L, 1)));
        signals.afterQueueScrape(cluster, List.of(row(0L, 1)));
        verify(hub, never()).publishAbout(any(), any(), any(), any());

        signals.afterQueueScrape(cluster, List.of(row(null, 1)));

        verify(hub).publishAbout(eq(cluster), eq("subscriptions"), eq(Set.of("orders")), any());
    }

    @Test
    void anUnknownBacklogAndAZeroBacklogWithAnotherCountDoNotCollide() {
        signals.afterQueueScrape(cluster, List.of(row(0L, 0)));

        signals.afterQueueScrape(cluster, List.of(row(null, 1)));

        verify(hub).publishAbout(eq(cluster), eq("subscriptions"), eq(Set.of("orders")), any());
    }
}
