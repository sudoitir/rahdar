package io.github.sudoitir.rahdar.feature.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.kernel.core.PartitionProvisioner;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.platform.broker.BrokerEvent;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link BrokerEventWriter}: {@code accept} never blocks, overflow is counted per
 * cluster, and {@code flush} persists the buffer in one batch.
 *
 * <p>Each test uses its own writer. The application's writer is drained every second by the
 * {@code events-flush} job, which would empty the buffer between two accepts and make overflow a
 * race.
 */
class BrokerEventWriterTest extends PostgresIntegrationTest {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ObjectProvider<BrokerEventPublisher> publisher;

    @Autowired
    EventsProperties properties;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PartitionProvisioner partitions;

    private final UUID clusterId = UUID.randomUUID();
    private BrokerEventWriter writer;

    @BeforeEach
    void seedCluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name, system) VALUES (:id, :name, 'artemis')",
                Map.of("id", clusterId, "name", "writer-" + clusterId));
        writer = new BrokerEventWriter(jdbc, mapper, publisher, transactions, partitions, properties);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    private BrokerEvent event(String type) {
        return event(clusterId, type);
    }

    private BrokerEvent event(UUID cluster, String type) {
        return new BrokerEvent(
                cluster,
                null,
                type,
                Instant.now(),
                "some.address",
                "rn",
                "c1",
                "s1",
                "conn1",
                "1.2.3.4:5",
                "alice",
                Map.of("_AMQ_NotifType", type, "_AMQ_Address", "some.address"));
    }

    private int persisted() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
    }

    @Test
    void flushPersistsTheBufferedBatch() {
        writer.accept(event("openbroker.consumer.attached"));
        writer.accept(event("openbroker.channel.opened"));

        writer.flush();

        assertThat(persisted()).isEqualTo(2);
        String storedProps = jdbc.queryForObject(
                "SELECT data::text FROM broker_event WHERE cluster_id = :c AND type = 'openbroker.consumer.attached'",
                Map.of("c", clusterId),
                String.class);
        assertThat(storedProps).contains("_AMQ_Address");
    }

    @Test
    void aBurstOfDeliveriesIsOneSummaryRowPerDestinationNotOnePerMessage() {
        for (int i = 0; i < 10_000; i++) {
            writer.accept(event("openbroker.message.delivered"));
        }
        writer.accept(event("openbroker.consumer.attached"));

        writer.flush();

        assertThat(persisted()).isEqualTo(2);
        Integer count = jdbc.queryForObject(
                "SELECT (data->>'count')::int FROM broker_event WHERE cluster_id = :c AND type = 'openbroker.message.delivered'",
                Map.of("c", clusterId),
                Integer.class);
        assertThat(count).isEqualTo(10_000);
        writer.flush();
        assertThat(persisted()).isEqualTo(2);
    }

    @Test
    void aBatchWhoseWriteFailsIsKeptInOrderForTheNextFlush() {
        NamedParameterJdbcTemplate failingOnce = Mockito.spy(jdbc);
        Mockito.doThrow(new DataAccessResourceFailureException("database down"))
                .doCallRealMethod()
                .when(failingOnce)
                .batchUpdate(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(SqlParameterSource[].class),
                        ArgumentMatchers.any(KeyHolder.class),
                        ArgumentMatchers.any(String[].class));
        BrokerEventWriter flaky =
                new BrokerEventWriter(failingOnce, mapper, publisher, transactions, partitions, properties);
        flaky.accept(event("openbroker.consumer.attached"));
        flaky.accept(event("openbroker.channel.opened"));

        Assertions.assertThatThrownBy(flaky::flush).hasMessageContaining("database down");
        assertThat(persisted()).isZero();

        flaky.flush();

        assertThat(persisted()).isEqualTo(2);
        assertThat(flaky.droppedFor(clusterId)).isZero();
        assertThat(jdbc.queryForList(
                        "SELECT type FROM broker_event WHERE cluster_id = :c ORDER BY seq",
                        Map.of("c", clusterId),
                        String.class))
                .containsExactly("openbroker.consumer.attached", "openbroker.channel.opened");
    }

    @Test
    void aFlushWaitsForAnotherWriterSoSeqFollowsCommitOrder() throws Exception {
        writer.accept(event("QUEUE_CREATED"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Thread other = Thread.ofVirtual()
                .start(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
                    jdbc.getJdbcOperations()
                            .query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, BrokerEventWriter.WRITE_LOCK);
                    holding.countDown();
                    try {
                        commit.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        Thread flushing = Thread.ofVirtual().start(writer::flush);
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(count())
                        .as("rows written while another writer holds the lock")
                        .isZero());

        commit.countDown();
        flushing.join(5_000);
        other.join(5_000);
        assertThat(count()).isEqualTo(1);
    }

    private int count() {
        return jdbc.getJdbcOperations()
                .queryForObject("SELECT count(*) FROM broker_event WHERE cluster_id = ?", Integer.class, clusterId);
    }

    @Test
    void thePublisherGetsOnlyItsOwnRows() {
        List<String> published = new ArrayList<>();
        ObjectProvider<BrokerEventPublisher> capturing = Mockito.mock();
        Mockito.when(capturing.getIfAvailable())
                .thenReturn((seqs, batch) -> batch.forEach(e -> published.add(e.type())));
        BrokerEventWriter capturingWriter =
                new BrokerEventWriter(jdbc, mapper, capturing, transactions, partitions, properties);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        capturingWriter.accept(event("openbroker.consumer.attached"));
        capturingWriter.accept(event("openbroker.channel.opened"));

        tx.executeWithoutResult(status -> {
            capturingWriter.flush();
            // Another writer's row lands in the same window; it is not ours to publish.
            writer.accept(event("BINDING_ADDED"));
            writer.flush();
        });

        assertThat(published).containsExactly("openbroker.consumer.attached", "openbroker.channel.opened");
    }

    @Test
    void overflowIsDroppedAndCountedNotBlocked() {
        writer.setCapacity(1);
        writer.accept(event("openbroker.consumer.attached")); // fills the soft cap
        writer.accept(event("openbroker.consumer.detached")); // dropped
        writer.accept(event("openbroker.channel.opened")); // dropped

        assertThat(writer.droppedFor(clusterId)).isEqualTo(2);

        writer.flush();
        assertThat(persisted()).isEqualTo(1);
    }

    @Test
    void anIntegrityViolatingBatchDoesNotBlockTheEventsBehindIt() {
        UUID deleted = UUID.randomUUID();
        writer.accept(event(deleted, "openbroker.consumer.attached"));
        writer.accept(event("openbroker.channel.opened"));

        writer.flush();
        writer.accept(event("openbroker.consumer.detached"));
        writer.flush();

        assertThat(jdbc.queryForList(
                        "SELECT type FROM broker_event WHERE cluster_id = :c ORDER BY seq",
                        Map.of("c", clusterId),
                        String.class))
                .containsExactly("openbroker.channel.opened", "openbroker.consumer.detached");
        // A deleted cluster has no total to report, and a counter made for it now would outlive its deletion signal.
        assertThat(writer.droppedFor(deleted)).isZero();
        assertThat(writer.droppedFor(clusterId)).isZero();
    }

    @Test
    void aDeletedClustersBufferedEventsArePurged() {
        UUID deleted = UUID.randomUUID();
        writer.accept(event(deleted, "openbroker.consumer.attached"));
        writer.accept(event("openbroker.channel.opened"));

        writer.onClusterDeleted(new ReplicaSignal("cluster-deleted", deleted.toString()));
        writer.flush();

        assertThat(persisted()).isEqualTo(1);
        assertThat(writer.droppedFor(deleted)).isZero();
    }
}
