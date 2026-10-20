package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import io.github.sudoitir.rahdar.platform.governance.Location;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The content policy applied to the SQL console (sql-console spec): governed result rows, residual
 * predicates evaluated over masked content, and a refusal for predicates on masked fields.
 */
@Component
@RequiredArgsConstructor
public class SqlGovernance {

    private static final String HEADER_CORRELATION_ID = "correlationId";
    private static final String HEADER_GROUP_ID = "groupId";
    private static final String HEADER_USER_ID = "userId";
    private static final String HEADER_REPLY_TO = "replyTo";

    /** The catalogue columns that are message headers, by the header name the policy knows them as. */
    private static final Map<Column, String> HEADER_COLUMNS = Map.of(
            Column.CORRELATION_ID, HEADER_CORRELATION_ID,
            Column.GROUP_ID, HEADER_GROUP_ID,
            Column.USER_ID, HEADER_USER_ID,
            Column.REPLY_TO, HEADER_REPLY_TO);

    private final ContentPolicy policy;

    /**
     * Whether the current caller holds {@code message:clear} on every queue the pattern can match. Only
     * meaningful on a request thread.
     */
    public boolean clearAccess(UUID clusterId, String queuePattern) {
        return policy.mayClear(clusterId, queuePattern);
    }

    /** A result row as the caller may see it. */
    public GovernedMessage govern(UUID clusterId, boolean clearAccess, Row row) {
        return policy.govern(new GovernContext(clusterId, row.address(), clearAccess), content(row));
    }

    /** A row as Rahdar may store it: masked for everyone, with sealable originals collected. */
    public GovernedMessage forStorage(UUID clusterId, Row row) {
        return forStorage(clusterId, row.address(), content(row));
    }

    public GovernedMessage forStorage(UUID clusterId, String address, MessageContent content) {
        return policy.governForStorage(clusterId, address, content);
    }

    /** The row with its headers, properties and body replaced by {@code content}'s. */
    static Row withContent(Row row, MessageContent content) {
        return new Row(
                row.nodeId(),
                row.nodeName(),
                row.queueName(),
                row.address(),
                row.messageId(),
                row.messageType(),
                row.durable(),
                row.priority(),
                row.timestamp(),
                row.expiration(),
                row.size(),
                row.jmsType(),
                content.headers().get(HEADER_CORRELATION_ID),
                content.headers().get(HEADER_GROUP_ID),
                content.headers().get(HEADER_USER_ID),
                content.headers().get(HEADER_REPLY_TO),
                content.body(),
                row.bodyTruncated(),
                row.bodyBase64(),
                row.bodyCompression(),
                content.properties(),
                row.source(),
                row.observedAt(),
                row.lastSeenAt(),
                row.origin(),
                row.sourceMessageId());
    }

    static MessageContent content(Row row) {
        Map<String, String> headers = new HashMap<>();
        headers.put(HEADER_CORRELATION_ID, row.correlationId());
        headers.put(HEADER_GROUP_ID, row.groupId());
        headers.put(HEADER_USER_ID, row.userId());
        headers.put(HEADER_REPLY_TO, row.replyTo());
        return new MessageContent(headers, row.properties(), row.body(), row.bodyBase64(), null);
    }

    /**
     * The message a residual predicate is evaluated against for a caller without clear access: the masked
     * copy, so a body search cannot confirm a value the caller would only ever see masked.
     */
    public BrowsedMessage forEvaluation(UUID clusterId, String address, BrowsedMessage m) {
        GovernedMessage g = policy.govern(GovernContext.masked(clusterId, address), MessageService.content(m));
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        g.properties().forEach((name, value) -> {
            if (value == null) {
                return;
            }
            switch (value) {
                case Long l when m.intProperties().containsKey(name) -> ints.put(name, l);
                case Long l when m.longProperties().containsKey(name) -> longs.put(name, l);
                case Double d -> doubles.put(name, d);
                case Boolean b -> booleans.put(name, b);
                default -> strings.put(name, String.valueOf(value));
            }
        });
        return new BrowsedMessage(
                m.messageId(),
                m.type(),
                m.durable(),
                m.priority(),
                m.timestamp(),
                m.expiration(),
                m.size(),
                g.headers().get(HEADER_GROUP_ID),
                g.headers().get(HEADER_CORRELATION_ID),
                g.headers().get(HEADER_REPLY_TO),
                g.headers().get(HEADER_USER_ID),
                g.body(),
                m.bodyEncoding(),
                m.bodyCompression(),
                m.contentType(),
                m.bodyTruncated(),
                m.observedLimitBytes(),
                strings,
                ints,
                longs,
                doubles,
                booleans);
    }

    /**
     * Refuse a query whose predicate or ordering names a field a masking rule covers, unless the caller holds
     * clear access. Ordering counts: sorting by a masked value reveals how the hidden values compare.
     */
    public void guardPredicates(UUID clusterId, QueryAst ast) {
        String field = maskedField(ast);
        if (field != null && !clearAccess(clusterId, ast.queuePattern())) {
            throw new GovernanceRefusedException(field);
        }
    }

    /** The first field a predicate or ordering names that a masking rule covers, or null. */
    public String maskedField(QueryAst ast) {
        List<Term> terms = new ArrayList<>();
        collect(ast.where(), terms);
        if (ast.orderBy() != null) {
            ast.orderBy().forEach(order -> terms.add(order.term()));
        }
        for (Term term : terms) {
            String field = classifiedField(term);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    /**
     * An index query that names a masked field says so (message-index spec): the index holds the masked value,
     * so the predicate compares masked text. Only a caller with clear access reaches this; others were refused.
     */
    public QueryPlan withAtRestNotice(QueryPlan plan) {
        if (plan.resolvedSource() != QueryAst.Source.INDEX) {
            return plan;
        }
        String field = maskedField(plan.ast());
        if (field == null) {
            return plan;
        }
        List<QueryPlan.Notice> notices = new ArrayList<>(plan.notices());
        notices.add(new QueryPlan.Notice(
                QueryPlan.Notice.Kind.MASKED_AT_REST,
                "The index stores " + field + " masked, so this predicate compares masked text and cannot match an"
                        + " original value. Query the live broker to match the originals."));
        return new QueryPlan(
                plan.ast(),
                plan.resolvedSource(),
                plan.targets(),
                plan.selector(),
                plan.requiresScan(),
                plan.pushedDown(),
                plan.scanned(),
                plan.estimatedMessagesExamined(),
                plan.effectiveLimit(),
                plan.captured(),
                List.copyOf(notices));
    }

    private static void collect(Predicate predicate, List<Term> out) {
        if (predicate == null) {
            return;
        }
        switch (predicate) {
            case Predicate.And(var parts) -> parts.forEach(p -> collect(p, out));
            case Predicate.Or(var parts) -> parts.forEach(p -> collect(p, out));
            case Predicate.Not(var inner) -> collect(inner, out);
            case Predicate.Compare compare -> out.add(compare.term());
            case Predicate.In in -> out.add(in.term());
            case Predicate.IsNull isNull -> out.add(isNull.term());
            case Predicate.Like like -> out.add(like.term());
            case Predicate.Between between -> out.add(between.term());
            case Predicate.Match _ -> {
                // Full-text search runs over the stored body, which holds only masked values.
            }
        }
    }

    private String classifiedField(Term term) {
        return switch (term) {
            case Term.PropertyTerm(var name) -> policy.classifies(Location.PROPERTY, name) ? "props." + name : null;
            case Term.JsonTerm(var path) -> policy.classifies(Location.BODY, path) ? "body->>'" + path + "'" : null;
            case Term.ColumnTerm(var column) -> {
                String header = HEADER_COLUMNS.get(column);
                yield header != null && policy.classifies(Location.HEADER, header) ? column.sqlName() : null;
            }
            case Term.CaseFold(var inner, var _) -> classifiedField(inner);
            case Term.MatchRank _ -> null;
        };
    }
}
