package io.github.sudoitir.rahdar.platform.governance;

import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import java.util.List;
import java.util.Map;

/**
 * A message as the caller may see it or as Rahdar may store it (ADR-0075 D1). Only this type is
 * accepted where content is serialised or persisted.
 *
 * @param headers governed headers; a masked value is its marker
 * @param properties governed properties; a masked value of any type becomes its marker string
 * @param body governed body; null when there was none or all of it was withheld
 * @param redactions every sensitive value found, masked or served clear by grant
 * @param withheld content not shown, with the reason
 * @param sealable originals of sealable masked values, keyed by {@link #key}; empty unless sealing was asked for
 * @param policyVersion the policy this message was governed under
 */
public record GovernedMessage(
        Map<String, String> headers,
        Map<String, Object> properties,
        String body,
        List<Redaction> redactions,
        List<Withheld> withheld,
        Map<String, String> sealable,
        int policyVersion,
        DecodedRecordView decoded,
        DecodedRecordView decodedKey) {

    public GovernedMessage(
            Map<String, String> headers,
            Map<String, Object> properties,
            String body,
            List<Redaction> redactions,
            List<Withheld> withheld,
            Map<String, String> sealable,
            int policyVersion) {
        this(headers, properties, body, redactions, withheld, sealable, policyVersion, null, null);
    }

    /** One sensitive value: where it is, what it is, what was done, and whether it was served clear by grant. */
    public record Redaction(Location location, String path, DataClass dataClass, Action action, boolean clear) {}

    /** Content that was not shown, why, and the setting that changes it where one does. */
    public record Withheld(Location location, String reason, String settingKey) {}

    /** The key under which an original is sealed. The whole body is sealed under {@code BODY:}. */
    public static String key(Location location, String path) {
        return location.name() + ":" + (path == null ? "" : path);
    }

    /** Values served clear by grant, for the clear-view audit. */
    public long clearCount() {
        return redactions.stream().filter(Redaction::clear).count();
    }
}
