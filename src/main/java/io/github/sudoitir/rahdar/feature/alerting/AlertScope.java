package io.github.sudoitir.rahdar.feature.alerting;

import java.util.Arrays;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code alert_rule.scope} ({@code {addressPattern, queuePattern, node, subjectPattern}}), where
 * {@code subjectPattern} narrows a plugin-metric rule to some of its subjects (ADR-0113).
 * A pattern with no {@code *} must match exactly; {@code *} is a wildcard
 * translated to a regex — enough for "starts with", "ends with", "contains"
 * without pulling in a glob library for three field-level filters.
 */
public record AlertScope(String addressPattern, String queuePattern, String node, String subjectPattern) {

    public static final AlertScope NONE = new AlertScope(null, null, null, null);

    public static AlertScope parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return NONE;
        }
        try {
            return mapper.readValue(json, AlertScope.class);
        } catch (RuntimeException _) {
            return NONE;
        }
    }

    /**
     * The queue names this scope can match, as a name pattern ({@code #} for every queue when none is set).
     * A {@code *} here is any run of characters, so a word that is only {@code *} becomes {@code #}, which
     * matches the same names and more. A {@code *} inside a word has no such spelling and is left as written,
     * so it matches only through a grant that reaches every queue.
     */
    public String queueNames() {
        if (queuePattern == null || queuePattern.isBlank()) {
            return "#";
        }
        return Arrays.stream(queuePattern.split("\\.", -1))
                .map(word -> "*".equals(word) ? "#" : word)
                .collect(Collectors.joining("."));
    }

    public boolean matchesAddress(String address) {
        return matches(addressPattern, address);
    }

    public boolean matchesQueue(String queueName) {
        return matches(queuePattern, queueName);
    }

    public boolean matchesSubject(String subject) {
        return matches(subjectPattern, subject);
    }

    public boolean matchesNode(String nativeNodeId) {
        return node == null || node.isBlank() || node.equals(nativeNodeId);
    }

    private static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
        return value != null && value.matches(regex);
    }
}
