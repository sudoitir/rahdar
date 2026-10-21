package io.github.sudoitir.rahdar.feature.sql;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed set of columns the SQL Console dialect understands (ADR-0058 D1/D2).
 * This catalogue <em>is</em> the whitelist: a column that is not here cannot be
 * queried, cannot reach a selector, and cannot reach a JDBC statement.
 *
 * <p>Each column declares where its predicate can be evaluated, which is the
 * decision the whole feature's cost model rests on:
 *
 * <ul>
 *   <li>{@link Evaluation#TARGET} — it describes <em>which</em> queue or node, so it
 *       filters the target list during planning and costs nothing at all.
 *   <li>{@link Evaluation#PUSHDOWN} — the broker's JMS selector can evaluate it, so
 *       the broker filters and returns fewer messages. Also free.
 *   <li>{@link Evaluation#SCAN} — nothing but Rahdar can evaluate it, so every
 *       message the broker returned has to be examined. This is the only class that
 *       costs anything, and the operator is told before the query runs.
 * </ul>
 */
public final class ColumnCatalogue {

    private ColumnCatalogue() {}

    public enum Type {
        STRING,
        NUMBER,
        BOOLEAN,
        TIMESTAMP
    }

    public enum Evaluation {
        TARGET,
        PUSHDOWN,
        SCAN
    }

    /**
     * A catalogue column. {@code selectorId} is the identifier Artemis' selector
     * parser knows it by, and is null for everything not pushdown-eligible.
     *
     * <p>{@code indexOnly} columns describe an observation rather than a message, so
     * they have no meaning against a live broker and are rejected there rather than
     * silently returning null.
     */
    public enum Column {
        MESSAGE_ID(
                "messageId", Type.STRING, Evaluation.SCAN, null, false, "The broker-assigned message id. Node-local."),
        SUBSCRIPTION(
                "subscription", Type.STRING, Evaluation.TARGET, null, false, "The subscription the message is on."),
        DESTINATION(
                "destination",
                Type.STRING,
                Evaluation.TARGET,
                null,
                false,
                "The destination the subscription is bound to."),
        NODE("node", Type.STRING, Evaluation.TARGET, null, false, "The broker node holding the message."),
        PRIORITY("priority", Type.NUMBER, Evaluation.PUSHDOWN, "JMSPriority", false, "JMS priority, 0-9."),
        DURABLE(
                "durable",
                Type.BOOLEAN,
                Evaluation.PUSHDOWN,
                "AMQDurable",
                false,
                "Whether the message is persistent."),
        TIMESTAMP(
                "timestamp",
                Type.TIMESTAMP,
                Evaluation.PUSHDOWN,
                "AMQTimestamp",
                false,
                "When the broker took the message."),
        EXPIRATION(
                "expiration",
                Type.TIMESTAMP,
                Evaluation.PUSHDOWN,
                "JMSExpiration",
                false,
                "When the message expires; 0 means never."),
        SIZE("size", Type.NUMBER, Evaluation.PUSHDOWN, "AMQSize", false, "Encoded size in bytes."),
        JMS_TYPE("jmsType", Type.STRING, Evaluation.PUSHDOWN, "JMSType", false, "The JMSType header."),
        CORRELATION_ID(
                "correlationId", Type.STRING, Evaluation.PUSHDOWN, "JMSCorrelationID", false, "The correlation id."),
        GROUP_ID("groupId", Type.STRING, Evaluation.PUSHDOWN, "AMQGroupID", false, "The message group id."),
        USER_ID("userId", Type.STRING, Evaluation.PUSHDOWN, "AMQUserID", false, "The user id the producer set."),
        MESSAGE_TYPE("messageType", Type.NUMBER, Evaluation.SCAN, null, false, "The numeric core message type."),
        REPLY_TO(
                "replyTo",
                Type.STRING,
                Evaluation.SCAN,
                null,
                false,
                "The reply-to destination, when the message has one."),
        BODY(
                "body",
                Type.STRING,
                Evaluation.SCAN,
                null,
                false,
                "The message body as text. Every predicate over it is a scan."),
        OBSERVED_AT(
                "observedAt",
                Type.TIMESTAMP,
                Evaluation.SCAN,
                null,
                true,
                "When the index first observed the message."),
        LAST_SEEN_AT(
                "lastSeenAt",
                Type.TIMESTAMP,
                Evaluation.SCAN,
                null,
                true,
                "When the index last still saw it on its subscription."),
        ORIGIN(
                "origin",
                Type.STRING,
                Evaluation.SCAN,
                null,
                true,
                "SAMPLED or CAPTURED. A sampled row says a poll saw the message; a captured one says"
                        + " the destination routed it."),
        ORIG_DESTINATION(
                "orig_destination",
                Type.STRING,
                Evaluation.SCAN,
                null,
                true,
                "The destination the broker said a captured copy came from, when it said so."),
        SOURCE_MESSAGE_ID(
                "sourceMessageId",
                Type.STRING,
                Evaluation.SCAN,
                null,
                true,
                "A captured message's id on its source subscription. Null when the broker did not copy it.");

        private final String sqlName;
        private final Type type;
        private final Evaluation evaluation;
        private final String selectorId;
        private final boolean indexOnly;
        private final String description;

        Column(
                String sqlName,
                Type type,
                Evaluation evaluation,
                String selectorId,
                boolean indexOnly,
                String description) {
            this.sqlName = sqlName;
            this.type = type;
            this.evaluation = evaluation;
            this.selectorId = selectorId;
            this.indexOnly = indexOnly;
            this.description = description;
        }

        public String sqlName() {
            return sqlName;
        }

        public Type type() {
            return type;
        }

        public Evaluation evaluation() {
            return evaluation;
        }

        public String selectorId() {
            return selectorId;
        }

        public boolean indexOnly() {
            return indexOnly;
        }

        public String description() {
            return description;
        }
    }

    /** Functions the dialect accepts. Anything else is rejected by name (D2). */
    public static final List<String> ALLOWED_FUNCTIONS = List.of("now", "lower", "upper");

    /** The prefix that addresses an application property: {@code props.tenant}. */
    public static final String PROPERTY_PREFIX = "props";

    private static final Map<String, Column> BY_NAME;

    static {
        Map<String, Column> m = new LinkedHashMap<>();
        for (Column c : Column.values()) {
            m.put(c.sqlName().toLowerCase(Locale.ROOT), c);
        }
        BY_NAME = Map.copyOf(m);
    }

    public static Optional<Column> find(String name) {
        return Optional.ofNullable(BY_NAME.get(name.toLowerCase(Locale.ROOT)));
    }

    public static List<Column> all() {
        return List.of(Column.values());
    }

    /**
     * The closest catalogue column to an unknown name, when one is close enough to
     * be worth offering. Edit distance over the lower-cased names, capped at a third
     * of the length so an unrelated word does not produce a confident wrong guess.
     */
    public static Optional<Column> suggest(String unknown) {
        String probe = unknown.toLowerCase(Locale.ROOT);
        int ceiling = Math.max(1, probe.length() / 3);
        return Arrays.stream(Column.values())
                .map(c -> Map.entry(c, distance(probe, c.sqlName().toLowerCase(Locale.ROOT))))
                .filter(e -> e.getValue() <= ceiling)
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
