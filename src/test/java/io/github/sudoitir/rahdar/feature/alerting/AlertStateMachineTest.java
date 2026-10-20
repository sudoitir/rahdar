package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.Transition;
import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Table-driven over every (state, condition) pair the debounce commits to (design.md decision 4). */
class AlertStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static AlertStateEntity fresh() {
        return new AlertStateEntity(UUID.randomUUID(), "subscription:orders");
    }

    @Test
    void briefBreachDoesNotFire() {
        AlertStateEntity state = fresh();
        assertThat(AlertStateMachine.advance(state, true, 10.0, 30, T0)).isNull();
        assertThat(state.getState()).isEqualTo("PENDING");

        // Condition clears before the duration elapses.
        Transition t = AlertStateMachine.advance(state, false, null, 30, T0.plusSeconds(10));
        assertThat(t).isNull();
        assertThat(state.getState()).isEqualTo("OK");
        assertThat(state.getSince()).isNull();
    }

    @Test
    void sustainedBreachFiresOnceDurationElapses() {
        AlertStateEntity state = fresh();
        AlertStateMachine.advance(state, true, 10.0, 30, T0);
        assertThat(AlertStateMachine.advance(state, true, 10.0, 30, T0.plusSeconds(15)))
                .isNull();

        Transition t = AlertStateMachine.advance(state, true, 12.0, 30, T0.plusSeconds(31));
        assertThat(t).isEqualTo(new Transition("subscription:orders", TransitionKind.FIRED, 12.0));
        assertThat(state.getState()).isEqualTo("FIRING");
    }

    @Test
    void zeroDurationFiresImmediately() {
        AlertStateEntity state = fresh();
        Transition t = AlertStateMachine.advance(state, true, 5.0, 0, T0);
        assertThat(t).isEqualTo(new Transition("subscription:orders", TransitionKind.FIRED, 5.0));
        assertThat(state.getState()).isEqualTo("FIRING");
    }

    @Test
    void firingResolvesWhenConditionClears() {
        AlertStateEntity state = fresh();
        AlertStateMachine.advance(state, true, 5.0, 0, T0);

        Transition t = AlertStateMachine.advance(state, false, null, 0, T0.plusSeconds(5));
        assertThat(t).isEqualTo(new Transition("subscription:orders", TransitionKind.RESOLVED, 5.0));
        assertThat(state.getState()).isEqualTo("OK");
        assertThat(state.getSince()).isNull();
    }

    @Test
    void firingStaysFiringWhileConditionHolds() {
        AlertStateEntity state = fresh();
        AlertStateMachine.advance(state, true, 5.0, 0, T0);

        Transition t = AlertStateMachine.advance(state, true, 7.0, 0, T0.plusSeconds(5));
        assertThat(t).isNull();
        assertThat(state.getState()).isEqualTo("FIRING");
        assertThat(state.getLastValue()).isEqualTo(7.0);
    }

    @Test
    void okStaysOkWhenNeverActive() {
        AlertStateEntity state = fresh();
        assertThat(AlertStateMachine.advance(state, false, null, 30, T0)).isNull();
        assertThat(state.getState()).isEqualTo("OK");
    }

    @Test
    void pendingResetsElapsedTimeOnEachReEntry() {
        AlertStateEntity state = fresh();
        AlertStateMachine.advance(state, true, 1.0, 30, T0);
        AlertStateMachine.advance(state, false, null, 30, T0.plusSeconds(20));
        assertThat(state.getState()).isEqualTo("OK");

        // Re-entering PENDING starts the clock over, not from the earlier `since`.
        AlertStateMachine.advance(state, true, 1.0, 30, T0.plusSeconds(25));
        Transition t = AlertStateMachine.advance(state, true, 1.0, 30, T0.plusSeconds(50));
        assertThat(t).isNull();
        assertThat(state.getState()).isEqualTo("PENDING");
    }
}
