package io.github.sudoitir.rahdar.platform.governance;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One masking rule, or — when {@code exception} — a dismissed finding that stops a detector from
 * masking one class in one field (ADR-0075 D4).
 *
 * @param destinationPattern Artemis wildcard pattern, or null for every address
 * @param action overrides the class default; null means the default
 */
public record Rule(
        UUID id,
        String destinationPattern,
        RuleTarget target,
        String selector,
        DataClass dataClass,
        Action action,
        boolean builtin,
        boolean enabled,
        boolean exception) {

    /** The action this rule applies. An exception always leaves the value clear. */
    public Action effectiveAction() {
        if (exception) {
            return Action.CLEAR;
        }
        return action != null ? action : dataClass.defaultAction();
    }

    /** A rule compiled for matching; built once per policy snapshot. */
    record Compiled(Rule rule, Pattern address, Pattern selector) {

        static Compiled of(Rule rule) {
            return new Compiled(
                    rule,
                    rule.destinationPattern() == null ? null : DestinationPattern.compile(rule.destinationPattern()),
                    DestinationPattern.glob(rule.selector()));
        }

        boolean appliesTo(String address) {
            return this.address == null
                    || (address != null && this.address.matcher(address).matches());
        }

        boolean names(Location location, String name) {
            return rule.target().location() == location
                    && selector.matcher(name).matches();
        }
    }
}
