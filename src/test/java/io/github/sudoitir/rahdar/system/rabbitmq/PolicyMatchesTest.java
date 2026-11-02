package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.system.rabbitmq.PolicyMatches.Candidate;
import io.github.sudoitir.rahdar.system.rabbitmq.PolicyMatches.Match;
import io.github.sudoitir.rahdar.system.rabbitmq.PolicyMatches.Policy;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which queues and exchanges a policy reaches, as Rahdar matches it. */
class PolicyMatchesTest {

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate(PolicyMatches.CLASSIC_QUEUE, "orders.eu"),
            new Candidate(PolicyMatches.QUORUM_QUEUE, "orders.us"),
            new Candidate(PolicyMatches.STREAM, "orders.log"),
            new Candidate(PolicyMatches.EXCHANGE, "orders"),
            new Candidate(PolicyMatches.CLASSIC_QUEUE, "billing"));

    @Test
    void aPatternIsFoundAnywhereInTheName() {
        List<Match> matches = PolicyMatches.of(new Policy("p", "orders", "all", 0), CANDIDATES, List.of());

        assertThat(matches).extracting(Match::name).containsExactly("orders.eu", "orders", "orders.us", "orders.log");
    }

    @Test
    void anAnchoredPatternMatchesTheWholeName() {
        assertThat(PolicyMatches.of(new Policy("p", "^orders$", "all", 0), CANDIDATES, List.of()))
                .extracting(Match::name)
                .containsExactly("orders");
    }

    @Test
    void applyToNarrowsTheKinds() {
        assertThat(names("orders", "exchanges")).containsExactly("orders");
        assertThat(names("orders", "queues")).containsExactlyInAnyOrder("orders.eu", "orders.us", "orders.log");
        assertThat(names("orders", "classic_queues")).containsExactly("orders.eu");
        assertThat(names("orders", "quorum_queues")).containsExactly("orders.us");
        assertThat(names("orders", "streams")).containsExactly("orders.log");
        assertThat(names("orders", null)).hasSize(4);
    }

    @Test
    void aPolicyOfHigherPriorityShadowsThisOne() {
        Policy mine = new Policy("mine", "orders", "queues", 1);
        List<Policy> others = List.of(
                new Policy("louder", "^orders\\.eu$", "all", 5),
                new Policy("loudest", "orders\\.eu", "classic_queues", 9),
                new Policy("quieter", "orders", "all", 0),
                new Policy("mine", "billing", "all", 99));

        List<Match> matches = PolicyMatches.of(mine, CANDIDATES, others);

        assertThat(matches)
                .filteredOn(m -> m.name().equals("orders.eu"))
                .extracting(Match::shadowedBy)
                .containsExactly("loudest");
        assertThat(matches)
                .filteredOn(m -> !m.name().equals("orders.eu"))
                .extracting(Match::shadowedBy)
                .containsOnlyNulls();
    }

    @Test
    void aRivalWithAPatternJavaCannotReadDoesNotCompete() {
        List<Match> matches = PolicyMatches.of(
                new Policy("mine", "orders", "all", 0),
                CANDIDATES,
                List.of(new Policy("odd", "(", "all", 9), new Policy("odder", "orders", "nonsense", 9)));

        assertThat(matches).extracting(Match::shadowedBy).containsOnlyNulls();
    }

    @Test
    void aPatternThatIsNotARegularExpressionIsRefused() {
        var policy = new Policy("p", "(", "all", 0);
        var none = List.<Policy>of();
        assertThatThrownBy(() -> PolicyMatches.of(policy, CANDIDATES, none))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular expression");
    }

    @Test
    void anApplyToRabbitMqDoesNotKnowIsRefused() {
        assertThatThrownBy(() -> PolicyMatches.covers("everything", PolicyMatches.EXCHANGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("everything");
    }

    private static List<String> names(String pattern, String applyTo) {
        return PolicyMatches.of(new Policy("p", pattern, applyTo, 0), CANDIDATES, List.of()).stream()
                .map(Match::name)
                .toList();
    }

    /** A pattern a Java engine backtracks on exponentially: its back reference defeats loop memoisation. */
    private static final String EVIL_PATTERN = "^(\\w|\\w\\w)*\\1$";

    /** Many word characters and one that fails the end, which takes {@link #EVIL_PATTERN} minutes to refuse. */
    private static final String EVIL_NAME = "a".repeat(40) + "!";

    @Test
    void aPatternThatBacktracksWithoutEndIsRefusedInsteadOfHoldingTheRequest() {
        Policy evil = new Policy("evil", EVIL_PATTERN, "all", 0);
        List<Candidate> names = List.of(new Candidate(PolicyMatches.CLASSIC_QUEUE, EVIL_NAME));
        long started = System.nanoTime();

        assertThatThrownBy(() -> PolicyMatches.of(evil, names, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes too long");
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
    }

    @Test
    void aRivalThatBacktracksWithoutEndDoesNotCompeteAndDoesNotSpendThePreview() {
        Policy mine = new Policy("mine", "^a", "all", 0);
        Policy evil = new Policy("evil", EVIL_PATTERN, "all", 10);
        List<Candidate> names = List.of(
                new Candidate(PolicyMatches.CLASSIC_QUEUE, EVIL_NAME),
                new Candidate(PolicyMatches.CLASSIC_QUEUE, EVIL_NAME + "b"));

        List<Match> matches = PolicyMatches.of(mine, names, List.of(evil));

        assertThat(matches)
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.shadowedBy()).isNull());
    }
}
