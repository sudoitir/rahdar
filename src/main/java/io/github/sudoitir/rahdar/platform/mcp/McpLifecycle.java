package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import java.util.List;
import java.util.Locale;

/** A cluster-wide lifecycle command's outcome as the model sees it (ADR-0049). */
public final class McpLifecycle {

    private McpLifecycle() {}

    /**
     * The summary of an attempt: the per-node list in full, whether the command applied unevenly, and what
     * to do next.
     *
     * @param action the kind of command, as a model names it
     * @param destructive whether a real run needs {@code confirm}
     */
    public static McpViews.LifecycleOutcomeSummary summary(
            String action, String subject, boolean destructive, boolean dryRun, Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome =
                switch (attempt) {
                    case Attempt.Ok<LifecycleOutcome>(var value) -> value;
                    case Attempt.Failed<LifecycleOutcome>(var failure, var detail) ->
                        throw new BrokerConnectionException(failure, detail);
                };
        List<McpViews.LifecycleNode> nodes = outcome.nodes().stream()
                .map(n -> new McpViews.LifecycleNode(n.nodeName(), n.status().name(), n.affected(), n.error()))
                .toList();
        boolean partial = !dryRun
                && nodes.stream().anyMatch(n -> "APPLIED".equals(n.status()) || "ALREADY".equals(n.status()))
                && nodes.stream()
                        .anyMatch(n -> "FAILED".equals(n.status())
                                || "SKIPPED_NOT_LIVE".equals(n.status())
                                || "UNSUPPORTED_VERSION".equals(n.status()));
        return new McpViews.LifecycleOutcomeSummary(
                action.toLowerCase(Locale.ROOT),
                subject,
                dryRun,
                partial,
                outcome.totalAffected(),
                outcome.cap(),
                outcome.overCap(),
                nodes,
                message(subject, destructive, dryRun, outcome, partial));
    }

    private static String message(
            String subject, boolean destructive, boolean dryRun, LifecycleOutcome outcome, boolean partial) {
        if (dryRun) {
            if (outcome.overCap()) {
                return "Nothing was changed. A real run is over the bulk cap and would need override=true.";
            }
            String confirmation = destructive ? " and confirm=\"" + subject + "\"." : ".";
            return "Nothing was changed. Re-run with dryRun=false" + confirmation;
        }
        if (partial) {
            return "Applied unevenly across the cluster. The nodes list says which nodes are in the requested "
                    + "state and which are not; nothing was rolled back.";
        }
        return outcome.anyFailed() ? "Failed on every node it reached." : "Applied.";
    }
}
