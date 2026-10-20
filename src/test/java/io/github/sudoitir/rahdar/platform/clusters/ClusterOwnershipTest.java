package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.replica.DrainStarted;
import io.github.sudoitir.rahdar.kernel.replica.HaProperties;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry.Replica;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Cluster ownership on one database with two fake replicas that tick by hand: one owner per
 * cluster, spread over both, a takeover after the owner goes silent, and a hand-over on drain.
 */
class ClusterOwnershipTest extends PostgresIntegrationTest {

    private static final HaProperties TTL_15S =
            new HaProperties(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ZERO, Duration.ZERO);

    /**
     * The replicas' ids are fixed and the clusters are made to split evenly between them. Rendezvous
     * hashing over random ids leaves one replica with nothing (or everything) in about one run of
     * twelve clusters in two thousand, which these tests would read as a failure.
     */
    private static final List<UUID> REPLICA_IDS = List.of(new UUID(0, 1), new UUID(0, 2));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<UUID> clusters = new ArrayList<>();
    private final List<Replica> live = new ArrayList<>();
    private int nodes;

    @AfterEach
    void clean() {
        clusters.forEach(id -> jdbc.update("DELETE FROM cluster WHERE id = ?", id));
    }

    private Node node(HaProperties ha) {
        ReplicaRegistry registry = mock(ReplicaRegistry.class);
        UUID id = REPLICA_IDS.get(nodes++);
        when(registry.id()).thenReturn(id);
        when(registry.state()).thenReturn(ReplicaRegistry.State.READY);
        when(registry.live()).thenAnswer(_ -> List.copyOf(live));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        RahdarBus bus = mock(RahdarBus.class);
        live.add(new Replica(id, "test", "test", ReplicaRegistry.State.READY, Instant.now(), Instant.now()));
        return new Node(new ClusterOwnership(jdbc, transactions, registry, bus, ha, events), id, events, bus);
    }

    private Node node() {
        return node(TTL_15S);
    }

    private record Node(ClusterOwnership ownership, UUID id, ApplicationEventPublisher events, RahdarBus bus) {}

    /** What the node owns among the clusters this test registered; the shared database holds others. */
    private Set<UUID> owned(Node node) {
        Set<UUID> mine = new HashSet<>(node.ownership().owned());
        mine.retainAll(clusters);
        return mine;
    }

    private void leave(Node node) {
        live.removeIf(r -> r.id().equals(node.id()));
    }

    private List<UUID> register(int count) {
        List<UUID> created = new ArrayList<>();
        while (created.size() < count) {
            UUID id = UUID.randomUUID();
            UUID wanted = REPLICA_IDS.get(created.size() % REPLICA_IDS.size());
            if (wanted.equals(ClusterOwnership.ownerOf(id, Set.copyOf(REPLICA_IDS)))) {
                jdbc.update("INSERT INTO cluster (id, name, system) VALUES (?, ?, 'artemis')", id, "ownership-" + id);
                created.add(id);
                clusters.add(id);
            }
        }
        return created;
    }

    private void expire(String age) {
        jdbc.update(
                "UPDATE cluster_lease SET expires_at = now() - ?::interval WHERE cluster_id = ANY (?)",
                age,
                clusters.toArray(UUID[]::new));
    }

    @Test
    void everyClusterHasOneOwnerAndBothReplicasOwnSome() {
        List<UUID> created = register(24);
        Node a = node();
        Node b = node();

        a.ownership().tick();
        b.ownership().tick();
        a.ownership().tick();

        Set<UUID> both = new HashSet<>(owned(a));
        both.addAll(owned(b));
        assertThat(both).containsExactlyInAnyOrderElementsOf(created);
        assertThat(owned(a)).as("disjoint").doesNotContainAnyElementsOf(owned(b));
        assertThat(owned(a)).isNotEmpty();
        assertThat(owned(b)).isNotEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cluster_lease WHERE cluster_id = ANY (?)", Long.class, (Object)
                                created.toArray(UUID[]::new)))
                .isEqualTo(24);
    }

    @Test
    void ownsFollowsTheRendezvousOwner() {
        Node a = node();
        Node b = node();
        register(10);
        a.ownership().tick();
        b.ownership().tick();

        Set<UUID> ready = Set.of(a.id(), b.id());
        for (UUID cluster : clusters) {
            assertThat(a.ownership().owns(cluster)).isEqualTo(a.id().equals(ClusterOwnership.ownerOf(cluster, ready)));
            assertThat(b.ownership().owns(cluster)).isEqualTo(b.id().equals(ClusterOwnership.ownerOf(cluster, ready)));
        }
    }

    @Test
    void aSilentOwnersClustersAreTakenOverWhenItsLeasesExpire() {
        register(12);
        Node a = node();
        Node b = node();
        a.ownership().tick();
        b.ownership().tick();
        Set<UUID> theirs = Set.copyOf(owned(a));
        assertThat(theirs).isNotEmpty();

        leave(a);
        b.ownership().tick();
        assertThat(owned(b)).as("a live lease is not taken").doesNotContainAnyElementsOf(theirs);

        expire("1 second");
        b.ownership().tick();
        assertThat(owned(b)).containsExactlyInAnyOrderElementsOf(clusters);
    }

    @Test
    void aReplicaThatNeverTicksLosesItsClustersOnlyAfterOneMoreTtl() {
        register(12);
        Node a = node();
        Node b = node();
        a.ownership().tick();
        Set<UUID> share = Set.copyOf(owned(a));
        assertThat(share)
                .as("only what is its own while the other may yet tick")
                .isNotEmpty()
                .hasSizeLessThan(12);

        jdbc.update("UPDATE cluster SET created_at = now() - interval '16 seconds' WHERE id = ANY (?)", (Object)
                clusters.toArray(UUID[]::new));
        a.ownership().tick();

        assertThat(owned(a)).containsExactlyInAnyOrderElementsOf(clusters);
        assertThat(owned(b)).isEmpty();
    }

    @Test
    void aDrainingReplicaHandsItsClustersOverAtOnce() {
        register(12);
        Node a = node();
        Node b = node();
        a.ownership().tick();
        b.ownership().tick();
        assertThat(owned(a)).isNotEmpty();

        leave(a);
        a.ownership().onDrain(new DrainStarted());

        assertThat(owned(a)).isEmpty();
        assertThat(a.ownership().owns(clusters.getFirst())).isFalse();
        verify(a.bus()).publish(any());
        b.ownership().tick();
        assertThat(owned(b)).containsExactlyInAnyOrderElementsOf(clusters);
    }

    @Test
    void aJoiningReplicaTakesItsShareAfterTheOthersHandItOver() {
        register(24);
        live.clear();
        Node a = node();
        a.ownership().tick();
        assertThat(owned(a)).containsExactlyInAnyOrderElementsOf(clusters);

        Node b = node();
        a.ownership().tick();
        verify(a.bus()).publish(any());
        b.ownership().tick();

        assertThat(owned(a)).isNotEmpty().doesNotContainAnyElementsOf(owned(b));
        assertThat(owned(b)).isNotEmpty();
        Set<UUID> both = new HashSet<>(owned(a));
        both.addAll(owned(b));
        assertThat(both).containsExactlyInAnyOrderElementsOf(clusters);
    }

    @Test
    void ownershipEventsFollowTheSet() {
        List<UUID> created = register(3);
        Node a = node();

        a.ownership().tick();
        created.forEach(id -> verify(a.events()).publishEvent(new ClusterDutyAcquired(id)));

        jdbc.update("DELETE FROM cluster WHERE id = ?", created.getFirst());
        a.ownership().tick();
        verify(a.events()).publishEvent(new ClusterDutyReleased(created.getFirst()));
        verify(a.events(), never()).publishEvent(new ClusterDutyReleased(created.getLast()));
    }

    @Test
    void aReplicaThatCannotRenewStopsOwningBeforeItsLeaseExpires() {
        register(3);
        Node a = node(new HaProperties(Duration.ofSeconds(1), Duration.ofMillis(1500), Duration.ZERO, Duration.ZERO));
        a.ownership().tick();
        assertThat(a.ownership().owns(clusters.getFirst())).isTrue();

        await().atMost(Duration.ofSeconds(5)).until(() -> !a.ownership().owns(clusters.getFirst()));

        assertThat(a.ownership().owns(clusters.getFirst()))
                .as("fenced at ttl minus a third")
                .isFalse();
        assertThat(owned(a)).isEmpty();
    }
}
