package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * Runbooks as prompts (ADR-0045): the order to do things in, which a tool
 * description cannot carry.
 *
 * <p>Every one of these is stateless and orchestrating only — it returns text and
 * calls nothing. That is not an implementation shortcut: a prompt that acted would
 * be a mutation with no {@code confirm} gate and no audit attribution of its own,
 * which is precisely what the safety model forbids. {@code before_you_purge} is the
 * clearest case — it prescribes the sequence and then stops at asking the human,
 * rather than ever prescribing a confirmed run.
 *
 * <p>A runbook names tools, and a tool exists only while its feature is enabled. When a
 * step's tool is missing, the prompt says so and names the setting that restores it, so
 * the model skips the step and reports it instead of failing on a call it cannot make.
 */
@Component
@RequiredArgsConstructor
public class McpRunbookPrompts {

    private static final String DIAGNOSE = "diagnose";

    private final McpToolCatalog catalog;
    private final FeatureRegistry features;

    @McpPrompt(name = "triage_cluster", description = "The order to investigate a cluster that looks unhealthy.")
    public McpSchema.GetPromptResult triageCluster(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId) {
        String target = target(clusterId);
        return prompt("Triage runbook", """
                Triage %s in this order, and stop as soon as one step explains the symptom.

                1. diagnose with no subscription. A splitBrain of CRITICAL is the whole answer — stop and \
                escalate; two nodes are accepting writes and the journals are diverging. \
                SUSPECTED during a failover is normal for a few seconds.
                2. If alertsVisible is false, this key cannot see alerts. Say so rather than \
                reporting that nothing is firing. Check clock too: a verdict of \
                RAHDAR_SUSPECT or BROKER_SKEWED means timeouts and latencies were measured \
                against a clock that disagrees, so treat those numbers as suspect and say so.
                3. replicationBehind means the backup cannot take over cleanly yet. That is a \
                risk, not an outage.
                4. list_resources role=subscription, sorted by depth. A deep subscription with zero \
                consumers is a stuck consumer, not a broker fault.
                5. diagnose subscription=<the id of the worst subscription>. Its trend separates a \
                backlog that is draining from one that is growing.
                6. activity_log source=broker_events for what the broker itself reported around \
                the time the symptom started.

                Report what you found and what you did not check. Do not run any mutating tool \
                as part of triage.""".formatted(target), DIAGNOSE, "list_resources", "activity_log");
    }

    @McpPrompt(
            name = "investigate_subscription",
            description = "How to work out why one subscription is deep, and what would actually fix it.")
    public McpSchema.GetPromptResult investigateSubscription(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId,
            @McpArg(
                            name = "subscription",
                            description = "Subscription id, such as artemis.queue/orders",
                            required = false)
                    String subscription) {
        return prompt(
                "Subscription investigation runbook",
                """
                Investigate %s on %s.

                1. diagnose with the subscription id. Read trend before depth: a deep subscription that is draining needs \
                patience, not intervention.
                2. Zero consumers with a growing depth is a consumer-side problem. The broker is \
                working. Fixing it here — by purging — destroys the evidence and the messages.
                3. list_resources role=consumer filtered to this subscription, to see whether consumers \
                are attached but not acknowledging.
                4. browse_messages for a few headers. Check whether they are all one type, one \
                correlation id, or all expired.
                5. Only read a body (browse_messages with position) once the headers have narrowed \
                it to a specific message worth looking at.
                6. activity_log source=broker_events filtered to its destination, for consumer \
                connect/disconnect churn.

                State the cause before proposing an action. If the cause is consumer-side, say so \
                and stop — no broker-side action fixes it.""".formatted(
                                subscription == null || subscription.isBlank() ? "the subscription" : subscription,
                                target(clusterId)),
                DIAGNOSE,
                "list_resources",
                "browse_messages",
                "activity_log");
    }

    @McpPrompt(
            name = "before_you_purge",
            description = "The checks to run before any destructive subscription operation, and where to stop.")
    public McpSchema.GetPromptResult beforeYouPurge(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId,
            @McpArg(
                            name = "subscription",
                            description = "Subscription id, such as artemis.queue/orders",
                            required = false)
                    String subscription) {
        return prompt(
                "Pre-purge checklist",
                """
                Purging discards messages permanently. There is no undo and no recovery from the \
                broker side. Before proposing one on %s:

                1. message_action with the defaults. dryRun is true unless you set it false, so this \
                returns the count that would be affected and changes nothing.
                2. diagnose with the subscription id. If consumers are attached and the depth is falling, it is \
                draining on its own — purging is the wrong action.
                3. browse_messages. Look at what is actually there. Messages that a fixed consumer \
                would process are messages a purge would destroy.
                4. Check whether the subscription is a dead-letter queue. Purging one discards the record of what \
                already failed.
                5. If the dry run reports overCap, the count is above the configured safety cap. \
                That is a signal about scale, not an obstacle to route around.

                Then stop. Report the count, what the messages appear to be, and your \
                recommendation — and ask the operator to confirm. Do not call message_action with \
                dryRun=false on your own initiative; the confirm argument exists so that a human \
                decision is what unlocks a destructive run, and supplying it yourself defeats it.""".formatted(subject(subscription, clusterId)),
                "message_action",
                DIAGNOSE,
                "browse_messages");
    }

    @McpPrompt(
            name = "tune_scrape_load",
            description = "How to decide whether Rahdar's polling is the load on the broker, and what to change.")
    public McpSchema.GetPromptResult tuneScrapeLoad(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId) {
        return prompt("Scrape tuning runbook", """
                Work out whether %s is under load from Rahdar itself before changing anything.

                1. rahdar_setting op=get. Read the current scrape intervals, the per-node rate \
                limit and the bulk cap together — they interact.
                2. Rahdar batches its reads: one management call per node per tier, not one per \
                subscription. A cluster with thousands of subscriptions costs the same per tick as a small one, \
                so their count alone is not evidence.
                3. metric_series on openbroker.backlog for the cluster over 6h. If broker load tracks \
                message volume rather than a fixed cadence, Rahdar is not the cause.
                4. If it is the cause, raise tier-c first — the full subscription walk is the expensive \
                one and the least time-sensitive. Leave tier-a alone: it is what detects \
                split-brain, and slowing it delays the one alert that matters most.
                5. Lower the per-node rate limit only if the broker is rejecting calls. It \
                throttles Rahdar, so it makes every screen slower.

                Say what you would change and what it costs before changing it.""".formatted(target(clusterId)), "rahdar_setting", "metric_series");
    }

    private static String target(String clusterId) {
        return clusterId == null || clusterId.isBlank() ? "the cluster" : "cluster " + clusterId;
    }

    private static String subject(String subscription, String clusterId) {
        String s = subscription == null || subscription.isBlank() ? "the subscription" : "subscription " + subscription;
        return s + " on " + target(clusterId);
    }

    private McpSchema.GetPromptResult prompt(String description, String text, String... tools) {
        return new McpSchema.GetPromptResult(
                description,
                List.of(new McpSchema.PromptMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent(text + unavailable(tools)))));
    }

    /** The runbook's tools whose feature is disabled, with the setting that enables each; empty when all exist. */
    String unavailable(String... tools) {
        var offered = catalog.toolNames();
        List<String> missing = Arrays.stream(tools)
                .filter(tool -> !offered.contains(tool))
                .map(this::describe)
                .toList();
        if (missing.isEmpty()) {
            return "";
        }
        return "\n\nSome steps name a tool this Rahdar does not offer, because its feature is disabled: "
                + String.join("; ", missing)
                + ". Skip those steps, and say which checks you could not make.";
    }

    private String describe(String tool) {
        return features.all().stream()
                .filter(feature ->
                        feature.mcpTools().stream().anyMatch(def -> def.name().equals(tool)))
                .findFirst()
                .map(feature -> tool + " (" + feature.title() + " is disabled; set rahdar.features." + feature.id()
                        + ".enabled=true)")
                .orElse(tool);
    }
}
