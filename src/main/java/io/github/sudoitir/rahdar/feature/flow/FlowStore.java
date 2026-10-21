package io.github.sudoitir.rahdar.feature.flow;

import io.github.sudoitir.openbroker.model.RouteClass;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The flow module's disposable caches (ADR-0081): the observation lease, the latest aggregated
 * client edges and each node's sampling coverage. JDBC batch upserts, as for {@code subscription_snapshot}
 * (ADR-0016): rows rewritten every sweep have no identity worth dirty-checking.
 */
@Repository
@RequiredArgsConstructor
public class FlowStore {

    private static final String CLUSTER_ID = "clusterId";
    private static final String NODE_ID = "nodeId";
    private static final String SAMPLED_AT = "sampledAt";
    private static final String NODE_ID_COLUMN = "node_id";
    private static final String SAMPLED_AT_COLUMN = "sampled_at";
    private static final String STALE_CLUSTERS = "SELECT cluster_id FROM flow_demand WHERE observed_until < :before";
    private static final String FORGET_EDGES =
            "DELETE FROM flow_client_edge WHERE cluster_id IN (" + STALE_CLUSTERS + ")";
    private static final String FORGET_NODE_SAMPLES =
            "DELETE FROM flow_node_sample WHERE cluster_id IN (" + STALE_CLUSTERS + ")";
    private static final String FORGET_ROUTES = "DELETE FROM flow_route WHERE cluster_id IN (" + STALE_CLUSTERS + ")";

    /** One node's outcome of one sweep. {@code errorKind} is null when the node answered in full. */
    public record NodeSample(
            UUID nodeId,
            UUID clusterId,
            Instant sampledAt,
            int producersSeen,
            int producersTotal,
            int consumersSeen,
            int consumersTotal,
            String error,
            String errorKind) {}

    /** What a routing row describes (ADR-0081): the objects the flow graph draws beyond clients. */
    public enum RouteKind {
        DIVERT(RouteClass.FORWARD),
        BRIDGE(RouteClass.LINK),
        STORE_AND_FORWARD(RouteClass.LINK),
        TEMPORARY_QUEUE(RouteClass.BINDING),
        QUEUE_FILTER(RouteClass.BINDING),
        DEAD_LETTER(RouteClass.FORWARD),
        EXPIRY(RouteClass.FORWARD);

        private final RouteClass routeClass;

        RouteKind(RouteClass routeClass) {
            this.routeClass = routeClass;
        }

        /** What kind of route this is in the broker SDK's terms, whatever the system calls it. */
        public RouteClass routeClass() {
            return routeClass;
        }
    }

    /**
     * One sampled routing object on one node.
     *
     * @param source a divert's or bridge's origin (address or queue); a queue row's address
     * @param target a divert's or bridge's forwarding address; a queue row's queue name; a
     *     store-and-forward queue's receiving node id; a dead-letter or expiry address
     * @param counter the broker counter a rate is taken from (bridge acknowledged, store-and-forward
     *     added); 0 when the object has none
     * @param rate messages per second since the previous sweep, or null when not computable
     */
    public record Route(
            RouteKind kind,
            String name,
            String source,
            String target,
            String filter,
            String transformer,
            boolean exclusive,
            boolean connected,
            long counter,
            Double rate) {}

    /** A persisted routing row as a reader sees it. */
    public record StoredRoute(UUID nodeId, Instant sampledAt, Route route) {}

    /** A persisted client edge as a reader sees it. */
    public record StoredEdge(UUID nodeId, Instant sampledAt, Edge edge) {}

    private static final String UPSERT_EDGE = """
            INSERT INTO flow_client_edge
              (sampled_at, rate, unacked, member_count, kind, client_id, user_name, remote_host,
               protocol, destination, subscription, node_id, cluster_id, stalled)
            VALUES
              (:sampledAt, :rate, :unacked, :memberCount, :kind, :clientId, :userName, :remoteHost,
               :protocol, :address, :queueName, :nodeId, :clusterId, :stalled)
            ON CONFLICT (node_id, kind, client_id, user_name, remote_host, protocol, destination, subscription)
            DO UPDATE SET
               sampled_at   = EXCLUDED.sampled_at,
               rate         = EXCLUDED.rate,
               unacked      = EXCLUDED.unacked,
               member_count = EXCLUDED.member_count,
               cluster_id   = EXCLUDED.cluster_id,
               stalled      = EXCLUDED.stalled
            """;

    private static final String UPSERT_NODE = """
            INSERT INTO flow_node_sample
              (sampled_at, producers_seen, producers_total, consumers_seen, consumers_total,
               error, error_kind, node_id, cluster_id)
            VALUES
              (:sampledAt, :producersSeen, :producersTotal, :consumersSeen, :consumersTotal,
               :error, :errorKind, :nodeId, :clusterId)
            ON CONFLICT (node_id) DO UPDATE SET
               sampled_at      = EXCLUDED.sampled_at,
               producers_seen  = EXCLUDED.producers_seen,
               producers_total = EXCLUDED.producers_total,
               consumers_seen  = EXCLUDED.consumers_seen,
               consumers_total = EXCLUDED.consumers_total,
               error           = EXCLUDED.error,
               error_kind      = EXCLUDED.error_kind,
               cluster_id      = EXCLUDED.cluster_id
            """;

    private static final String UPSERT_ROUTE = """
            INSERT INTO flow_route
              (sampled_at, rate, counter, route_class, native_kind, name, source, target, filter, transformer,
               node_id, cluster_id, exclusive, connected)
            VALUES
              (:sampledAt, :rate, :counter, :routeClass, :kind, :name, :source, :target, :filter, :transformer,
               :nodeId, :clusterId, :exclusive, :connected)
            ON CONFLICT (node_id, native_kind, name, source, target) DO UPDATE SET
               sampled_at  = EXCLUDED.sampled_at,
               rate        = EXCLUDED.rate,
               counter     = EXCLUDED.counter,
               filter      = EXCLUDED.filter,
               transformer = EXCLUDED.transformer,
               cluster_id  = EXCLUDED.cluster_id,
               exclusive   = EXCLUDED.exclusive,
               connected   = EXCLUDED.connected
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Extend a cluster's observation lease to at least {@code until}; never shortens it. */
    public void renew(UUID clusterId, Instant until) {
        jdbc.update("""
                INSERT INTO flow_demand (observed_until, cluster_id) VALUES (:until, :clusterId)
                ON CONFLICT (cluster_id) DO UPDATE
                   SET observed_until = GREATEST(flow_demand.observed_until, EXCLUDED.observed_until)
                """, new MapSqlParameterSource("until", Timestamp.from(until)).addValue(CLUSTER_ID, clusterId));
    }

    /** Clusters whose lease has not expired at {@code now}, whichever instance renewed it. */
    public Set<UUID> observedClusters(Instant now) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT cluster_id FROM flow_demand WHERE observed_until > :now",
                Map.of("now", Timestamp.from(now)),
                UUID.class));
    }

    /**
     * Replace one node's edges with this sweep's and record its coverage, in one short transaction
     * after the broker calls have returned. A node that failed passes no edges, so its clients stop
     * being shown instead of being shown stale.
     */
    @Transactional
    public void persistNode(NodeSample sample, List<Edge> edges, List<Route> routes) {
        if (!edges.isEmpty()) {
            SqlParameterSource[] rows =
                    edges.stream().map(e -> edgeParams(sample, e)).toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate(UPSERT_EDGE, rows);
        }
        jdbc.update(
                "DELETE FROM flow_client_edge WHERE node_id = :nodeId AND sampled_at < :sampledAt",
                new MapSqlParameterSource(NODE_ID, sample.nodeId())
                        .addValue(SAMPLED_AT, Timestamp.from(sample.sampledAt())));
        if (!routes.isEmpty()) {
            jdbc.batchUpdate(
                    UPSERT_ROUTE,
                    routes.stream().map(r -> routeParams(sample, r)).toArray(SqlParameterSource[]::new));
        }
        jdbc.update(
                "DELETE FROM flow_route WHERE node_id = :nodeId AND sampled_at < :sampledAt",
                new MapSqlParameterSource(NODE_ID, sample.nodeId())
                        .addValue(SAMPLED_AT, Timestamp.from(sample.sampledAt())));
        jdbc.update(UPSERT_NODE, nodeParams(sample));
    }

    /** Drop the caches of clusters nobody has observed since {@code before}, lease included. */
    @Transactional
    public void forgetUnobserved(Instant before) {
        MapSqlParameterSource p = new MapSqlParameterSource("before", Timestamp.from(before));
        jdbc.update(FORGET_EDGES, p);
        jdbc.update(FORGET_NODE_SAMPLES, p);
        jdbc.update(FORGET_ROUTES, p);
        jdbc.update("DELETE FROM flow_demand WHERE observed_until < :before", p);
    }

    public List<StoredEdge> edges(UUID clusterId) {
        return jdbc.query(
                "SELECT * FROM flow_client_edge WHERE cluster_id = :clusterId",
                Map.of(CLUSTER_ID, clusterId),
                (rs, i) -> new StoredEdge(
                        rs.getObject(NODE_ID_COLUMN, UUID.class),
                        rs.getTimestamp(SAMPLED_AT_COLUMN).toInstant(),
                        edge(rs)));
    }

    public List<NodeSample> nodeSamples(UUID clusterId) {
        return jdbc.query(
                "SELECT * FROM flow_node_sample WHERE cluster_id = :clusterId",
                Map.of(CLUSTER_ID, clusterId),
                (rs, i) -> new NodeSample(
                        rs.getObject(NODE_ID_COLUMN, UUID.class),
                        rs.getObject("cluster_id", UUID.class),
                        rs.getTimestamp(SAMPLED_AT_COLUMN).toInstant(),
                        rs.getInt("producers_seen"),
                        rs.getInt("producers_total"),
                        rs.getInt("consumers_seen"),
                        rs.getInt("consumers_total"),
                        rs.getString("error"),
                        rs.getString("error_kind")));
    }

    public List<StoredRoute> routes(UUID clusterId) {
        return jdbc.query(
                "SELECT * FROM flow_route WHERE cluster_id = :clusterId",
                Map.of(CLUSTER_ID, clusterId),
                (rs, i) -> new StoredRoute(
                        rs.getObject(NODE_ID_COLUMN, UUID.class),
                        rs.getTimestamp(SAMPLED_AT_COLUMN).toInstant(),
                        new Route(
                                RouteKind.valueOf(rs.getString("native_kind")),
                                rs.getString("name"),
                                rs.getString("source"),
                                rs.getString("target"),
                                rs.getString("filter"),
                                rs.getString("transformer"),
                                rs.getBoolean("exclusive"),
                                rs.getBoolean("connected"),
                                rs.getLong("counter"),
                                rs.getObject("rate", Double.class))));
    }

    private static SqlParameterSource routeParams(NodeSample s, Route r) {
        return new MapSqlParameterSource()
                .addValue(SAMPLED_AT, Timestamp.from(s.sampledAt()))
                .addValue("rate", r.rate())
                .addValue("counter", r.counter())
                .addValue("kind", r.kind().name())
                .addValue("routeClass", r.kind().routeClass().name())
                .addValue("name", r.name() == null ? "" : r.name())
                .addValue("source", r.source() == null ? "" : r.source())
                .addValue("target", r.target() == null ? "" : r.target())
                .addValue("filter", r.filter())
                .addValue("transformer", r.transformer())
                .addValue(NODE_ID, s.nodeId())
                .addValue(CLUSTER_ID, s.clusterId())
                .addValue("exclusive", r.exclusive())
                .addValue("connected", r.connected());
    }

    private static Edge edge(ResultSet rs) throws SQLException {
        // getObject, not getDouble + wasNull: wasNull reports the last column read, and the
        // constructor arguments below read several after this one.
        Double rate = rs.getObject("rate", Double.class);
        return new Edge(
                Kind.valueOf(rs.getString("kind")),
                rs.getString("client_id"),
                rs.getString("user_name"),
                rs.getString("remote_host"),
                rs.getString("protocol"),
                rs.getString("destination"),
                rs.getString("subscription"),
                rate,
                rs.getLong("unacked"),
                rs.getInt("member_count"),
                rs.getBoolean("stalled"));
    }

    private static SqlParameterSource edgeParams(NodeSample s, Edge e) {
        return new MapSqlParameterSource()
                .addValue(SAMPLED_AT, Timestamp.from(s.sampledAt()))
                .addValue("rate", e.rate())
                .addValue("unacked", e.unacked())
                .addValue("memberCount", e.memberCount())
                .addValue("kind", e.kind().name())
                .addValue("clientId", e.clientId())
                .addValue("userName", e.user())
                .addValue("remoteHost", e.remoteHost())
                .addValue("protocol", e.protocol())
                .addValue("address", e.destination())
                .addValue("queueName", e.subscription())
                .addValue(NODE_ID, s.nodeId())
                .addValue(CLUSTER_ID, s.clusterId())
                .addValue("stalled", e.stalled());
    }

    private static SqlParameterSource nodeParams(NodeSample s) {
        return new MapSqlParameterSource()
                .addValue(SAMPLED_AT, Timestamp.from(s.sampledAt()))
                .addValue("producersSeen", s.producersSeen())
                .addValue("producersTotal", s.producersTotal())
                .addValue("consumersSeen", s.consumersSeen())
                .addValue("consumersTotal", s.consumersTotal())
                .addValue("error", s.error())
                .addValue("errorKind", s.errorKind())
                .addValue(NODE_ID, s.nodeId())
                .addValue(CLUSTER_ID, s.clusterId());
    }
}
