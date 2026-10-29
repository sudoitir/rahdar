package io.github.sudoitir.rahdar.feature.events;

import io.github.sudoitir.openbroker.notification.EventTypes;
import io.github.sudoitir.rahdar.kernel.core.PartitionProvisioner;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.platform.broker.BrokerEvent;
import io.github.sudoitir.rahdar.platform.broker.BrokerEventSink;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Buffered batch writer for {@code broker_event} (ADR-0028). Notifications arrive
 * on the Core client's drain thread; a synchronous insert per notification would
 * couple broker chatter to database latency. Instead {@link #accept} enqueues
 * without blocking, a scheduled {@link #flush} does one JDBC batch, and the
 * buffer is bounded: overflow is dropped and counted per cluster, surfaced by
 * the events API rather than silently.
 *
 * <p>A message delivery is the one event with one notification per message, so it is never a row of its
 * own: deliveries are counted in memory per destination and subscription, and each flush turns the counts
 * into one summary row per key. The rows grow with the active destinations and the flush interval, not with
 * traffic (ADR-0218). Every other type is stored verbatim.
 */
@Component
@Slf4j
public class BrokerEventWriter implements BrokerEventSink {

    private static final int BATCH_MAX = 500;

    /**
     * Held, for the transaction, by whoever is inserting. An identity value is taken when a row is inserted
     * and becomes visible when its transaction commits, so two replicas flushing at once could commit a
     * lower {@code seq} after a client was already sent a higher one, and a replay from that id would skip it.
     * One writer at a time makes commit order follow {@code seq} (ADR-0197).
     */
    static final String WRITE_LOCK = "broker_event.write";

    private static final String INSERT = """
            INSERT INTO broker_event
              (occurred_at, type, destination, subscription, consumer, channel,
               connection, remote_address, principal, data, cluster_id, node_id)
            VALUES
              (:occurredAt, :type, :destination, :subscription, :consumer, :channel,
               :connection, :remoteAddress, :principal, CAST(:data AS jsonb), :clusterId, :nodeId)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectProvider<BrokerEventPublisher> publisher;

    private final TransactionTemplate transaction;
    private final PartitionProvisioner partitions;

    private record TallyKey(UUID clusterId, UUID nodeId, String destination, String subscription) {}

    private static final class Tally {
        private long count;
        private Instant first;
        private Instant last;

        synchronized void add(Instant at) {
            count++;
            first = first == null || at.isBefore(first) ? at : first;
            last = last == null || at.isAfter(last) ? at : last;
        }
    }

    private final Map<TallyKey, Tally> delivered = new ConcurrentHashMap<>();

    private final LinkedBlockingDeque<BrokerEvent> buffer = new LinkedBlockingDeque<>();
    private final Map<UUID, AtomicLong> dropped = new ConcurrentHashMap<>();
    private volatile int capacity;

    public BrokerEventWriter(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            ObjectProvider<BrokerEventPublisher> publisher,
            PlatformTransactionManager transactions,
            PartitionProvisioner partitions,
            EventsProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactions);
        this.partitions = partitions;
        this.capacity = Math.max(1, properties.bufferSize());
    }

    /** Runtime override hook — {@code SettingsService} calls this when the setting changes. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public void accept(BrokerEvent event) {
        if (EventTypes.MESSAGE_DELIVERED.equals(event.type())) {
            delivered
                    .computeIfAbsent(
                            new TallyKey(event.clusterId(), event.nodeId(), event.destination(), event.subscription()),
                            k -> new Tally())
                    .add(event.occurredAt());
            return;
        }
        if (buffer.size() >= capacity) {
            dropped.computeIfAbsent(event.clusterId(), k -> new AtomicLong()).incrementAndGet();
            return;
        }
        buffer.add(event);
    }

    /** A cluster was deleted on any replica, once its transaction committed: its buffered events can no longer be stored. */
    @EventListener(condition = "#signal.kind() == 'cluster-deleted'")
    void onClusterDeleted(ReplicaSignal signal) {
        UUID clusterId = UUID.fromString(signal.key());
        buffer.removeIf(event -> event.clusterId().equals(clusterId));
        delivered.keySet().removeIf(key -> key.clusterId().equals(clusterId));
        dropped.remove(clusterId);
    }

    public long droppedFor(UUID clusterId) {
        AtomicLong count = dropped.get(clusterId);
        return count == null ? 0 : count.get();
    }

    /**
     * Scheduled by {@code JobRunner} on the settings-driven flush interval, and run once
     * more at shutdown. A batch whose insert fails is put back at the head of the buffer,
     * in order, and the failure is rethrown so the job reports it: a database outage delays
     * events rather than discarding the batch that happened to be in flight. A batch the
     * database rejects for a cluster or node that no longer exists is the exception: those
     * events can never be stored, so they are dropped and counted and the rest goes on.
     */
    public void flush() {
        summariseDeliveries();
        List<BrokerEvent> batch = new ArrayList<>(BATCH_MAX);
        buffer.drainTo(batch, BATCH_MAX);
        if (batch.isEmpty()) {
            return;
        }
        try {
            // received_at is the database's clock, so only a missed maintenance run can leave a day without a
            // partition.
            partitions.retryingNoPartition(() -> {
                transaction.executeWithoutResult(status -> write(batch));
                return null;
            });
        } catch (DataIntegrityViolationException _) {
            dropOrphans(batch);
        } catch (RuntimeException e) {
            requeue(batch);
            throw e;
        }
    }

    /** One row per destination and subscription that saw deliveries since the last flush, with the count. */
    private void summariseDeliveries() {
        for (TallyKey key : List.copyOf(delivered.keySet())) {
            Tally tally = delivered.remove(key);
            if (tally == null) {
                continue;
            }
            Map<String, Object> data;
            Instant last;
            synchronized (tally) {
                data = Map.of("count", tally.count, "from", tally.first.toString(), "to", tally.last.toString());
                last = tally.last;
            }
            BrokerEvent summary = new BrokerEvent(
                    key.clusterId(),
                    key.nodeId(),
                    EventTypes.MESSAGE_DELIVERED,
                    last,
                    key.destination(),
                    key.subscription(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    data);
            if (buffer.size() >= capacity) {
                dropped.computeIfAbsent(key.clusterId(), k -> new AtomicLong()).incrementAndGet();
            } else {
                buffer.add(summary);
            }
        }
    }

    /**
     * Flush until the buffer is empty or a write fails — the shutdown flush. A failure is
     * logged rather than thrown: shutdown continues, and what could not be written is what
     * the buffer still holds.
     */
    public void drain() {
        try {
            summariseDeliveries();
            while (!buffer.isEmpty()) {
                flush();
            }
        } catch (RuntimeException e) {
            log.warn("{} broker event(s) could not be written before shutdown: {}", buffer.size(), e.getMessage());
        }
    }

    /**
     * The insert was rolled back on a foreign key: drop the events whose cluster or node is gone,
     * counting those of a cluster that still exists, and put the rest back. A rejection with no
     * orphan to blame would repeat for ever, so then the whole batch is dropped.
     */
    private void dropOrphans(List<BrokerEvent> batch) {
        Set<UUID> clusters = existing(
                "SELECT id FROM cluster WHERE id IN (:ids)", batch.stream().map(BrokerEvent::clusterId));
        Set<UUID> nodes = existing(
                "SELECT id FROM broker_node WHERE id IN (:ids)", batch.stream().map(BrokerEvent::nodeId));
        Map<Boolean, List<BrokerEvent>> byFate = batch.stream()
                .collect(Collectors.partitioningBy(
                        e -> clusters.contains(e.clusterId()) && (e.nodeId() == null || nodes.contains(e.nodeId()))));
        List<BrokerEvent> orphans = byFate.get(false);
        List<BrokerEvent> kept = byFate.get(true);
        if (orphans.isEmpty()) {
            orphans = kept;
            kept = List.of();
        }
        // A deleted cluster has no total to report, and onClusterDeleted may already have removed it.
        orphans.stream()
                .filter(e -> clusters.contains(e.clusterId()))
                .forEach(e -> dropped.computeIfAbsent(e.clusterId(), k -> new AtomicLong())
                        .incrementAndGet());
        log.warn("Dropped {} broker event(s) that reference a cluster or node that no longer exists", orphans.size());
        requeue(kept);
    }

    private Set<UUID> existing(String sql, Stream<UUID> ids) {
        Set<UUID> wanted = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.queryForList(sql, Map.of("ids", wanted), UUID.class));
    }

    /** Back at the head in their original order. What no longer fits is counted as dropped, as on intake. */
    private void requeue(List<BrokerEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            BrokerEvent event = batch.get(i);
            if (buffer.size() >= capacity) {
                dropped.computeIfAbsent(event.clusterId(), k -> new AtomicLong())
                        .incrementAndGet();
                continue;
            }
            buffer.offerFirst(event);
        }
    }

    /**
     * Inserts the batch and hands exactly the rows this insert produced to the sink, inside the
     * surrounding transaction. The sink announces them on the bus, whose notification Postgres sends
     * only when that transaction commits, so a rollback announces nothing.
     */
    private void write(List<BrokerEvent> batch) {
        jdbc.getJdbcOperations().query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, WRITE_LOCK);
        SqlParameterSource[] params = batch.stream().map(this::params).toArray(SqlParameterSource[]::new);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.batchUpdate(INSERT, params, keys, new String[] {"seq"});
        List<Long> seqs = keys.getKeyList().stream()
                .map(row -> ((Number) row.get("seq")).longValue())
                .toList();
        BrokerEventPublisher sink = publisher.getIfAvailable();
        if (sink != null) {
            sink.written(seqs, batch);
        }
    }

    private SqlParameterSource params(BrokerEvent e) {
        return new MapSqlParameterSource()
                .addValue("occurredAt", Timestamp.from(e.occurredAt()))
                .addValue("type", e.type())
                .addValue("destination", e.destination())
                .addValue("subscription", e.subscription())
                .addValue("consumer", e.consumer())
                .addValue("channel", e.channel())
                .addValue("connection", e.connection())
                .addValue("remoteAddress", e.remoteAddress())
                .addValue("principal", e.principal())
                .addValue("data", toJson(e.data()))
                .addValue("clusterId", e.clusterId())
                .addValue("nodeId", e.nodeId());
    }

    private String toJson(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(props);
        } catch (JacksonException e) {
            log.debug("Could not serialise notification props: {}", e.getMessage());
            return null;
        }
    }
}
