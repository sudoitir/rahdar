package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.util.List;
import java.util.Set;

/**
 * Declares one gated operation type: one bean per type. Rahdar's types are named like {@code
 * subscription.purge}; a plugin's are {@code <pluginId>:<name>}. Every method but {@link #replay} must be
 * cheap and free of side effects, because the gate calls them on every request.
 *
 * @param <P> the parameters, a record that {@link CanonicalJson} can write
 */
@PluginApi
public interface GatedOperation<P extends Record> {

    String type();

    /**
     * Raised whenever the parameters or the meaning of the operation change. A held request of an
     * older version is refused when it would run.
     */
    int version();

    Class<P> paramsType();

    /** The traits of this request; {@link Trait#GATE_INTEGRITY} may depend on the parameters. */
    Set<Trait> traits(P params);

    ExecutionMode mode();

    OperationScope scope(P params);

    /** One line for lists and notices, such as "Purge queue orders on prod-eu". Never a secret. */
    String summary(P params);

    /** What will change, row by row, for the approver. Secret values are shown redacted. */
    List<DisplayRow> display(P params);

    /**
     * JSON Pointers ({@code /password}) into the canonical parameters whose values are redacted in the
     * copy approvers and providers see. The sealed original keeps them until the operation ends.
     */
    Set<String> redactedPaths();

    /** The effect as the current user sees it now. Throwing fails the request closed. */
    Effect estimate(P params);

    /** Runs the operation again, as the requester, after approval: the same public method the request called. */
    void replay(P params);
}
