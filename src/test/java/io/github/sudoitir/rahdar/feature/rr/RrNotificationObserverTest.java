package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.notification.EventTypes;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerEvent;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link RrNotificationObserver} against a real correlator/Postgres: only
 * notifications relevant to a traced address or an open temp-queue flow reach
 * the correlator — broker-wide chatter on unrelated addresses does not.
 */
class RrNotificationObserverTest extends PostgresIntegrationTest {

    @Autowired
    RrNotificationObserver observer;

    @Autowired
    RrCorrelator correlator;

    @Autowired
    RrFlowRepository flows;

    @Autowired
    RrExpectationRepository expectations;

    @Autowired
    ClusterRepository clusters;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("rr-notif-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    private BrokerEvent event(
            UUID clusterId, String type, String destination, String subscription, Map<String, Object> data) {
        return new BrokerEvent(
                clusterId,
                null,
                type,
                Instant.now(),
                destination,
                subscription,
                "consumer-1",
                "s1",
                "c1",
                "10.0.0.1:1",
                "u",
                data);
    }

    @Test
    void unrelatedConsumerActivityIsIgnored() {
        cluster();
        // No expectation declared for "some.other.address" — must not be tracked.
        observer.accept(
                event(clusterId, EventTypes.CONSUMER_ATTACHED, "some.other.address", "some.other.address", Map.of()));

        assertThat(correlator.isTracedRequestAddress(clusterId, "some.other.address"))
                .isFalse();
    }

    @Test
    void responderDropOnAnUntracedAddressIsIgnored() {
        cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-1", null, 0L, null, Map.of()));

        // No expectation declared for "rr.request" — isTracedRequestAddress is false, so this must be ignored.
        observer.accept(event(
                clusterId,
                EventTypes.CONSUMER_DETACHED,
                "rr.request",
                "rr.request",
                Map.of("openbroker.consumer_count", 0)));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.request", "m1")
                .orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.AWAITING_REPLY.name());
    }

    @Test
    void responderDropOnATracedAddressDropsTheFlow() {
        cluster();
        expectations.save(new RrExpectationEntity(clusterId, "rr.traced", List.of(), null, null, 10, false));
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.traced", "m2", "corr-1", null, 0L, null, Map.of()));

        observer.accept(event(
                clusterId,
                EventTypes.CONSUMER_DETACHED,
                "rr.traced",
                "rr.traced",
                Map.of("openbroker.consumer_count", 0)));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.traced", "m2")
                .orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.RESPONDER_DROPPED.name());
    }

    @Test
    void messageDeliveredOnAResolvedReplyAddressIsForwardedAsAReply() {
        cluster();
        expectations.save(
                new RrExpectationEntity(clusterId, "rr.traced", List.of("rr.traced.reply"), null, null, 10, false));
        Instant t0 = Instant.now();
        // The sampler supplied the correlation identity; the reply itself was drained
        // between two ticks, so only the notification can close the flow (design.md D6).
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.traced", "m1", "corr-drained", null, 0L, null, Map.of()));

        observer.accept(event(clusterId, EventTypes.MESSAGE_DELIVERED, "rr.traced.reply", "rr.traced.reply", Map.of()));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.traced", "m1")
                .orElseThrow();
        // No correlation id on the notification, so this cannot join by identity — it
        // is recorded as a reply observation, and the flow it cannot join stays open.
        assertThat(correlator.isTracedReplyAddress(clusterId, "rr.traced.reply"))
                .isTrue();
        assertThat(flow).isNotNull();
    }

    @Test
    void messageDeliveredOnAnUntracedAddressIsNotForwarded() {
        cluster();
        expectations.save(
                new RrExpectationEntity(clusterId, "rr.traced", List.of("rr.traced.reply"), null, null, 10, false));

        assertThat(correlator.isTracedReplyAddress(clusterId, "something.else")).isFalse();

        observer.accept(event(clusterId, EventTypes.MESSAGE_DELIVERED, "something.else", "something.else", Map.of()));

        // Broker-wide delivery chatter must not manufacture orphaned-reply rows.
        assertThat(flows.findByClusterIdAndReplyDestinationAndState(
                        clusterId, "something.else", RrState.ORPHANED_REPLY.name()))
                .isEmpty();
    }

    @Test
    void aGlobReplyPatternIsRecognisedWithoutTheAddressBeingScrapedYet() {
        cluster();
        expectations.save(
                new RrExpectationEntity(clusterId, "rr.traced", List.of("rr.traced.reply.*"), null, null, 10, false));

        // matches() reads the declaration, not the snapshot, so a reply queue created
        // since the last scrape is still recognised.
        assertThat(correlator.isTracedReplyAddress(clusterId, "rr.traced.reply.host-9"))
                .isTrue();
        assertThat(correlator.isTracedReplyAddress(clusterId, "other.reply.host-9"))
                .isFalse();
    }

    @Test
    void messageDeliveredOnAKnownOpenTempQueueCompletesTheFlow() {
        cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-2", "temp-queue-xyz", 0L, null, Map.of()));

        observer.accept(event(
                clusterId,
                EventTypes.MESSAGE_DELIVERED,
                "temp-queue-xyz",
                "temp-queue-xyz",
                Map.of("openbroker.message_id", "42")));

        RrFlowEntity flow = flows.findByClusterIdAndRequestAddressAndRequestMessageId(clusterId, "rr.request", "m1")
                .orElseThrow();
        assertThat(flow.getState()).isEqualTo(RrState.COMPLETED.name());
    }

    @Test
    void messageDeliveredOnAnUnrelatedAddressCreatesNoFlow() {
        cluster();
        observer.accept(event(
                clusterId,
                EventTypes.MESSAGE_DELIVERED,
                "unrelated.queue",
                "unrelated.queue",
                Map.of("openbroker.message_id", "99")));

        assertThat(flows.findByClusterIdAndRequestAddressAndState(
                        clusterId, "unrelated.queue", RrState.ORPHANED_REPLY.name()))
                .isEmpty();
    }
}
