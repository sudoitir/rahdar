package io.github.sudoitir.rahdar.platform.governance;

import io.github.sudoitir.rahdar.platform.governance.GovernedMessage.Redaction;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage.Withheld;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * One policy snapshot applied to messages (ADR-0075 D2). Pure: no Spring, no database, so every
 * rule of the policy is unit-testable. A new engine is built per call from the current snapshot.
 */
final class PolicyEngine {

    /** Where detections in uncovered fields go. */
    interface FindingSink {
        void recordFinding(String address, Location location, String path, DataClass dataClass);
    }

    static final String BINARY_WITHHELD = "Binary body cannot be classified, so it is withheld.";

    private final PolicySnapshot policy;
    private final int scanLimit;
    private final ObjectMapper mapper;
    private final FindingSink findings;

    PolicyEngine(PolicySnapshot policy, int scanLimit, ObjectMapper mapper, FindingSink findings) {
        this.policy = policy;
        this.scanLimit = scanLimit;
        this.mapper = mapper;
        this.findings = findings;
    }

    /**
     * Govern one message. With {@code seal}, originals of masked sealable values are collected — taken
     * from a clear-mode pass, so a sealed original never carries a credential or unscanned bytes.
     */
    GovernedMessage govern(GovernContext context, MessageContent content, boolean seal) {
        GovernedMessage governed = new Run(context, true, false).message(content);
        if (!seal) {
            return governed;
        }
        GovernedMessage clearCopy = new Run(
                        new GovernContext(context.clusterId(), context.destination(), true), false, true)
                .message(content);
        Map<String, String> sealable = new LinkedHashMap<>();
        for (Redaction r : governed.redactions()) {
            if (r.clear() || !r.dataClass().sealable()) {
                continue;
            }
            switch (r.location()) {
                case HEADER ->
                    sealable.put(
                            GovernedMessage.key(Location.HEADER, r.path()),
                            clearCopy.headers().get(r.path()));
                case PROPERTY ->
                    sealable.put(
                            GovernedMessage.key(Location.PROPERTY, r.path()),
                            String.valueOf(clearCopy.properties().get(r.path())));
                case BODY -> sealable.put(GovernedMessage.key(Location.BODY, null), clearCopy.body());
            }
        }
        if (content.base64() && content.body() != null && !context.clearAccess()) {
            // A withheld binary body is sealed whole: clear readers of the stored copy still get it.
            sealable.put(GovernedMessage.key(Location.BODY, null), content.body());
        }
        return new GovernedMessage(
                governed.headers(),
                governed.properties(),
                governed.body(),
                governed.redactions(),
                governed.withheld(),
                Collections.unmodifiableMap(sealable),
                governed.policyVersion());
    }

    /** Detectors only, for free text such as audit parameters: no rules, no findings, never clear. */
    String governText(String text) {
        if (text == null) {
            return null;
        }
        return new Run(new GovernContext(null, null, false), false, false)
                .spans(Location.BODY, "", text, Detectors.scan(text));
    }

    /** One pass over one message, collecting what it redacted and withheld. */
    private final class Run {

        private final GovernContext context;
        private final boolean recordFindings;
        /** A sealing copy: nothing past the scan limit, since it was never checked for credentials. */
        private final boolean sealCopy;

        private final List<Redaction> redactions = new ArrayList<>();
        private final List<Withheld> withheld = new ArrayList<>();

        Run(GovernContext context, boolean recordFindings, boolean sealCopy) {
            this.context = context;
            this.recordFindings = recordFindings;
            this.sealCopy = sealCopy;
        }

        GovernedMessage message(MessageContent content) {
            Map<String, String> headers = new LinkedHashMap<>();
            content.headers().forEach((name, value) -> {
                if (value != null) {
                    headers.put(name, field(Location.HEADER, name, value));
                }
            });
            Map<String, Object> properties = new LinkedHashMap<>();
            content.properties().forEach((name, value) -> properties.put(name, typed(Location.PROPERTY, name, value)));
            String body = body(content);
            return new GovernedMessage(
                    Collections.unmodifiableMap(headers),
                    Collections.unmodifiableMap(properties),
                    body,
                    List.copyOf(redactions),
                    List.copyOf(withheld),
                    Map.of(),
                    policy.version());
        }

        /** A typed value: governed as text, and kept with its type when nothing about it changed. */
        private Object typed(Location location, String name, Object value) {
            if (value == null || value instanceof Boolean || value instanceof Double || value instanceof Float) {
                return value;
            }
            String text = String.valueOf(value);
            String governed = field(location, name, text);
            return governed.equals(text) ? value : governed;
        }

        String field(Location location, String name, String value) {
            Rule rule = policy.rule(context.destination(), location, name);
            if (rule != null) {
                return whole(location, name, value, rule.dataClass(), rule.effectiveAction());
            }
            List<Detectors.Hit> hits = Detectors.scan(value).stream()
                    .filter(h -> !policy.exempt(context.destination(), location, name, h.dataClass()))
                    .toList();
            if (hits.isEmpty()) {
                return value;
            }
            if (recordFindings) {
                hits.stream()
                        .map(Detectors.Hit::dataClass)
                        .distinct()
                        .forEach(c -> findings.recordFinding(context.destination(), location, name, c));
            }
            return spans(location, name, value, hits);
        }

        private String whole(Location location, String name, String value, DataClass dataClass, Action action) {
            if (action == Action.CLEAR) {
                return value;
            }
            if (context.clearAccess() && dataClass.sealable()) {
                redactions.add(new Redaction(location, name, dataClass, action, true));
                return value;
            }
            redactions.add(new Redaction(location, name, dataClass, action, false));
            return mask(dataClass, action, value);
        }

        String spans(Location location, String name, String value, List<Detectors.Hit> hits) {
            StringBuilder out = new StringBuilder(value.length());
            int at = 0;
            for (Detectors.Hit hit : hits) {
                out.append(value, at, hit.start());
                String span = value.substring(hit.start(), hit.end());
                DataClass dataClass = hit.dataClass();
                Action action = dataClass.defaultAction();
                boolean clear = context.clearAccess() && dataClass.sealable();
                redactions.add(new Redaction(location, name, dataClass, action, clear));
                out.append(clear ? span : mask(dataClass, action, span));
                at = hit.end();
            }
            return out.append(value.substring(at)).toString();
        }

        String body(MessageContent content) {
            String body = content.body();
            if (body == null) {
                return null;
            }
            if (content.base64()) {
                if (context.clearAccess()) {
                    return body;
                }
                withheld.add(new Withheld(Location.BODY, BINARY_WITHHELD, null));
                return null;
            }
            String scanned = cutUtf8(body, scanLimit);
            boolean cut = scanned.length() < body.length();
            String governed = !cut && looksJson(content.contentType(), scanned) ? json(scanned) : null;
            if (governed == null) {
                governed = field(Location.BODY, "", scanned);
            }
            if (cut) {
                if (context.clearAccess() && !sealCopy) {
                    return governed + body.substring(scanned.length());
                }
                if (!context.clearAccess()) {
                    withheld.add(new Withheld(
                            Location.BODY,
                            "Bytes after the first " + scanLimit + " were not scanned, so they are withheld.",
                            GovernanceSettings.SCAN_LIMIT));
                }
            }
            return governed;
        }

        /** Null when the text is not JSON after all, so it is governed as text instead. */
        private String json(String text) {
            Object tree;
            try {
                tree = mapper.readValue(text, Object.class);
            } catch (JacksonException _) {
                return null;
            }
            return mapper.writeValueAsString(walk(tree, ""));
        }

        private Object walk(Object node, String path) {
            if (node instanceof Map<?, ?> object) {
                Map<String, Object> out = new LinkedHashMap<>();
                object.forEach((key, value) -> out.put(
                        String.valueOf(key), walk(value, path.isEmpty() ? String.valueOf(key) : path + "." + key)));
                return out;
            }
            if (node instanceof List<?> array) {
                List<Object> out = new ArrayList<>(array.size());
                array.forEach(value -> out.add(walk(value, path + "[*]")));
                return out;
            }
            if (node instanceof String text) {
                return field(Location.BODY, path, text);
            }
            if (node instanceof Long || node instanceof Integer || node instanceof BigInteger) {
                // A card number is often a JSON number; integral leaves are checked, fractions are not.
                return typed(Location.BODY, path, node);
            }
            return node;
        }
    }

    /** The marker that replaces a value. Idempotent: masking a marker yields the same marker. */
    static String mask(DataClass dataClass, Action action, String value) {
        return switch (action) {
            case CLEAR -> value;
            case DROP -> "[dropped " + dataClass.label() + "]";
            case REDACT -> "[redacted " + dataClass.label() + "]";
            case PARTIAL -> {
                String alphanumeric = value.replaceAll("[^A-Za-z0-9]", "");
                yield alphanumeric.length() < 8
                        ? "[redacted " + dataClass.label() + "]"
                        : "[" + dataClass.label() + " ending " + alphanumeric.substring(alphanumeric.length() - 4)
                                + "]";
            }
        };
    }

    static boolean looksJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            return true;
        }
        String trimmed = body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /** The longest prefix whose UTF-8 encoding fits in {@code limit} bytes, never splitting a code point. */
    static String cutUtf8(String text, int limit) {
        if (text.length() * 4L <= limit) {
            return text;
        }
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int size = utf8Size(codePoint);
            if (bytes + size > limit) {
                return text.substring(0, i);
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return text;
    }

    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
