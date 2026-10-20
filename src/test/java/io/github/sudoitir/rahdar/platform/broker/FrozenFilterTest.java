package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FrozenFilterTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_790_000_000_000L);

    @Test
    void noFilterIsTheTimestampClauseAlone() {
        assertThat(FrozenFilter.compose(null, T0)).isEqualTo("AMQTimestamp <= 1790000000000");
        assertThat(FrozenFilter.compose("  ", T0)).isEqualTo("AMQTimestamp <= 1790000000000");
    }

    @Test
    void aFilterIsParenthesisedSoAnOrCannotEscapeTheClause() {
        assertThat(FrozenFilter.compose(" a = 1 OR b = 2 ", T0))
                .isEqualTo("(a = 1 OR b = 2) AND AMQTimestamp <= 1790000000000");
    }

    @Test
    void anAlreadyParenthesisedFilterStaysCorrect() {
        assertThat(FrozenFilter.compose("(a = 1) OR (b = 2)", T0))
                .isEqualTo("((a = 1) OR (b = 2)) AND AMQTimestamp <= 1790000000000");
    }

    @Test
    void aFilterThatClosesTheParenthesisItselfIsRefused() {
        assertThat(FrozenFilter.isCompleteExpression("a=1) OR (1=1")).isFalse();
        assertThat(FrozenFilter.isCompleteExpression("a=1)")).isFalse();
        assertThat(FrozenFilter.isCompleteExpression("(a=1")).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> FrozenFilter.compose("a=1) OR (1=1", T0));
    }

    @Test
    void aCompleteExpressionIsAccepted() {
        assertThat(FrozenFilter.isCompleteExpression("color = 'red' AND AMQPriority > 4"))
                .isTrue();
    }
}
