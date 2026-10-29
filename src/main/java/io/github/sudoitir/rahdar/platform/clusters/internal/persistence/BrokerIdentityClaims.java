package io.github.sudoitir.rahdar.platform.clusters.internal.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code broker_identity}: which cluster each broker belongs to (ADR-0167). Its primary key is the
 * database's half of "a broker is registered once"; the registration check over {@code broker_node}
 * is the other, and the one that names the cluster.
 */
@Component
@RequiredArgsConstructor
public class BrokerIdentityClaims {

    private final JdbcClient jdbc;

    /**
     * Claims the identities for a cluster in the caller's transaction, and returns another cluster that
     * already holds one of them, if any. One statement, in key order, so two registrations claiming the
     * same brokers lock them in the same order. A claim another transaction has written but not committed
     * makes this wait for it: committed, it is that cluster's and is returned here; rolled back, the claim
     * is this cluster's.
     *
     * @param claims the identities by kind ({@code NODE_ID} or {@code URL})
     */
    public Optional<UUID> claim(UUID clusterId, Map<String, Set<String>> claims) {
        Keys keys = Keys.of(claims);
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        insert(clusterId, keys);
        return jdbc.sql("""
                        SELECT b.cluster_id FROM broker_identity b
                        JOIN unnest(?::text[], ?::text[]) AS t (kind, identity)
                          ON b.kind = t.kind AND b.identity = t.identity
                        WHERE b.cluster_id <> ?
                        LIMIT 1
                        """)
                .params(keys.kinds(), keys.identities(), clusterId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Makes a cluster's claims exactly these, in the caller's transaction: what it no longer has is
     * released, and what it has now is claimed in the same key order as {@link #claim}. An identity another
     * cluster holds stays that cluster's; the registration check still finds both through their nodes.
     */
    public void replace(UUID clusterId, Map<String, Set<String>> claims) {
        Keys keys = Keys.of(claims);
        jdbc.sql("""
                        DELETE FROM broker_identity b
                        WHERE b.cluster_id = ? AND NOT EXISTS (
                            SELECT 1 FROM unnest(?::text[], ?::text[]) AS t (kind, identity)
                            WHERE t.kind = b.kind AND t.identity = b.identity)
                        """).params(clusterId, keys.kinds(), keys.identities()).update();
        if (!keys.isEmpty()) {
            insert(clusterId, keys);
        }
    }

    /**
     * Releases these identities from every cluster but this one. Only for claims the caller has found
     * stale: no node of the cluster holding them carries them any more.
     */
    public void releaseHeldByOthers(UUID clusterId, Map<String, Set<String>> claims) {
        Keys keys = Keys.of(claims);
        if (keys.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        DELETE FROM broker_identity b
                        USING unnest(?::text[], ?::text[]) AS t (kind, identity)
                        WHERE b.kind = t.kind AND b.identity = t.identity AND b.cluster_id <> ?
                        """).params(keys.kinds(), keys.identities(), clusterId).update();
    }

    /** The cluster holding this identity, if any. */
    public Optional<UUID> holder(String kind, String identity) {
        return jdbc.sql("SELECT cluster_id FROM broker_identity WHERE kind = ? AND identity = ?")
                .params(kind, identity)
                .query(UUID.class)
                .optional();
    }

    /** The identity of this kind a cluster holds, if any. */
    public Optional<String> identityOf(UUID clusterId, String kind) {
        return jdbc.sql("SELECT identity FROM broker_identity WHERE cluster_id = ? AND kind = ? LIMIT 1")
                .params(clusterId, kind)
                .query(String.class)
                .optional();
    }

    private void insert(UUID clusterId, Keys keys) {
        jdbc.sql("""
                        INSERT INTO broker_identity (kind, identity, cluster_id)
                        SELECT t.kind, t.identity, ? FROM unnest(?::text[], ?::text[]) AS t (kind, identity)
                        ORDER BY t.kind, t.identity
                        ON CONFLICT DO NOTHING
                        """).params(clusterId, keys.kinds(), keys.identities()).update();
    }

    /** The claims as two parallel arrays, for {@code unnest}. */
    private record Keys(List<String> kindList, List<String> identityList) {

        static Keys of(Map<String, Set<String>> claims) {
            List<String> kinds = new ArrayList<>();
            List<String> identities = new ArrayList<>();
            claims.forEach((kind, values) -> values.forEach(value -> {
                kinds.add(kind);
                identities.add(value);
            }));
            return new Keys(List.copyOf(kinds), List.copyOf(identities));
        }

        String[] kinds() {
            return kindList.toArray(String[]::new);
        }

        String[] identities() {
            return identityList.toArray(String[]::new);
        }

        boolean isEmpty() {
            return kindList.isEmpty();
        }
    }
}
