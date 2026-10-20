package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ArtemisSelectorsTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_790_000_000_000L);

    @Test
    void noFilterIsTheTimestampClauseAlone() {
        assertThat(ArtemisSelectors.frozen(null, T0)).isEqualTo("AMQTimestamp <= 1790000000000");
        assertThat(ArtemisSelectors.frozen("  ", T0)).isEqualTo("AMQTimestamp <= 1790000000000");
    }

    @Test
    void aFilterIsParenthesisedSoAnOrCannotEscapeTheClause() {
        assertThat(ArtemisSelectors.frozen(" a = 1 OR b = 2 ", T0))
                .isEqualTo("(a = 1 OR b = 2) AND AMQTimestamp <= 1790000000000");
    }

    @Test
    void anAlreadyParenthesisedFilterStaysCorrect() {
        assertThat(ArtemisSelectors.frozen("(a = 1) OR (b = 2)", T0))
                .isEqualTo("((a = 1) OR (b = 2)) AND AMQTimestamp <= 1790000000000");
    }

    @Test
    void aFilterThatClosesTheParenthesisItselfIsRefused() {
        assertThat(ArtemisSelectors.isCompleteExpression("a=1) OR (1=1")).isFalse();
        assertThat(ArtemisSelectors.isCompleteExpression("a=1)")).isFalse();
        assertThat(ArtemisSelectors.isCompleteExpression("(a=1")).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> ArtemisSelectors.frozen("a=1) OR (1=1", T0));
    }

    @Test
    void aCompleteExpressionIsAccepted() {
        assertThat(ArtemisSelectors.isCompleteExpression("color = 'red' AND AMQPriority > 4"))
                .isTrue();
    }
}
