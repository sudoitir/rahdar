package io.github.sudoitir.rahdar.system.rabbitmq;

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

    private PolicyMatches() {}

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
        return candidates.stream()
                .filter(c -> covers(policy.applyTo(), c.kind())
                        && own.matcher(c.name()).find())
                .map(c -> new Match(c.kind(), c.name(), shadow(policy, c, rivals)))
                .sorted(Comparator.comparing(Match::kind).thenComparing(Match::name))
                .toList();
    }

    private static String shadow(Policy policy, Candidate candidate, Map<Policy, Optional<Pattern>> rivals) {
        return rivals.entrySet().stream()
                .filter(e -> e.getKey().priority() > policy.priority())
                .filter(e -> applies(e.getKey(), e.getValue(), candidate))
                .max(Comparator.comparingLong((Map.Entry<Policy, Optional<Pattern>> e) ->
                                e.getKey().priority())
                        .thenComparing(e -> e.getKey().name()))
                .map(e -> e.getKey().name())
                .orElse(null);
    }

    private static boolean applies(Policy policy, Optional<Pattern> pattern, Candidate candidate) {
        return pattern.isPresent()
                && coversLeniently(policy.applyTo(), candidate.kind())
                && pattern.get().matcher(candidate.name()).find();
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
