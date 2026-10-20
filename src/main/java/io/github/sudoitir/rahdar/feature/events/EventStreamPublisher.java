package io.github.sudoitir.rahdar.feature.events;

import io.github.sudoitir.openbroker.notification.EventTypes;
import io.github.sudoitir.rahdar.feature.events.internal.persistence.BrokerEventEntity;
import io.github.sudoitir.rahdar.feature.events.internal.persistence.BrokerEventRepository;
import io.github.sudoitir.rahdar.kernel.replica.BusEvents;
import io.github.sudoitir.rahdar.kernel.replica.BusFrame;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.github.sudoitir.rahdar.platform.broker.BrokerEvent;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans a flushed batch of broker events out over SSE (ADR-0027, ADR-0152). The replica that wrote
 * the batch announces its seqs on the bus, in the transaction that stores them; every replica, that one included, loads the rows when the
 * announcement arrives and sends each as a frame on the data-bearing {@code events} topic, with its
 * {@code seq} as the SSE id. So a frame is delivered once per replica, and only the writer nudges the
 * resource-view signal topics the events imply are stale, through {@link TopicCoalescer} (D11); those
 * signals cross the bus themselves.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventStreamPublisher implements BrokerEventPublisher {

    private final RahdarBus bus;
    private final ApplicationEventPublisher frames;
    private final ObjectMapper mapper;
    private final TopicCoalescer coalescer;
    private final BrokerEventService events;
    private final BrokerEventRepository repository;

    /** One thread, so batches load and go out in the order they were announced, off the bus dispatch thread. */
    private final ExecutorService loader = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("events-load").daemon().factory());

    /**
     * Runs inside the flush transaction: the announcement goes out when it commits. The signal topics
     * are nudged here too; a rolled-back flush costs at most one needless refetch.
     */
    @Override
    public void written(List<Long> seqs, List<BrokerEvent> batch) {
        if (seqs.isEmpty()) {
            return;
        }
        bus.publish(new BusEvents(seqs));
        for (BrokerEvent e : batch) {
            String derived = derivedTopicOf(e.type());
            if (derived != null) {
                coalescer.touch(e.clusterId(), derived);
            }
        }
    }

    /**
     * Announced by some replica: hand each stored row to the local subscribers of its cluster. The rows
     * are read on {@code events-load}, not on the bus dispatch thread, which must not wait for the database.
     */
    @EventListener
    void on(BusEvents announced) {
        loader.execute(() -> {
            try {
                load(announced);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not load {} announced broker events; they are not streamed from this replica",
                        announced.seqs().size(),
                        e);
            }
        });
    }

    @PreDestroy
    void close() {
        loader.shutdownNow();
    }

    private void load(BusEvents announced) {
        for (BrokerEventEntity e : repository.findBySeqInOrderBySeqAsc(announced.seqs())) {
            frames.publishEvent(new BusFrame(
                    e.getClusterId(),
                    EventsModule.TOPIC,
                    mapper.valueToTree(events.toView(e)),
                    Long.toString(e.getSeq())));
        }
    }

    static String derivedTopicOf(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            // CONSUMER_SLOW is the broker's own slow-consumer verdict (ADR-0044) and is
            // authoritative over Rahdar's derived rule. It names the consumer, so unlike
            // the derived rule it attributes to a named consumer.
            case EventTypes.CONSUMER_ATTACHED, EventTypes.CONSUMER_DETACHED, EventTypes.CONSUMER_SLOW -> "consumers";
            case EventTypes.CHANNEL_OPENED, EventTypes.CHANNEL_CLOSED -> "sessions";
            case EventTypes.CLIENT_CONNECTED, EventTypes.CLIENT_DISCONNECTED -> "connections";
            case EventTypes.RESOURCE_CREATED, EventTypes.RESOURCE_DELETED -> "queues";
            default -> null;
        };
    }
}
