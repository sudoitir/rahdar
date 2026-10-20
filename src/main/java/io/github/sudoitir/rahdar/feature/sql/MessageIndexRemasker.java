package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.platform.governance.ContentSealer;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import io.github.sudoitir.rahdar.platform.governance.StoredContentRemasker;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Re-masks {@code message_index} rows stored under an earlier policy version (ADR-0075 D5). Originals sealed
 * earlier are laid back over the stored values before governing, so a narrowed rule can still unmask into the
 * seal and a new rule masks and seals a value that was stored clear.
 */
@Component
@RequiredArgsConstructor
class MessageIndexRemasker implements StoredContentRemasker {

    private static final TypeReference<Map<String, Object>> PROPS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final SqlGovernance governance;
    private final ContentSealer sealer;
    private final JsonMapper json = JsonMapper.builder().build();

    private record Stale(
            Timestamp observedAt,
            UUID clusterId,
            UUID nodeId,
            String queueName,
            long messageId,
            String address,
            boolean bodyBase64,
            Map<String, String> headers,
            String body,
            String props,
            byte[] sealed) {}

    @Override
    public String name() {
        return "message_index";
    }

    @Override
    public long countBelow(int version, long cap) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM message_index WHERE policy_version < ? LIMIT ?) stale",
                Long.class,
                version,
                cap);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public int remask(int version, int limit) {
        List<Stale> rows = jdbc.query(
                """
                SELECT observed_at, cluster_id, node_id, subscription, message_id, destination, body_base64,
                       correlation_id, group_id, user_id, reply_to, body, props, sealed
                  FROM message_index
                 WHERE policy_version < ?
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, n) -> {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("correlationId", rs.getString("correlation_id"));
                    headers.put("groupId", rs.getString("group_id"));
                    headers.put("userId", rs.getString("user_id"));
                    headers.put("replyTo", rs.getString("reply_to"));
                    return new Stale(
                            rs.getTimestamp("observed_at"),
                            rs.getObject("cluster_id", UUID.class),
                            rs.getObject("node_id", UUID.class),
                            rs.getString("subscription"),
                            rs.getLong("message_id"),
                            rs.getString("destination"),
                            rs.getBoolean("body_base64"),
                            headers,
                            rs.getString("body"),
                            rs.getString("props"),
                            rs.getBytes("sealed"));
                },
                version,
                limit);
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("""
                UPDATE message_index
                   SET correlation_id = ?, group_id = ?, user_id = ?, reply_to = ?, body = ?, props = ?::jsonb,
                       policy_version = ?, sealed = ?
                 WHERE cluster_id = ? AND node_id = ? AND subscription = ? AND message_id = ? AND observed_at = ?
                """, rows, rows.size(), (statement, row) -> {
            String aad = MessageIndexWriter.aad(row.clusterId(), row.nodeId(), row.queueName(), row.messageId());
            MessageContent stored =
                    new MessageContent(row.headers(), properties(row.props()), row.body(), row.bodyBase64(), null);
            MessageContent restored = ContentSealer.restore(stored, sealer.unseal(aad, row.sealed()));
            GovernedMessage governed = governance.forStorage(row.clusterId(), row.address(), restored);
            byte[] sealed = sealer.seal(aad, governed.sealable());
            int i = 0;
            statement.setString(++i, governed.headers().get("correlationId"));
            statement.setString(++i, governed.headers().get("groupId"));
            statement.setString(++i, governed.headers().get("userId"));
            statement.setString(++i, governed.headers().get("replyTo"));
            statement.setString(++i, governed.body());
            statement.setString(++i, json.writeValueAsString(governed.properties()));
            statement.setInt(++i, governed.policyVersion());
            statement.setObject(++i, sealed, Types.BINARY);
            statement.setObject(++i, row.clusterId());
            statement.setObject(++i, row.nodeId());
            statement.setString(++i, row.queueName());
            statement.setLong(++i, row.messageId());
            statement.setTimestamp(++i, row.observedAt());
        });
        return rows.size();
    }

    private Map<String, Object> properties(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, PROPS);
        } catch (JacksonException _) {
            return Map.of();
        }
    }
}
