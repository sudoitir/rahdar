package io.github.sudoitir.rahdar.platform.governance;

import java.util.Comparator;
import java.util.List;

/** An immutable policy: every enabled rule, compiled, at one version. Replaced whole on change. */
record PolicySnapshot(int version, List<Rule.Compiled> rules) {

    static PolicySnapshot of(int version, List<Rule> rules) {
        return new PolicySnapshot(
                version,
                rules.stream()
                        .filter(Rule::enabled)
                        // An address-scoped rule is more specific than a global one, so it is consulted first.
                        .sorted(Comparator.comparing(r -> r.destinationPattern() == null))
                        .map(Rule.Compiled::of)
                        .toList());
    }

    /** The masking rule for a field, or null. Exceptions are not masking rules. */
    Rule rule(String address, Location location, String name) {
        for (Rule.Compiled compiled : rules) {
            if (!compiled.rule().exception() && compiled.appliesTo(address) && compiled.names(location, name)) {
                return compiled.rule();
            }
        }
        return null;
    }

    /** Whether a dismissed finding exempts this class in this field from detection. */
    boolean exempt(String address, Location location, String name, DataClass dataClass) {
        for (Rule.Compiled compiled : rules) {
            Rule rule = compiled.rule();
            if (rule.exception()
                    && rule.dataClass() == dataClass
                    && compiled.appliesTo(address)
                    && compiled.names(location, name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any masking rule names a header or property with this name on some address. */
    boolean classifiesAnywhere(Location location, String name) {
        return rules.stream().anyMatch(c -> !c.rule().exception() && c.names(location, name));
    }
}
