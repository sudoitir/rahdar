package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Order;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Bound;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.kernel.core.StatementTimeout;
import io.github.sudoitir.rahdar.platform.governance.ContentSealer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link QueryPlan} against the historical index (ADR-0059).
 *
 * <p>Every value the operator typed becomes a bind parameter. The SQL text is
 * assembled only from the {@link ColumnCatalogue}'s own column names and fixed
 * operator spellings, so no fragment of the query string reaches Postgres as SQL.
 * The statement runs read-only with its own timeout, so a pathological predicate
 * costs one query rather than the connection pool.
 */
@Component
@RequiredArgsConstructor
public class IndexQueryExecutor {

    private final JdbcTemplate jdbc;
    private final SqlProperties properties;
    private final SqlGovernance governance;
    private final ContentSealer sealer;
    private final JsonMapper json = JsonMapper.builder().build();

    /** A fragment plus the values that fill its placeholders. */
    private record Sql(String text, List<Object> binds) {}

    @Transactional(readOnly = true)
    public QueryResult execute(UUID clusterId, QueryPlan plan, BrokerQueryExecutor.Sink sink) {
        return execute(clusterId, plan, sink, null);
    }

    /**
     * As above, but only what the index recorded at or after {@code since}, oldest first — a live
     * tail's next page. The bound is {@code observed_at} and not the message's own timestamp: it is
     * when this Rahdar wrote the row, so it advances even for a producer whose clock does not, and
     * it is the column the query is already ordered and partitioned by.
     *
     * <p>Inclusive, and the caller re-reads a short way behind its mark: {@code observed_at} is
     * stamped when a drain reads a message and the row lands when its batch commits, so two drains
     * can commit slightly out of order. Whatever that repeats, the tail's own mark filters out.
     */
    @Transactional(readOnly = true)
    public QueryResult execute(UUID clusterId, QueryPlan plan, BrokerQueryExecutor.Sink sink, Instant since) {
        List<Object> binds = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT observed_at, last_seen_at, message_id, timestamp_ms, expiration_ms, size_bytes,
                       priority, message_type, subscription, destination, node_name, correlation_id, group_id,
                       user_id, reply_to, jms_type, body, props, cluster_id, node_id, durable,
                       origin, orig_destination, source_message_id, body_truncated, body_base64, body_compression, sealed
                  FROM message_index
                 WHERE cluster_id = ?
                """);
        binds.add(clusterId);

        List<String> queues =
                plan.targets().stream().map(Target::subscription).distinct().toList();
        if (!queues.isEmpty()) {
            sql.append(" AND subscription IN (")
                    .append(String.join(", ", Collections.nCopies(queues.size(), "?")))
                    .append(')');
            binds.addAll(queues);
        }

        if (plan.ast().where() != null) {
            Sql where = predicate(plan.ast().where());
            sql.append(" AND (").append(where.text()).append(')');
            binds.addAll(where.binds());
        }

        if (since != null) {
            sql.append(" AND observed_at >= ?");
            binds.add(Timestamp.from(since));
        }

        // A tail is chronological whatever the query asked to be ordered by: it is a feed of what
        // arrived next, and the newest-first page the same query answers statically would send the
        // same rows again on every poll.
        Sql order = since != null
                ? new Sql(" ORDER BY observed_at ASC", List.of())
                : orderBy(plan.ast().orderBy(), matchTerms(plan.ast().where()));
        sql.append(order.text());
        binds.addAll(order.binds());
        sql.append(" LIMIT ?");
        binds.add(plan.effectiveLimit() + 1);

        jdbc.execute(StatementTimeout.setLocal(properties.timeout()));

        // Resolved on the caller's thread. Only a caller with clear access has stored originals unsealed; everyone
        // else reads the masked stored row, which the console governs again under the current policy.
        boolean clearAccess = governance.clearAccess(clusterId, plan.ast().subscriptionPattern());
        List<Row> rows = jdbc.query(sql.toString(), (rs, n) -> toRow(rs, clusterId, clearAccess), binds.toArray());

        List<Bound> bounds = new ArrayList<>();
        if (rows.size() > plan.effectiveLimit()) {
            rows = rows.subList(0, plan.effectiveLimit());
            bounds.add(new Bound(Bound.Kind.ROW_LIMIT, plan.effectiveLimit()));
        }
        rows.forEach(sink::row);

        // The index answers in one statement, so there is no per-node fan-out to
        // report — but the queues it covered are still named, so a result from the
        // index and one from the brokers read the same way.
        List<NodeOutcome> outcomes = new ArrayList<>();
        Map<String, Long> perQueue = new LinkedHashMap<>();
        for (Row row : rows) {
            perQueue.merge(row.subscription(), 1L, Long::sum);
        }
        for (Target target : plan.targets()) {
            long matched = perQueue.getOrDefault(target.subscription(), 0L);
            NodeOutcome outcome = new NodeOutcome(
                    target.nodeId(),
                    target.nodeName(),
                    target.subscription(),
                    NodeOutcome.Status.ANSWERED,
                    matched,
                    matched,
                    null);
            outcomes.add(outcome);
            sink.nodeFinished(outcome);
        }
        return new QueryResult(List.copyOf(rows), List.copyOf(outcomes), List.copyOf(bounds), plan.notices());
    }

    // ---- predicate compilation -----------------------------------------

    private Sql predicate(Predicate predicate) {
        return switch (predicate) {
            case Predicate.And(var parts) -> join(parts, " AND ");
            case Predicate.Or(var parts) -> join(parts, " OR ");
            case Predicate.Not(var operand) -> {
                Sql inner = predicate(operand);
                yield new Sql("NOT (" + inner.text() + ')', inner.binds());
            }
            case Predicate.Compare compare -> compare(compare);
            case Predicate.In in -> in(in);
            case Predicate.IsNull(var target, var negated) -> {
                Sql term = term(target);
                yield new Sql(term.text() + (negated ? " IS NOT NULL" : " IS NULL"), term.binds());
            }
            case Predicate.Like like -> like(like);
            case Predicate.Between between -> between(between);
            case Predicate.Match match -> match(match);
        };
    }

    private Sql join(List<Predicate> parts, String operator) {
        List<String> fragments = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        for (Predicate part : parts) {
            Sql compiled = predicate(part);
            fragments.add('(' + compiled.text() + ')');
            binds.addAll(compiled.binds());
        }
        return new Sql(String.join(operator, fragments), binds);
    }

    private Sql compare(Predicate.Compare compare) {
        Sql term = term(compare.term());
        List<Object> binds = new ArrayList<>(term.binds());
        binds.add(value(compare.value(), compare.term()));
        return new Sql(term.text() + " " + compare.op().sql() + " ?", binds);
    }

    private Sql in(Predicate.In in) {
        Sql term = term(in.term());
        List<Object> binds = new ArrayList<>(term.binds());
        in.values().forEach(v -> binds.add(value(v, in.term())));
        String placeholders = String.join(", ", Collections.nCopies(in.values().size(), "?"));
        return new Sql(term.text() + (in.negated() ? " NOT IN (" : " IN (") + placeholders + ')', binds);
    }

    private Sql between(Predicate.Between between) {
        Sql term = term(between.term());
        List<Object> binds = new ArrayList<>(term.binds());
        binds.add(value(between.low(), between.term()));
        binds.add(value(between.high(), between.term()));
        return new Sql(term.text() + (between.negated() ? " NOT BETWEEN ? AND ?" : " BETWEEN ? AND ?"), binds);
    }

    private Sql like(Predicate.Like like) {
        Sql term = term(like.term());
        List<Object> binds = new ArrayList<>(term.binds());
        binds.add(like.pattern());
        String operator = like.caseInsensitive() ? " ILIKE ?" : " LIKE ?";
        String escape = like.escape() == null ? "" : " ESCAPE ?";
        if (like.escape() != null) {
            binds.add(String.valueOf(like.escape()));
        }
        String text = term.text() + (like.negated() ? " NOT" : "") + operator + escape;
        return new Sql(text, binds);
    }

    /**
     * {@code MATCH(body, 'terms')} against the functional GIN index (ADR-0063).
     *
     * <p>The {@code body_base64} test is not a filter an operator asked for — it is
     * the index's own predicate, repeated so Postgres can use it. The index covers
     * text bodies only, because {@code to_tsvector} over base64 produces garbage
     * tokens and bloats the index for no retrieval value. A bytes message whose body
     * is text is stored as text (ADR-0148) and is searchable like any other.
     *
     * <p>{@code websearch_to_tsquery} rather than {@code to_tsquery}: it accepts what
     * an operator types into a search box — quoted phrases, {@code -exclusion},
     * {@code or} — and treats malformed input as no match rather than raising.
     */
    private Sql match(Predicate.Match match) {
        return new Sql(
                "(NOT body_base64 AND to_tsvector('simple', body) @@ websearch_to_tsquery('simple', ?))",
                List.of(match.terms()));
    }

    // ---- terms ----------------------------------------------------------

    /**
     * A term becomes a column name from the catalogue or a JSONB path expression. The
     * only operator-supplied text that appears is a property or JSON key, and it goes
     * in as a bind, never as an identifier.
     */
    private Sql term(Term term) {
        return switch (term) {
            case Term.ColumnTerm(var column) -> new Sql(columnName(column), List.of());
            case Term.PropertyTerm(var name) -> new Sql("props ->> ?", List.of(name));
            case Term.JsonTerm(var path) -> new Sql("(body::jsonb #>> string_to_array(?, '.'))", List.of(path));
            case Term.CaseFold(var operand, var upper) -> {
                Sql inner = term(operand);
                yield new Sql((upper ? "upper(" : "lower(") + inner.text() + ')', inner.binds());
            }
            case Term.MatchRank _ ->
                // Only reachable through ORDER BY, which compiles it with the query's
                // own MATCH() terms. A rank in a predicate has nothing to rank against.
                throw new SqlSyntaxException("match_rank can only be used in ORDER BY.", "match_rank");
        };
    }

    private String columnName(Column column) {
        return switch (column) {
            case MESSAGE_ID -> "message_id";
            case SUBSCRIPTION -> "subscription";
            case DESTINATION -> "destination";
            case NODE -> "node_name";
            case PRIORITY -> "priority";
            case DURABLE -> "durable";
            case TIMESTAMP -> "timestamp_ms";
            case EXPIRATION -> "expiration_ms";
            case SIZE -> "size_bytes";
            case JMS_TYPE -> "jms_type";
            case CORRELATION_ID -> "correlation_id";
            case GROUP_ID -> "group_id";
            case USER_ID -> "user_id";
            case MESSAGE_TYPE -> "message_type";
            case REPLY_TO -> "reply_to";
            case BODY -> "body";
            case OBSERVED_AT -> "observed_at";
            case LAST_SEEN_AT -> "last_seen_at";
            case ORIGIN -> "origin";
            case ORIG_DESTINATION -> "orig_destination";
            case SOURCE_MESSAGE_ID -> "source_message_id";
        };
    }

    /**
     * A literal's bind value. A relative time against a millisecond column binds
     * milliseconds; against a timestamptz column it binds an instant — the same
     * predicate means the same thing either way, which is the point.
     */
    private Object value(Literal literal, Term against) {
        return switch (literal) {
            case Literal.Str(var value) -> value;
            case Literal.Num(var value, var integral) -> integral ? (Object) (long) value : value;
            case Literal.Bool(var value) -> value;
            case Literal.RelativeTime(var before) -> {
                Instant at = Instant.now().minus(before);
                yield isEpochMillisColumn(against) ? (Object) at.toEpochMilli() : Timestamp.from(at);
            }
        };
    }

    private boolean isEpochMillisColumn(Term term) {
        if (!(term instanceof Term.ColumnTerm(var column))) {
            return false;
        }
        return column == Column.TIMESTAMP || column == Column.EXPIRATION;
    }

    /**
     * {@code ORDER BY}, with {@code match_rank} compiled against the query's own
     * {@code MATCH()} terms.
     *
     * <p>Ranking without a {@code MATCH()} is refused rather than ignored. There is
     * nothing to rank against, so silently ordering by something else would answer a
     * different question than the one asked.
     */
    private Sql orderBy(List<Order> orders, String matchTerms) {
        if (orders.isEmpty()) {
            return new Sql(" ORDER BY observed_at DESC", List.of());
        }
        List<String> fragments = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        for (Order order : orders) {
            String direction = order.direction() == QueryAst.Direction.DESC ? " DESC" : " ASC";
            if (order.term() instanceof Term.MatchRank) {
                if (matchTerms == null) {
                    throw new SqlSyntaxException(
                            "match_rank ranks how well a row matched MATCH(), and this query has no MATCH()."
                                    + " Add MATCH(body, 'terms') to the WHERE clause, or order by another column.",
                            "match_rank");
                }
                fragments.add("ts_rank_cd(to_tsvector('simple', body), websearch_to_tsquery('simple', ?))" + direction);
                binds.add(matchTerms);
            } else if (order.term() instanceof Term.ColumnTerm(var column)) {
                fragments.add(columnName(column) + direction);
            }
        }
        return fragments.isEmpty()
                ? new Sql(" ORDER BY observed_at DESC", List.of())
                : new Sql(" ORDER BY " + String.join(", ", fragments), binds);
    }

    /** The first MATCH() terms in a predicate tree, or null when there is none. */
    private static String matchTerms(Predicate predicate) {
        return switch (predicate) {
            case null -> null;
            case Predicate.Match(var terms) -> terms;
            case Predicate.And(var parts) -> firstMatch(parts);
            case Predicate.Or(var parts) -> firstMatch(parts);
            case Predicate.Not(var inner) -> matchTerms(inner);
            default -> null;
        };
    }

    private static String firstMatch(List<Predicate> parts) {
        return parts.stream()
                .map(IndexQueryExecutor::matchTerms)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ---- mapping --------------------------------------------------------

    private Row toRow(ResultSet rs, UUID clusterId, boolean clearAccess) throws SQLException {
        Row row = new Row(
                rs.getObject("node_id", UUID.class),
                rs.getString("node_name"),
                rs.getString("subscription"),
                rs.getString("destination"),
                rs.getLong("message_id"),
                rs.getInt("message_type"),
                rs.getBoolean("durable"),
                rs.getInt("priority"),
                rs.getLong("timestamp_ms"),
                rs.getLong("expiration_ms"),
                rs.getLong("size_bytes"),
                rs.getString("jms_type"),
                rs.getString("correlation_id"),
                rs.getString("group_id"),
                rs.getString("user_id"),
                rs.getString("reply_to"),
                rs.getString("body"),
                rs.getBoolean("body_truncated"),
                rs.getBoolean("body_base64"),
                rs.getString("body_compression"),
                properties(rs.getString("props")),
                Source.INDEX,
                instant(rs.getTimestamp("observed_at")),
                instant(rs.getTimestamp("last_seen_at")),
                rs.getString("origin"),
                rs.getObject("source_message_id", Long.class));
        byte[] sealed = rs.getBytes("sealed");
        if (!clearAccess || sealed == null) {
            return row;
        }
        Map<String, String> originals = sealer.unseal(
                MessageIndexWriter.aad(clusterId, row.nodeId(), row.subscription(), row.messageId()), sealed);
        return SqlGovernance.withContent(row, ContentSealer.restore(SqlGovernance.content(row), originals));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(rawJson, Map.class);
        } catch (JacksonException _) {
            return new HashMap<>();
        }
    }
}
