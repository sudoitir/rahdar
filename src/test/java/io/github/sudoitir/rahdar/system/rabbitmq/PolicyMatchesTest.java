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
}
