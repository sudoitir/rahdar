package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateEntity;
import java.time.Duration;
import java.time.Instant;

/**
 * The OK → PENDING → FIRING → OK debounce (design.md decision 4). Pure: no
 * Spring, no I/O — mutates the given {@link AlertStateEntity} in place and
 * returns a {@link Transition} only when one that matters to an operator
 * occurred (a fire or a resolve), never for PENDING bookkeeping. {@code since}
 * lives on the entity, so this survives a restart with no in-memory state. A firing alert holds its resolution for
 * the rule's resolve time ({@link #STATE_RESOLVE_SECONDS} for the state rules a flapping broker drives).
 */
public final class AlertStateMachine {

    private static final String FIRING = "FIRING";

    /** How long a state rule's condition must stay false before its alert resolves. */
    public static final int STATE_RESOLVE_SECONDS = 120;

    private AlertStateMachine() {}

    public enum TransitionKind {
        FIRED,
        RESOLVED
    }

    public record Transition(String subjectKey, TransitionKind kind, Double value) {}

    /** Advances {@code state} for one evaluation tick. {@code value} is null when {@code active} is false. */
    public static Transition advance(
            AlertStateEntity state, boolean active, Double value, int forSeconds, int resolveSeconds, Instant now) {
        return switch (state.getState()) {
            case "OK" -> fromOk(state, active, value, forSeconds, now);
            case "PENDING" -> fromPending(state, active, value, forSeconds, now);
            case FIRING -> fromFiring(state, active, value, resolveSeconds, now);
            default -> throw new IllegalStateException("unknown alert_state: " + state.getState());
        };
    }

    private static Transition fromOk(
            AlertStateEntity state, boolean active, Double value, int forSeconds, Instant now) {
        if (!active) {
            return null;
        }
        state.setState("PENDING");
        state.setSince(now);
        state.setLastValue(value);
        if (forSeconds <= 0) {
            state.setState(FIRING);
            state.setSince(null);
            return new Transition(state.getSubjectKey(), TransitionKind.FIRED, value);
        }
        return null;
    }

    private static Transition fromPending(
            AlertStateEntity state, boolean active, Double value, int forSeconds, Instant now) {
        if (!active) {
            state.setState("OK");
            state.setSince(null);
            return null;
        }
        state.setLastValue(value);
        Instant since = state.getSince() != null ? state.getSince() : now;
        state.setSince(since);
        if (Duration.between(since, now).getSeconds() >= forSeconds) {
            state.setState(FIRING);
            state.setSince(null);
            return new Transition(state.getSubjectKey(), TransitionKind.FIRED, value);
        }
        return null;
    }

    /**
     * A firing alert resolves once its condition has been false for {@code resolveSeconds}: {@code since} is then the
     * moment it first read false, and null while it holds. A source that flaps inside that time is one alert, not a
     * firing and a resolution for every flap.
     */
    private static Transition fromFiring(
            AlertStateEntity state, boolean active, Double value, int resolveSeconds, Instant now) {
        if (active) {
            state.setLastValue(value);
            state.setSince(null);
            return null;
        }
        if (resolveSeconds > 0) {
            Instant clearSince = state.getSince() != null ? state.getSince() : now;
            state.setSince(clearSince);
            if (Duration.between(clearSince, now).getSeconds() < resolveSeconds) {
                return null;
            }
        }
        Double lastValue = state.getLastValue();
        state.setState("OK");
        state.setSince(null);
        return new Transition(state.getSubjectKey(), TransitionKind.RESOLVED, lastValue);
    }
}
