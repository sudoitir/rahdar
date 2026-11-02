package io.github.sudoitir.rahdar.system.rabbitmq;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which queues and exchanges a policy applies to, as Rahdar matches it: the policy's pattern is a Java regular expression
 * found anywhere in the name, over the kinds its {@code apply-to} names. A match that another policy of higher
 * priority also covers is shadowed by it. RabbitMQ's own matching is the broker's; this is the preview an operator reads.
 *
 * <p>A pattern comes from whoever may write policies on the broker, so matching it is bounded: one preview spends at
 * most {@link #BUDGET} on its patterns, and a pattern that backtracks past that is not run to the end.
 */
final class PolicyMatches {

    static final String CLASSIC_QUEUE = "classic_queue";
    static final String QUORUM_QUEUE = "quorum_queue";
    static final String STREAM = "stream";
    static final String EXCHANGE = "exchange";

    /** A queue or exchange a policy may apply to. */
    record Candidate(String kind, String name) {}

    /** A policy as it matters here. */
    record Policy(String name, String pattern, String applyTo, long priority) {}

    /** A candidate the policy matches, and the policy of higher priority that wins it, if any. */
    record Match(String kind, String name, String shadowedBy) {}

    /** The time one preview may spend matching the policy's own pattern against names. */
    static final Duration BUDGET = Duration.ofMillis(250);

    /** The time one rival policy's pattern may spend on one name before that rival is left out. */
    static final Duration RIVAL_BUDGET = Duration.ofMillis(25);

    private PolicyMatches() {}

    /** The budget ran out while a pattern was being matched. */
    static final class TooSlow extends RuntimeException {
        TooSlow() {
            super("A policy's pattern took too long to match.", null, false, false);
        }
    }

    /** A name read by the regular-expression engine, which stops the match once the preview's budget is spent. */
    private record Bounded(CharSequence text, long deadline) implements CharSequence {

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new TooSlow();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Bounded(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** What {@code apply-to} covers; a value RabbitMQ does not know is refused. */
    static boolean covers(String applyTo, String kind) {
        return switch (applyTo == null ? "all" : applyTo) {
            case "all" -> true;
            case "exchanges" -> EXCHANGE.equals(kind);
            case "queues" -> CLASSIC_QUEUE.equals(kind) || QUORUM_QUEUE.equals(kind) || STREAM.equals(kind);
            case "classic_queues" -> CLASSIC_QUEUE.equals(kind);
            case "quorum_queues" -> QUORUM_QUEUE.equals(kind);
            case "streams" -> STREAM.equals(kind);
            default ->
                throw new IllegalArgumentException("A policy applies to all, queues, classic_queues, quorum_queues,"
                        + " streams or exchanges, not '" + applyTo + "'.");
        };
    }

    static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "The pattern is not a regular expression: " + e.getDescription() + ".", e);
        }
    }

    /**
     * The candidates the policy matches, in name order, each with the policy of higher priority that wins it. The
     * others are the policies of the same virtual host; one of the same name is the policy being replaced and does
     * not compete.
     */
    static List<Match> of(Policy policy, List<Candidate> candidates, List<Policy> others) {
        Pattern own = compile(policy.pattern());
        Map<Policy, Optional<Pattern>> rivals = new HashMap<>();
        others.stream().filter(o -> !o.name().equals(policy.name())).forEach(o -> rivals.put(o, lenient(o.pattern())));
        long deadline = System.nanoTime() + BUDGET.toNanos();
        try {
            return candidates.stream()
                    .filter(c -> covers(policy.applyTo(), c.kind()) && found(own, c.name(), deadline))
                    .map(c -> new Match(c.kind(), c.name(), shadow(policy, c, rivals)))
                    .sorted(Comparator.comparing(Match::kind).thenComparing(Match::name))
                    .toList();
        } catch (TooSlow e) {
            throw new IllegalArgumentException(
                    "The pattern takes too long to match the cluster's names, so its matches cannot be previewed;"
                            + " a pattern that backtracks this much also slows the broker.",
                    e);
        }
    }

    private static boolean found(Pattern pattern, String name, long deadline) {
        return pattern.matcher(new Bounded(name, deadline)).find();
    }

    private static String shadow(Policy policy, Candidate candidate, Map<Policy, Optional<Pattern>> rivals) {
        return List.copyOf(rivals.entrySet()).stream()
                .filter(e -> e.getKey().priority() > policy.priority())
                .filter(e -> applies(e.getKey(), e.getValue(), candidate, rivals))
                .max(Comparator.comparingLong((Map.Entry<Policy, Optional<Pattern>> e) ->
                                e.getKey().priority())
                        .thenComparing(e -> e.getKey().name()))
                .map(e -> e.getKey().name())
                .orElse(null);
    }

    /**
     * Whether a rival policy covers the candidate. A rival whose pattern runs out of time does not compete, here or
     * for any later name, so a pattern that backtracks costs one short budget at most.
     */
    private static boolean applies(
            Policy policy, Optional<Pattern> pattern, Candidate candidate, Map<Policy, Optional<Pattern>> rivals) {
        if (pattern.isEmpty() || !coversLeniently(policy.applyTo(), candidate.kind())) {
            return false;
        }
        try {
            return found(pattern.get(), candidate.name(), System.nanoTime() + RIVAL_BUDGET.toNanos());
        } catch (TooSlow _) {
            rivals.put(policy, Optional.empty());
            return false;
        }
    }

    private static boolean coversLeniently(String applyTo, String kind) {
        try {
            return covers(applyTo, kind);
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /** A policy of the cluster whose pattern Java cannot read does not compete: it is left out, not an error. */
    private static Optional<Pattern> lenient(String pattern) {
        try {
            return Optional.of(Pattern.compile(pattern));
        } catch (PatternSyntaxException _) {
            return Optional.empty();
        }
    }
}
