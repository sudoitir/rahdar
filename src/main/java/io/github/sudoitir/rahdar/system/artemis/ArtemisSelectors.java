package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.artemis.ArtemisFilters;
import io.github.sudoitir.openbroker.messaging.MessageFilter;
import java.time.Instant;
import java.util.Optional;

/**
 * A selection frozen at a moment (ADR-0097), as Artemis's management operations take it: a selector
 * matching only messages produced at or before {@code t0}, so a selection over a queue that is still
 * being produced to has an end.
 */
public final class ArtemisSelectors {

    private ArtemisSelectors() {}

    /**
     * Whether {@code filter} is one complete selector expression. Parenthesising alone does not contain
     * a filter: {@code a=1) OR (1=1} closes the parenthesis itself and escapes the clause, so a filter
     * is parsed with the broker's own selector grammar before it is composed.
     */
    public static boolean isCompleteExpression(String filter) {
        try {
            frozen(filter, Instant.EPOCH);
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * {@code (<filter>) AND <produced at or before t0>}, or the bound alone when there is no filter.
     *
     * @throws IllegalArgumentException when the filter is not one complete selector expression
     */
    public static String frozen(String filter, Instant t0) {
        Optional<MessageFilter> given =
                filter == null || filter.isBlank() ? Optional.empty() : Optional.of(ArtemisFilters.selector(filter));
        try {
            return ArtemisFilters.producedAtOrBefore(given, t0).expression();
        } catch (BrokerException e) {
            throw new IllegalArgumentException("Not a complete selector expression.", e);
        }
    }
}
