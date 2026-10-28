package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Resolve;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.openbroker.operation.Resolution;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BlockedPartition;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Transactional ids, the ones open longer than their timeout, and what aborting one releases (ADR-0216). */
@Service
@RequiredArgsConstructor
public class KafkaTransactions {

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public TransactionPage list(UUID clusterId, String cursor, Integer limit) {
        access.requireCluster(clusterId, KafkaModule.TRANSACTION_READ);
        ResourceQuery query = ResourceQuery.of(KafkaKinds.TRANSACTIONAL_ID)
                .limit(limit == null ? KafkaGroups.DEFAULT_LIMIT : Math.max(1, Math.min(limit, KafkaGroups.MAX_LIMIT)));
        if (cursor != null && !cursor.isBlank()) {
            query = query.continueFrom(cursor);
        }
        ResourcePage page = KafkaGroups.read(gateway.cluster(clusterId), query);
        Instant now = Instant.now();
        return new TransactionPage(
                page.items().stream().map(t -> row(t, now)).toList(),
                page.continueToken().orElse(null));
    }

    /**
     * How long past its timeout an open transaction is left to the coordinator, which aborts it by itself within
     * about ten seconds (its cleanup interval). Only one still open after this has been missed, and is the one an
     * operator may abort.
     */
    static final Duration COORDINATOR_GRACE = Duration.ofSeconds(60);

    public AbortPreview abortPreview(UUID clusterId, String id) {
        access.requireCluster(clusterId, KafkaModule.TRANSACTION_ABORT);
        ClusterConnection connection = gateway.cluster(clusterId);
        Resource transaction = find(connection, id);
        Preview preview = KafkaFailures.call(() ->
                connection.operations().preview(transaction.id(), new Resolve(Resolution.ROLLBACK), Scope.whole()));
        List<BlockedPartition> blocked = blocked(transaction);
        String state = KafkaGroups.text(transaction, KafkaKinds.TRANSACTION_STATE);
        String producer = transaction
                .attributes()
                .raw(KafkaKinds.PRODUCER_ID)
                .map(String::valueOf)
                .orElse("?");
        boolean abortable = hanging(transaction, Instant.now());
        String effect = abortable
                ? "Aborting rolls back the open transaction and releases "
                        + (blocked.isEmpty()
                                ? "its partitions"
                                : blocked.size() == 1 ? "1 blocked partition" : blocked.size() + " blocked partitions")
                        + ". The producer " + producer + " is fenced: its next write fails and it must restart."
                : open(state)
                        ? "The transaction is open and within its timeout, or just past it: the coordinator aborts"
                                + " one past its timeout by itself, so it is left to it."
                        : "The transaction is " + state + ", not open, so there is nothing to abort.";
        return new AbortPreview(id, state, blocked, effect, abortable, preview.etag());
    }

    Resource find(ClusterConnection connection, String id) {
        return KafkaGroups.read(
                        connection,
                        ResourceQuery.of(KafkaKinds.TRANSACTIONAL_ID).named(KafkaGroups.escape(id)))
                .items()
                .stream()
                .filter(t -> t.id().name().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("transaction", id));
    }

    static ResourceId idOf(String id) {
        return new ResourceId(KafkaKinds.TRANSACTIONAL_ID, ResourcePath.of(id));
    }

    static boolean open(String state) {
        return "ongoing".equals(state) || state.startsWith("prepare");
    }

    private static TransactionRow row(Resource t, Instant now) {
        String state = KafkaGroups.text(t, KafkaKinds.TRANSACTION_STATE);
        long timeout = t.attributes()
                .raw(KafkaKinds.TRANSACTION_TIMEOUT_MS)
                .map(v -> ((Number) v).longValue())
                .orElse(0L);
        Instant start = t.attributes()
                .raw(KafkaKinds.TRANSACTION_START)
                .filter(Instant.class::isInstance)
                .map(Instant.class::cast)
                .orElse(null);
        return new TransactionRow(t.id().name(), state, start, timeout, hanging(t, now), blocked(t));
    }

    /** An open transaction the coordinator has not aborted past its timeout and cleanup: the only one to abort. */
    static boolean hanging(Resource t, Instant now) {
        String state = KafkaGroups.text(t, KafkaKinds.TRANSACTION_STATE);
        long timeout = t.attributes()
                .raw(KafkaKinds.TRANSACTION_TIMEOUT_MS)
                .map(v -> ((Number) v).longValue())
                .orElse(0L);
        Instant start = t.attributes()
                .raw(KafkaKinds.TRANSACTION_START)
                .filter(Instant.class::isInstance)
                .map(Instant.class::cast)
                .orElse(null);
        return open(state)
                && start != null
                && timeout > 0
                && Duration.between(start, now).toMillis() > timeout + COORDINATOR_GRACE.toMillis();
    }

    /** The partitions the transaction has touched, which a reader of committed records waits behind. */
    static List<BlockedPartition> blocked(Resource t) {
        Object raw = t.attributes().raw(KafkaKinds.TRANSACTION_PARTITIONS).orElse(List.of());
        return ((List<?>) raw)
                .stream()
                        .map(String::valueOf)
                        .map(text -> {
                            int dash = text.lastIndexOf('-');
                            return new BlockedPartition(
                                    text.substring(0, dash), Integer.parseInt(text.substring(dash + 1)));
                        })
                        .toList();
    }
}
