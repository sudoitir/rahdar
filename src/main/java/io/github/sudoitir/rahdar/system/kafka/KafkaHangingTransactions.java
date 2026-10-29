package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Resolve;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.openbroker.operation.Resolution;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingTransaction;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Hanging transactions (KIP-664, ADR-0216): open on a partition, past the longest a transaction may last and unknown to
 * the coordinator, which aborts the ones it knows within seconds. One holds the partition's last stable offset back for
 * ever, so a read-committed consumer stalls behind it. They are found on demand, for the cluster or one topic, because
 * finding them reads every partition in scope; they are never on a watch or a screen's refresh.
 */
@Service
@RequiredArgsConstructor
public class KafkaHangingTransactions {

    /** The SDK's kind of a hanging transaction, with its attributes (OpenBroker 0.6.2). */
    static final KindId KIND = KindId.of("kafka.hanging-transaction");

    private static final String PRODUCER_EPOCH = "kafka.producer_epoch";
    private static final String START_OFFSET = "kafka.transaction_start_offset";
    private static final String HELD_BACK = "kafka.records_held_back";
    private static final String AGE_MS = "kafka.hanging_age_ms";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    /** The hanging transactions of the cluster, or of one topic, at most {@code limit} (default 100, at most 500). */
    public HangingPage find(UUID clusterId, String topic, String cursor, Integer limit) {
        access.requireCluster(clusterId, KafkaModule.TRANSACTION_READ);
        ResourceQuery query = ResourceQuery.of(KIND)
                .limit(limit == null ? KafkaGroups.DEFAULT_LIMIT : Math.clamp(limit, 1, KafkaGroups.MAX_LIMIT));
        if (topic != null && !topic.isBlank()) {
            query = query.where(Map.of("topic", topic));
        }
        if (cursor != null && !cursor.isBlank()) {
            query = query.continueFrom(cursor);
        }
        ResourcePage page = KafkaGroups.read(gateway.cluster(clusterId), query);
        return new HangingPage(
                page.items().stream().map(KafkaHangingTransactions::row).toList(),
                page.continueToken().orElse(null));
    }

    /** What aborting one would do, and the state key it pins. */
    public HangingAbortPreview abortPreview(UUID clusterId, String topic, int partition, long producerId) {
        access.requireCluster(clusterId, KafkaModule.TRANSACTION_ABORT);
        Preview preview = KafkaFailures.call(() -> gateway.cluster(clusterId)
                .operations()
                .preview(id(topic, partition, producerId), new Resolve(Resolution.ROLLBACK), Scope.whole()));
        long held =
                switch (preview.estimate().count()) {
                    case Count.Exact(var n) -> n;
                    case Count.Estimate(var n) -> n;
                    case Count.Unavailable(var _) -> 0L;
                };
        return new HangingAbortPreview(
                topic,
                partition,
                producerId,
                "Aborting rolls the transaction back on " + topic + "-" + partition + " and releases " + held
                        + " record(s) it holds back. The producer " + producerId
                        + " is fenced: its next write fails and it must restart.",
                held,
                preview.etag());
    }

    static ResourceId id(String topic, int partition, long producerId) {
        return new ResourceId(KIND, ResourcePath.of(topic, String.valueOf(partition), String.valueOf(producerId)));
    }

    private static HangingTransaction row(Resource r) {
        List<String> path = r.id().path().segments();
        return new HangingTransaction(
                r.id().format(),
                path.get(0),
                Integer.parseInt(path.get(1)),
                Long.parseLong(path.get(2)),
                number(r, PRODUCER_EPOCH),
                number(r, START_OFFSET),
                number(r, HELD_BACK),
                number(r, AGE_MS));
    }

    private static Long number(Resource r, String attribute) {
        return r.attributes().raw(attribute).map(v -> ((Number) v).longValue()).orElse(null);
    }
}
