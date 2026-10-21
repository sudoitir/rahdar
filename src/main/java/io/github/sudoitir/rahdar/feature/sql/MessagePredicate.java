package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluates the residual half of a split {@code WHERE} clause — everything the
 * broker's selector cannot do — against a message the broker already returned
 * (ADR-0058 D4). This is the only part of a query that costs anything, which is why
 * the plan tells the operator it is there before the query runs.
 *
 * <p>Comparison follows SQL's three-valued logic collapsed to a selection decision:
 * a comparison against an absent value is unknown, and an unknown row is not
 * selected. That matches how the broker's own selector behaves, so a predicate does
 * not change meaning depending on which side of the split it landed on.
 */
@Component
public class MessagePredicate {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * What a residual predicate can see beyond the message itself: the target it was
     * read from, and the broker-time "now" a relative window resolves against.
     */
    public record EvalContext(String subscription, String destination, String node, Instant now) {}

    public java.util.function.Predicate<BrowsedMessage> compile(Predicate predicate, EvalContext context) {
        if (predicate == null) {
            return message -> true;
        }
        return message -> matches(predicate, message, context);
    }

    public boolean matches(Predicate predicate, BrowsedMessage message, EvalContext context) {
        return switch (predicate) {
            case Predicate.And(var parts) -> parts.stream().allMatch(p -> matches(p, message, context));
            case Predicate.Or(var parts) -> parts.stream().anyMatch(p -> matches(p, message, context));
            case Predicate.Not(var inner) -> !matches(inner, message, context);
            case Predicate.Compare compare -> compare(compare, message, context);
            case Predicate.In in -> in(in, message, context);
            case Predicate.IsNull(var term, var negated) -> (resolve(term, message, context) == null) != negated;
            case Predicate.Like like -> like(like, message, context);
            case Predicate.Between between -> between(between, message, context);
            // Unreachable: the planner refuses MATCH() against a live broker before a
            // message is ever read. Answering false here would silently drop rows; a
            // failure says which layer let it through.
            case Predicate.Match _ ->
                throw new IllegalStateException("MATCH() cannot be evaluated against a broker message");
        };
    }

    // ---- predicate kinds -----------------------------------------------

    private boolean compare(Predicate.Compare compare, BrowsedMessage message, EvalContext context) {
        Object left = resolve(compare.term(), message, context);
        if (left == null) {
            return false;
        }
        Integer order = order(left, compare.value(), context);
        if (order == null) {
            return false;
        }
        return switch (compare.op()) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            case GT -> order > 0;
            case GTE -> order >= 0;
        };
    }

    private boolean in(Predicate.In in, BrowsedMessage message, EvalContext context) {
        Object left = resolve(in.term(), message, context);
        if (left == null) {
            return false;
        }
        boolean found = in.values().stream().anyMatch(v -> {
            Integer order = order(left, v, context);
            return order != null && order == 0;
        });
        return found != in.negated();
    }

    private boolean between(Predicate.Between between, BrowsedMessage message, EvalContext context) {
        Object left = resolve(between.term(), message, context);
        if (left == null) {
            return false;
        }
        Integer low = order(left, between.low(), context);
        Integer high = order(left, between.high(), context);
        if (low == null || high == null) {
            return false;
        }
        return (low >= 0 && high <= 0) != between.negated();
    }

    private boolean like(Predicate.Like like, BrowsedMessage message, EvalContext context) {
        Object left = resolve(like.term(), message, context);
        if (left == null) {
            return false;
        }
        int flags = like.caseInsensitive() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        Pattern pattern = Pattern.compile(likeToRegex(like.pattern(), like.escape()), flags | Pattern.DOTALL);
        return pattern.matcher(String.valueOf(left)).matches() != like.negated();
    }

    /**
     * SQL {@code LIKE} to a regex: {@code %} is any run, {@code _} is one character,
     * everything else is a literal. Every other character is quoted, so a pattern
     * carrying regex metacharacters matches them literally rather than becoming a
     * different pattern than the operator wrote.
     */
    static String likeToRegex(String pattern, Character escape) {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                out.append(Pattern.quote(String.valueOf(c)));
                escaped = false;
            } else if (escape != null && c == escape) {
                escaped = true;
            } else if (c == '%') {
                out.append(".*");
            } else if (c == '_') {
                out.append('.');
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (escaped) {
            // A trailing escape character escapes nothing; treat it as a literal
            // rather than dropping it, so the pattern still means what it looks like.
            out.append(Pattern.quote(String.valueOf(escape)));
        }
        return out.toString();
    }

    // ---- term resolution -----------------------------------------------

    /** The message's value for a term, or null when it has none. */
    Object resolve(Term term, BrowsedMessage message, EvalContext context) {
        return switch (term) {
            case Term.ColumnTerm(var column) -> column(column, message, context);
            case Term.PropertyTerm(var name) -> property(name, message);
            case Term.JsonTerm(var path) -> jsonPath(path, message);
            case Term.MatchRank _ -> null;
            case Term.CaseFold(var operand, var upper) -> {
                Object inner = resolve(operand, message, context);
                if (inner == null) {
                    yield null;
                }
                String text = String.valueOf(inner);
                yield upper ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
            }
        };
    }

    private Object column(Column column, BrowsedMessage message, EvalContext context) {
        return switch (column) {
            case MESSAGE_ID -> Long.toString(message.messageId());
            case SUBSCRIPTION -> context.subscription();
            case DESTINATION -> context.destination();
            case NODE -> context.node();
            case PRIORITY -> (long) message.priority();
            case DURABLE -> message.durable();
            case TIMESTAMP -> message.timestamp();
            case EXPIRATION -> message.expiration();
            case SIZE -> message.size();
            case JMS_TYPE -> message.contentType();
            case CORRELATION_ID -> message.correlationId();
            case GROUP_ID -> message.groupId();
            case USER_ID -> message.userId();
            case MESSAGE_TYPE -> (long) message.type();
            case REPLY_TO -> message.replyTo();
            case BODY -> message.body();
            // An observation column has no meaning against a live broker; the planner
            // rejects the query before it gets here, so this is a backstop.
            case OBSERVED_AT, LAST_SEEN_AT, ORIGIN, ORIG_DESTINATION, SOURCE_MESSAGE_ID -> null;
        };
    }

    private Object property(String name, BrowsedMessage message) {
        if (message.stringProperties().containsKey(name)) {
            return message.stringProperties().get(name);
        }
        if (message.longProperties().containsKey(name)) {
            return message.longProperties().get(name);
        }
        if (message.intProperties().containsKey(name)) {
            return message.intProperties().get(name);
        }
        if (message.doubleProperties().containsKey(name)) {
            return message.doubleProperties().get(name);
        }
        if (message.booleanProperties().containsKey(name)) {
            return message.booleanProperties().get(name);
        }
        return null;
    }

    /** {@code body->>'a.b'} — dotted keys (an array's by index), text out, null when the body is not JSON. */
    private Object jsonPath(String path, BrowsedMessage message) {
        String body = message.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JacksonException _) {
            return null;
        }
        for (String key : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            // An array steps by index, as Postgres' #>> does over the index, so one path reads both sources.
            node = node.isArray() && key.matches("\\d{1,9}") ? node.get(Integer.parseInt(key)) : node.get(key);
        }
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }

    // ---- comparison ----------------------------------------------------

    /**
     * Orders a resolved value against a literal, or null when they are not
     * comparable — which, like an absent value, means the row is not selected.
     */
    private Integer order(Object left, Literal right, EvalContext context) {
        return switch (right) {
            case Literal.Str(var value) ->
                left instanceof Boolean ? null : String.valueOf(left).compareTo(value);
            case Literal.Num num -> {
                Double value = asNumber(left);
                yield value == null ? null : Double.compare(value, num.value());
            }
            case Literal.Bool(var value) -> left instanceof Boolean b ? Boolean.compare(b, value) : null;
            case Literal.RelativeTime(var before) -> {
                Double value = asNumber(left);
                yield value == null
                        ? null
                        : Double.compare(
                                value, (double) context.now().minus(before).toEpochMilli());
            }
        };
    }

    private Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }
}
