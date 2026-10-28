package io.github.sudoitir.rahdar.feature.sql.web;

import io.github.sudoitir.rahdar.platform.governance.GovernanceViews.RedactionView;
import io.github.sudoitir.rahdar.platform.governance.GovernanceViews.WithheldView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ProblemDetail;

/**
 * The SQL Console's wire shapes (ADR-0058). Provenance and incompleteness are
 * carried in the data rather than reconstructed by the client: a row says which
 * backend produced it, a result says which nodes did not answer and which bound it
 * hit, and a plan says what a query will cost before it runs.
 */
public final class SqlViews {

    private SqlViews() {}

    @Schema(description = "A query to plan or run.")
    public record SqlQueryRequest(String sql) {}

    @Schema(description = "A query to execute, and whether to keep tailing after the first pass.")
    public record SqlExecuteRequest(String sql, Boolean tail) {}

    @Schema(
            description = "A short-lived, single-use reference to a query. The stream is opened by this"
                    + " reference so the query text never appears in a URL.")
    public record SqlQueryTicketView(UUID queryId, String expiresAt) {}

    @Schema(description = "What a query will do, worked out without contacting a broker.")
    public record QueryPlanView(
            @Schema(description = "BROKER or INDEX — which backend will answer.")
            String source,

            @Schema(description = "The queue-and-node pairs the query will read.")
            List<TargetView> targets,

            @Schema(description = "The JMS selector the broker will evaluate, if any.", nullable = true)
            String selector,

            @Schema(description = "Whether any predicate forces messages to be read and examined.")
            boolean requiresScan,

            @Schema(description = "The predicates the broker evaluates. These cost nothing.")
            List<String> pushedDown,

            @Schema(description = "The predicates Rahdar evaluates. These cost a scan.")
            List<String> scanned,

            @Schema(description = "How many messages the query is expected to examine.")
            long estimatedMessagesExamined,

            @Schema(description = "The row limit that will actually apply, after the server cap.")
            int effectiveLimit,

            @Schema(
                    description = "True when every target of this query is captured on the node it is read"
                            + " from, which is what lets the console claim the result is everything the"
                            + " address routed rather than everything a poll happened to see.")
            boolean captured,

            @Schema(description = "Everything true about this plan the operator has to be told.")
            List<NoticeView> notices) {}

    public record TargetView(
            UUID nodeId,
            String nodeName,
            @Schema(description = "The subscription's id.") String subscription,

            @Schema(description = "The id of the destination the subscription is bound to.")
            String destination,

            long messageCount) {}

    @Schema(description = "Something true about a plan or result that changes how it should be read.")
    public record NoticeView(String kind, String detail) {}

    @Schema(description = "A message, and where it came from.")
    public record RowView(
            @Schema(description = "The node holding the message. Identity is (node, subscription, messageId).")
            UUID nodeId,

            String nodeName,
            @Schema(description = "The subscription's id.") String subscription,

            @Schema(description = "The id of the destination the subscription is bound to.")
            String destination,

            long messageId,
            int messageType,
            boolean durable,
            int priority,
            long timestamp,
            long expiration,
            long size,
            String jmsType,
            String correlationId,
            String groupId,
            String userId,
            String replyTo,
            String body,

            @Schema(description = "The management channel cut this body, so a body predicate may be a false negative.")
            boolean bodyTruncated,

            Map<String, Object> properties,

            @Schema(description = "BROKER or INDEX — an INDEX row may have been consumed since.")
            String source,

            @Schema(description = "When the index observed it. Null for a live broker row.")
            String observedAt,

            @Schema(description = "When the index last still saw it on its queue.")
            String lastSeenAt,

            @Schema(
                    description = "SAMPLED or CAPTURED for an indexed row, null for a live one. A sampled row"
                            + " says a poll saw this message; a captured one says the address routed it.")
            String origin,

            @Schema(
                    description = "The message's id on its source queue, for a captured row. Null when the"
                            + " broker did not record the message's origin id, which is what makes verifying it"
                            + " against the live broker impossible.")
            Long sourceMessageId,

            @Schema(description = "Every sensitive value in this row, masked or shown in clear by grant.")
            List<RedactionView> redactions,

            @Schema(description = "Content of this row that was not shown, and why.")
            List<WithheldView> withheld) {}

    @Schema(description = "What one node contributed, including nothing and why.")
    public record SqlNodeOutcomeView(
            UUID nodeId,
            String nodeName,
            @Schema(description = "The subscription's id.") String subscription,

            @Schema(description = "ANSWERED, FAILED or NOT_READ.")
            String status,

            long examined,
            long matched,

            String detail) {}

    @Schema(description = "A limit the query ran into, which is why it stopped.")
    public record BoundView(String kind, long value) {}

    @Schema(description = "A finished query, without its rows: those arrived as row frames.")
    public record ResultView(
            @Schema(description = "Every target's outcome, including the ones that did not answer.")
            List<SqlNodeOutcomeView> nodes,

            @Schema(description = "Empty when the query ran to completion.")
            List<BoundView> boundsReached,

            List<NoticeView> notices,

            @Schema(description = "True when a bound was hit or a node did not answer.")
            boolean partial,

            QueryPlanView plan) {}

    @Schema(description = "What a live tail has seen, and what it can prove it missed.")
    public record TailStatusView(
            @Schema(description = "Messages enqueued on the tailed queues since the tail started.")
            long enqueued,

            @Schema(description = "Rows this tail has delivered.")
            long shown,

            long polls,

            String lastPollAt,

            @Schema(
                    description = "True when the query has no predicate, so enqueued minus shown is"
                            + " exactly the number that passed through unobserved. When false the"
                            + " difference also holds messages that simply did not match.")
            boolean everyMessageMatches) {}

    /**
     * One frame on the query stream. Exactly one field is set, and the SSE event name
     * says which: {@code row}, {@code node}, {@code done}, {@code tail} or
     * {@code failed}. The failure frame is not named {@code error}: an
     * {@code EventSource} dispatches its own connection failures under that name, and
     * a client cannot then tell a refusal from a dropped socket. Declared as one record so every frame shape reaches the generated
     * client, which cannot describe a stream any other way. The plan is not a frame:
     * the console already has it from {@code /plan}, and the one that actually ran
     * comes back inside {@code done}.
     */
    @Schema(description = "One frame on the query stream. The SSE event name says which field is set.")
    public record StreamFrameView(
            RowView row, SqlNodeOutcomeView node, ResultView done, TailStatusView tail, ProblemDetail error) {}

    @Schema(description = "Which indexed row to look for on the live broker.")
    public record VerifyRequest(UUID nodeId, String subscription, long messageId, long timestamp) {}

    @Schema(description = "Whether an indexed row is still on the broker.")
    public record VerifyView(
            @Schema(
                    description = "PRESENT, GONE or UNKNOWN. UNKNOWN is not GONE: a read that could not"
                            + " settle the question is never reported as an absence.")
            String presence,

            String detail) {}

    // ---- index subscriptions -------------------------------------------

    @Schema(description = "A request to start capturing a queue's messages into the index.")
    public record IndexSubscriptionRequest(
            String subscriptionPattern,
            Integer retentionDays,
            Long intervalMs,
            Boolean enabled,

            @Schema(
                    description = "SAMPLE polls the queue and records what it saw; CAPTURE installs a"
                            + " divert-fed tap on every live node and records everything the address"
                            + " routed. CAPTURE mutates broker routing and needs capture:write.",
                    allowableValues = {"SAMPLE", "CAPTURE"})
            String mode,

            @Schema(description = "Messages the capture queue holds before the broker drops the oldest.")
            Long ringSize,

            @Schema(description = "An Artemis filter applied by the divert, narrowing both load and exposure.")
            String filterString,

            @Schema(description = "Payload bytes this subscription may hold before it degrades.")
            Long maxBytes,

            @Schema(description = "Messages per second this subscription may ingest.")
            Integer maxRate,

            @Schema(description = "Bytes of body stored per message; a longer body is stored truncated.")
            Integer bodyCapBytes) {}

    @Schema(
            description = "What creating a capture subscription would do, without doing it: what it covers, where,"
                    + " how much it may hold, and the broker objects and configuration it amounts to.")
    public record CapturePreviewView(
            @Schema(
                    description =
                            "The ids of the destinations the pattern resolves to. Rahdar's own capture destinations are"
                                    + " never included.")
            List<String> destinations,

            @Schema(description = "The live nodes the tap would be installed on.")
            List<String> nodes,

            @Schema(description = "Messages each capture queue holds before the broker drops the oldest.")
            long ringMessages,

            @Schema(description = "Bytes each capture queue holds before the broker drops the oldest.")
            long ringBytes,

            @Schema(description = "The broker objects created on every listed node.")
            List<String> brokerObjects,

            @Schema(description = "The equivalent permanent broker.xml for a configuration-managed estate.")
            String brokerXml,

            @Schema(
                    nullable = true,
                    description = "Why capture would be refused as things stand, or null when it would not.")
            String refusal) {}

    @Schema(description = "What capture is doing on one node. Per node, because a tap is a node-local object.")
    public record CaptureNodeView(
            UUID nodeId,
            String nodeName,

            @Schema(allowableValues = {"PENDING", "ACTIVE", "DEGRADED", "FAILED"})
            String state,

            @Schema(description = "Why it is in that state, in the words to show the operator.")
            String detail,

            @Schema(description = "When this node started being captured. Null when it never has been.")
            String capturedFrom,

            long droppedEstimate) {}

    @Schema(description = "One index subscription and what it currently holds.")
    public record IndexSubscriptionView(
            UUID id,
            String subscriptionPattern,
            int retentionDays,
            long intervalMs,
            String captureFrom,
            String createdAt,
            String createdBy,
            boolean enabled,

            @Schema(description = "How many messages this subscription is currently holding.")
            long messagesHeld,

            @Schema(description = "Approximate bytes of message payload held.")
            long bytesHeld,

            @Schema(description = "The oldest observation still held, or null when nothing is held.")
            String oldestObservedAt,

            @Schema(
                    description = "Why this subscription is recording nothing, or null when it is running."
                            + " An empty index and a subscription whose pattern matches no queue look"
                            + " identical from a query, so the reason is stated here.")
            String notCapturing,

            @Schema(
                    description = "True while a sampled subscription is still indexing the messages that were"
                            + " already on its queues when it started. That walk is spread over several polls to"
                            + " bound broker load, and until it finishes the index is not up to date.")
            boolean backlogInProgress,

            @Schema(
                    description = "SAMPLE or CAPTURE. A sampled subscription records what a poll saw;"
                            + " a captured one records what the address routed.")
            String mode,

            long ringSize,
            String filterString,
            long maxBytes,
            int maxRate,
            int bodyCapBytes,

            @Schema(
                    description = "Capture state per node. Empty for a sampled subscription. A node missing"
                            + " from this list is one capture has not reached, which is not the same as one"
                            + " that is capturing nothing.")
            List<CaptureNodeView> nodes) {}
}
