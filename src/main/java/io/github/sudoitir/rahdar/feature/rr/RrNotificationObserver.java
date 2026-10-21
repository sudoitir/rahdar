package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.notification.EventTypes;
import io.github.sudoitir.rahdar.platform.broker.BrokerEvent;
import io.github.sudoitir.rahdar.platform.broker.BrokerEventSink;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps broker events to lifecycle {@link Observation}s for {@link RrCorrelator} (design.md D2):
 * responder presence and temp-queue teardown. The broker SDK leaves out events about its own
 * notification plumbing, so what arrives here happened on the broker.
 *
 * <p>The broker reports consumer, binding and delivery events for <em>every</em> destination, not just
 * traced ones — every branch below checks relevance against the correlator before forwarding,
 * otherwise ordinary broker-wide traffic would flood {@code rr_flow} with irrelevant rows and the
 * in-memory responder tracker with entries for destinations nobody asked Rahdar to trace.
 *
 * <p>A delivery on a temp queue closes the loop for the temporary-reply-queue pattern: the sampler
 * never polls a per-request temp queue (it has no fixed name to poll), so a delivery to it is the only
 * signal a reply arrived. The subscription alone identifies the flow — the temp queue is 1:1 per
 * request — so no correlation id is needed for that observation.
 *
 * <p>A delivery on a <em>resolved reply address</em> is forwarded too (design.md D6). The shared-queue
 * pattern is otherwise sampled-only, and the sampler browses page 1 every few seconds, so a reply
 * consumed in milliseconds is never there when it looks. This observation carries no correlation id,
 * so it completes a flow only where sampling already supplied the correlation identity — an
 * improvement in coverage, not a replacement for browsing.
 */
@Component
@RequiredArgsConstructor
public class RrNotificationObserver implements BrokerEventSink {

    private final RrCorrelator correlator;

    @Override
    public void accept(BrokerEvent event) {
        switch (event.type()) {
            case EventTypes.CONSUMER_ATTACHED -> responderUp(event);
            case EventTypes.CONSUMER_DETACHED -> responderDown(event);
            case EventTypes.RESOURCE_DELETED -> subscriptionGone(event);
            case EventTypes.MESSAGE_DELIVERED -> delivered(event);
            case EventTypes.MESSAGE_EXPIRED ->
                correlator.accept(new Observation.MessageExpired(
                        event.clusterId(), event.nodeId(), event.occurredAt(), event.destination(), messageId(event)));
            default -> {
                // Every other event is retained by BrokerEventWriter but carries no rr_flow signal.
            }
        }
    }

    private void responderUp(BrokerEvent event) {
        if (correlator.isTracedRequestDestination(event.clusterId(), event.destination())) {
            correlator.accept(new Observation.ResponderUp(
                    event.clusterId(), event.nodeId(), event.occurredAt(), event.destination(), event.consumer()));
        }
    }

    private void responderDown(BrokerEvent event) {
        if (correlator.isTracedRequestDestination(event.clusterId(), event.destination())) {
            correlator.accept(new Observation.ResponderDown(
                    event.clusterId(),
                    event.nodeId(),
                    event.occurredAt(),
                    event.destination(),
                    event.consumer(),
                    consumerCount(event)));
        }
    }

    /** A subscription going away; a destination going away names none. */
    private void subscriptionGone(BrokerEvent event) {
        if (event.subscription() != null && correlator.hasOpenTempQueueFlow(event.clusterId(), event.subscription())) {
            correlator.accept(new Observation.TempQueueUnbound(
                    event.clusterId(), event.nodeId(), event.occurredAt(), event.subscription()));
        }
    }

    /**
     * Two ways a delivery is a reply: it closes a temp-queue flow, or it landed on an address some
     * expectation's reply patterns cover.
     */
    private void delivered(BrokerEvent event) {
        if (correlator.hasOpenTempQueueFlow(event.clusterId(), event.subscription())
                || correlator.isTracedReplyDestination(event.clusterId(), event.destination())
                || correlator.isTracedReplyDestination(event.clusterId(), event.subscription())) {
            correlator.accept(new Observation.ReplySeen(
                    event.clusterId(),
                    event.nodeId(),
                    event.occurredAt(),
                    event.subscription(),
                    messageId(event),
                    null,
                    null,
                    Map.of()));
        }
    }

    private static int consumerCount(BrokerEvent event) {
        Object v = event.data().get(StandardAttributes.CONSUMER_COUNT.name());
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static String messageId(BrokerEvent event) {
        Object v = event.data().get(StandardAttributes.MESSAGE_ID.name());
        return v == null ? null : v.toString();
    }
}
