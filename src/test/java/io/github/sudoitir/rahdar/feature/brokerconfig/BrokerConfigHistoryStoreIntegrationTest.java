package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The broker-config-history store against a real Postgres (ADR-0134). */
class BrokerConfigHistoryStoreIntegrationTest extends PostgresIntegrationTest {

    private static final String OLD = "now() - interval '500 days'";

    @Autowired
    BrokerConfigHistoryStore store;

    @Autowired
    JdbcTemplate jdbc;

    private UUID clusterId;
    private final long[] revisions = new long[8];

    /**
     * r1 and r3 are old and free once a1 goes; r2 is held by an apply a node state names; r4 by an
     * owned item; r5 is the declaration's current one; r6 is recent; r7 was verified by a node.
     */
    @BeforeEach
    void seed() {
        clusterId = jdbc.queryForObject(
                "INSERT INTO cluster (name, system) VALUES (?, 'artemis') RETURNING id",
                UUID.class,
                "hist-" + UUID.randomUUID());
        for (int n = 1; n <= 7; n++) {
            revisions[n] = jdbc.queryForObject(
                    "INSERT INTO artemis_config_revision (created_at, revision, document, source, created_by, cluster_id)"
                            + " VALUES (" + (n == 6 ? "now()" : OLD) + ", ?, '{}', 'EDIT', 'test', ?) RETURNING id",
                    Long.class,
                    n,
                    clusterId);
        }
        apply(revisions[1], OLD);
        long a2 = apply(revisions[2], OLD);
        apply(revisions[6], "now()");
        UUID nodeA = node("a");
        UUID nodeB = node("b");
        nodeState(nodeA, 2, "'VERIFIED_APPLY'", a2);
        nodeState(nodeB, 7, "NULL", null);
        jdbc.update(
                "INSERT INTO artemis_config_owned_item (revision_id, kind, item_key, cluster_id)"
                        + " VALUES (?, 'ADDRESS_SETTING', 'orders', ?)",
                revisions[4],
                clusterId);
        jdbc.update(
                "INSERT INTO artemis_config_declaration (current_revision_id, cluster_id) VALUES (?, ?)",
                revisions[5],
                clusterId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM cluster WHERE id = ?", clusterId);
    }

    private long apply(long revisionId, String startedAt) {
        return jdbc.queryForObject(
                "INSERT INTO artemis_config_apply (started_at, revision_id, plan, outcome, actor, cluster_id, dry_run)"
                        + " VALUES (" + startedAt + ", ?, '{}', 'APPLIED', 'test', ?, false) RETURNING id",
                Long.class,
                revisionId,
                clusterId);
    }

    private UUID node(String name) {
        return jdbc.queryForObject(
                "INSERT INTO broker_node (name, cluster_id) VALUES (?, ?) RETURNING id", UUID.class, name, clusterId);
    }

    private void nodeState(UUID nodeId, int verifiedRevision, String basis, Long basisRef) {
        jdbc.update(
                "INSERT INTO artemis_config_node_state (state, cluster_id, node_id, verified_revision, basis, basis_ref)"
                        + " VALUES ('IN_SYNC', ?, ?, ?, " + basis + ", ?)",
                clusterId,
                nodeId,
                verifiedRevision,
                basisRef);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE cluster_id = ?", Long.class, clusterId);
    }

    private List<Integer> keptRevisionNumbers() {
        return jdbc.queryForList(
                "SELECT revision FROM artemis_config_revision WHERE cluster_id = ? ORDER BY revision",
                Integer.class,
                clusterId);
    }

    @Test
    void previewCountsWhatQualifiesAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        // a1, then r1 and r3
        assertThat(store.preview(cutoff).rows()).isEqualTo(3);

        assertThat(count("artemis_config_apply")).isEqualTo(3);
        assertThat(count("artemis_config_revision")).isEqualTo(7);
    }

    @Test
    void purgesUnreferencedHistoryInBatchesAndKeepsWhatIsStillReferenced() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        long total = 0;
        long batch;
        while ((batch = store.purgeBatch(cutoff, 1)) > 0) {
            assertThat(batch).isEqualTo(1);
            total++;
        }

        assertThat(total).isEqualTo(3);
        assertThat(store.purgeBatch(cutoff, 1)).isZero();
        assertThat(count("artemis_config_apply")).isEqualTo(2);
        assertThat(keptRevisionNumbers()).containsExactly(2, 4, 5, 6, 7);
    }
}
