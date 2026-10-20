package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.platform.governance.StoredContentRemasker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Re-masks captured request-reply payloads stored under an earlier policy version (ADR-0075 D5). The flow gives
 * the cluster and the address the payload was observed on, which the policy's address-scoped rules need.
 */
@Component
@RequiredArgsConstructor
class RrEventRemasker implements StoredContentRemasker {

    private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {};

    /** A stored payload below the version; events without a payload are never counted. */
    private static final String STALE = """
            FROM rr_event e
            JOIN rr_flow f ON f.id = e.flow_id
           WHERE e.detail ->> 'bodyPreview' IS NOT NULL
             AND COALESCE((e.detail ->> 'policyVersion')::int, 0) < ?
            """;

    private final JdbcTemplate jdbc;
    private final RrPayloads payloads;
    private final ObjectMapper mapper;

    private record Stale(
            long seq, UUID flowId, String kind, Instant ts, UUID clusterId, String address, String detail) {}

    @Override
    public String name() {
        return "rr_event";
    }

    @Override
    public long countBelow(int version, long cap) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 " + STALE + " LIMIT ?) stale", Long.class, version, cap);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public int remask(int version, int limit) {
        List<Stale> rows = jdbc.query(
                "SELECT e.seq, e.flow_id, e.kind, e.ts, e.detail::text AS detail, f.cluster_id,"
                        + " CASE WHEN e.kind = 'REQUEST_SEEN' THEN f.request_destination ELSE f.reply_destination END"
                        + " AS address "
                        + STALE
                        + " LIMIT ? FOR UPDATE OF e SKIP LOCKED",
                (rs, n) -> new Stale(
                        rs.getLong("seq"),
                        rs.getObject("flow_id", UUID.class),
                        rs.getString("kind"),
                        rs.getTimestamp("ts").toInstant(),
                        rs.getObject("cluster_id", UUID.class),
                        rs.getString("address"),
                        rs.getString("detail")),
                version,
                limit);
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("UPDATE rr_event SET detail = ?::jsonb WHERE seq = ?", rows, rows.size(), (statement, row) -> {
            Map<String, Object> next = payloads.remasked(
                    row.clusterId(),
                    row.address(),
                    row.flowId(),
                    row.kind(),
                    row.ts(),
                    mapper.readValue(row.detail(), DETAIL));
            statement.setString(1, mapper.writeValueAsString(next));
            statement.setLong(2, row.seq());
        });
        return rows.size();
    }
}
