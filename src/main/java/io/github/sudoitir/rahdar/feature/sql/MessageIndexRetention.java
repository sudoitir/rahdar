package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps each subscription's own retention promise on the shared message index (ADR-0059). Each
 * subscription may carry a shorter retention than the store, and a partition holds rows from every
 * subscription, so {@link MessageIndexStore} cannot honour that by dropping a partition. Rows that
 * outlive their own subscription are removed here by a bulk range delete: set-based, one statement
 * per retention, which is the only way a per-subscription promise can be kept on a shared partition.
 */
@Component
@Slf4j
public class MessageIndexRetention {

    private final NamedParameterJdbcTemplate jdbc;
    private final MessageIndexSubscriptionRepository subscriptions;
    private final QueueSnapshots snapshots;
    private final CaptureAddresses captureAddresses;
    private final LifecycleRegistry lifecycle;

    public MessageIndexRetention(
            NamedParameterJdbcTemplate jdbc,
            MessageIndexSubscriptionRepository subscriptions,
            QueueSnapshots snapshots,
            CaptureAddresses captureAddresses,
            LifecycleRegistry lifecycle) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.captureAddresses = captureAddresses;
        this.lifecycle = lifecycle;
    }

    /**
     * Remove what has outlived its own subscription while its partition is still
     * needed by a longer-retention one, or by the store's own retention.
     *
     * <p>Retention belongs to a queue, not to a subscription: two subscriptions can
     * match the same queue, and the longer of them is the promise that queue is kept
     * under. So the queues are resolved in Java — the pattern is an Artemis wildcard,
     * not a SQL {@code LIKE} — grouped by the longest retention that claims them, and
     * deleted one range at a time. A queue held for the store's whole retention is
     * skipped entirely; {@link MessageIndexStore} reclaims it by dropping the partition.
     */
    public void expire() {
        int storeDays =
                (int) lifecycle.retention(MessageIndexStore.ID).orElseThrow().toDays();
        for (UUID clusterId : subscriptions.findAll().stream()
                .map(MessageIndexSubscriptionEntity::getClusterId)
                .distinct()
                .toList()) {
            Map<Integer, List<String>> byRetention = queuesByRetention(clusterId);
            byRetention.forEach((retentionDays, queues) -> {
                if (retentionDays >= storeDays || queues.isEmpty()) {
                    return;
                }
                Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
                String placeholders = String.join(", ", Collections.nCopies(queues.size(), "?"));
                List<Object> binds = new ArrayList<>();
                binds.add(clusterId);
                binds.add(Timestamp.from(cutoff));
                binds.addAll(queues);
                int removed = jdbc.getJdbcTemplate()
                        .update(
                                "DELETE FROM message_index WHERE cluster_id = ? AND observed_at < ? AND subscription IN ("
                                        + placeholders + ')',
                                binds.toArray());
                if (removed > 0) {
                    log.info("Expired {} indexed messages past their {}-day retention", removed, retentionDays);
                }
            });
        }
    }

    /**
     * Every name rows are stored under in one cluster, grouped by the longest retention claiming
     * it. A sampled row is stored under its queue; a captured row under its address, which is
     * not the queue name for a multicast address — so both are claimed, or captured payload
     * would be kept past its subscription's retention.
     */
    private Map<Integer, List<String>> queuesByRetention(UUID clusterId) {
        List<MessageIndexSubscriptionEntity> ofCluster = subscriptions.findByClusterId(clusterId);
        Map<String, Integer> longest = new LinkedHashMap<>();
        snapshots.forCluster(clusterId).stream()
                .map(QueueSnapshot::queueName)
                .distinct()
                .forEach(queue -> ofCluster.stream()
                        .filter(s -> QueueNamePattern.matches(s.getSubscriptionPattern(), queue))
                        .mapToInt(MessageIndexSubscriptionEntity::getRetentionDays)
                        .max()
                        .ifPresent(retention -> longest.merge(queue, retention, Math::max)));
        for (MessageIndexSubscriptionEntity subscription : ofCluster) {
            if (subscription.getMode() == CaptureMode.CAPTURE) {
                for (String address : captureAddresses.of(clusterId, subscription)) {
                    longest.merge(address, subscription.getRetentionDays(), Math::max);
                }
            }
        }
        Map<Integer, List<String>> byRetention = new LinkedHashMap<>();
        longest.forEach((name, retention) ->
                byRetention.computeIfAbsent(retention, k -> new ArrayList<>()).add(name));
        return byRetention;
    }
}
