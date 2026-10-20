package io.github.sudoitir.rahdar.platform.broker;

import java.time.Instant;
import org.apache.activemq.artemis.selector.filter.FilterException;
import org.apache.activemq.artemis.selector.impl.SelectorParser;

/**
 * A message filter frozen at a moment (ADR-0097): only messages whose producer timestamp is at or
 * before {@code t0} match, so a selection over a queue that is still being produced to has an end.
 */
public final class FrozenFilter {

    private FrozenFilter() {}

    /**
     * Whether {@code filter} is one complete selector expression. Parenthesising alone does not
     * contain a filter: {@code a=1) OR (1=1} closes the parenthesis itself and escapes the clause, so
     * a filter is parsed with the broker's own selector grammar before it is composed.
     */
    public static boolean isCompleteExpression(String filter) {
        try {
            SelectorParser.parse(filter.strip());
            return true;
        } catch (FilterException | RuntimeException e) {
            return false;
        }
    }

    /**
     * {@code (<filter>) AND AMQTimestamp <= <t0 millis>}, or the timestamp clause alone when there
     * is no filter.
     *
     * @throws IllegalArgumentException when the filter is not one complete selector expression
     */
    public static String compose(String filter, Instant t0) {
        String frozen = "AMQTimestamp <= " + t0.toEpochMilli();
        if (filter == null || filter.isBlank()) {
            return frozen;
        }
        if (!isCompleteExpression(filter)) {
            throw new IllegalArgumentException("Not a complete selector expression: " + filter.strip());
        }
        return "(" + filter.strip() + ") AND " + frozen;
    }
}
