package io.github.sudoitir.rahdar.platform.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage.Redaction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PolicyEngineTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final List<String> findings = new ArrayList<>();

    private static Rule rule(String address, RuleTarget target, String selector, DataClass dataClass) {
        return new Rule(UUID.randomUUID(), address, target, selector, dataClass, null, false, true, false);
    }

    private static final List<Rule> BUILT_INS = List.of(
            new Rule(
                    UUID.randomUUID(),
                    null,
                    RuleTarget.PROPERTY,
                    "authorization",
                    DataClass.CREDENTIAL,
                    null,
                    true,
                    true,
                    false),
            new Rule(
                    UUID.randomUUID(),
                    null,
                    RuleTarget.PROPERTY,
                    "*token*",
                    DataClass.CREDENTIAL,
                    null,
                    true,
                    true,
                    false));

    private PolicyEngine engine(List<Rule> extra, int scanLimit) {
        List<Rule> rules = new ArrayList<>(BUILT_INS);
        rules.addAll(extra);
        return new PolicyEngine(
                PolicySnapshot.of(7, rules),
                scanLimit,
                JsonMapper.builder().build(),
                (address, location, path, c) -> findings.add(address + "|" + location + "|" + path + "|" + c));
    }

    private static GovernContext masked(String address) {
        return new GovernContext(CLUSTER, address, false);
    }

    private static GovernContext clear(String address) {
        return new GovernContext(CLUSTER, address, true);
    }

    private static MessageContent props(Map<String, Object> properties, String body) {
        return new MessageContent(Map.of(), properties, body, false, null);
    }

    @Test
    void aBodyRuleMasksTheSameFieldOfADecodedRecord() {
        DecodedRecordView decoded = new DecodedRecordView(
                "AVRO",
                1,
                "orders-value",
                Map.of("email", "ada@example.com"),
                null,
                "{\"email\":\"ada@example.com\"}",
                Map.of("email", "ada@example.com"));
        MessageContent content = new MessageContent(Map.of(), Map.of(), "{}", false, "application/json", decoded);

        GovernedMessage governed = engine(List.of(rule("orders", RuleTarget.BODY_PATH, "email", DataClass.EMAIL)), 1024)
                .govern(masked("orders"), content, false);

        assertThat(governed.decoded().serialized()).doesNotContain("ada@example.com");
        assertThat(String.valueOf(governed.decoded().record())).doesNotContain("ada@example.com");
    }

    @Test
    void anAuthorizationPropertyIsDroppedEvenForClearAccess() {
        MessageContent content = props(Map.of("Authorization", "Basic dXNlcjpwYXNz"), null);

        GovernedMessage forClear = engine(List.of(), 1024).govern(clear("orders"), content, false);

        assertThat(forClear.properties()).containsEntry("Authorization", "[dropped credential]");
        assertThat(forClear.redactions())
                .containsExactly(
                        new Redaction(Location.PROPERTY, "Authorization", DataClass.CREDENTIAL, Action.DROP, false));
    }

    @Test
    void aBearerTokenInAnUnnamedJsonFieldIsDropped() {
        GovernedMessage governed = engine(List.of(), 1024)
                .govern(clear("orders"), props(Map.of(), "{\"note\":\"Bearer eyJhbGciOi.abcdefg.hijklmn\"}"), false);

        assertThat(governed.body()).isEqualTo("{\"note\":\"[dropped credential]\"}");
    }

    @Test
    void anUndeclaredCardNumberIsMaskedAndBecomesAFinding() {
        GovernedMessage governed = engine(List.of(), 1024)
                .govern(masked("orders.eu"), props(Map.of(), "{\"payment\":{\"ref\":\"4242 4242 4242 4242\"}}"), false);

        assertThat(governed.body()).isEqualTo("{\"payment\":{\"ref\":\"[payment card number ending 4242]\"}}");
        assertThat(findings).containsExactly("orders.eu|BODY|payment.ref|PAN");
    }

    @Test
    void aCardNumberAsAJsonNumberIsMasked() {
        GovernedMessage governed =
                engine(List.of(), 1024).govern(masked("orders"), props(Map.of(), "{\"pan\":4242424242424242}"), false);

        assertThat(governed.body()).isEqualTo("{\"pan\":\"[payment card number ending 4242]\"}");
    }

    @Test
    void anAddressScopedRuleAppliesOnlyToItsAddresses() {
        PolicyEngine engine =
                engine(List.of(rule("orders.#", RuleTarget.PROPERTY, "customerName", DataClass.PERSONAL)), 1024);
        MessageContent content = props(Map.of("customerName", "Jane Doe"), null);

        assertThat(engine.govern(masked("orders.eu"), content, false).properties())
                .containsEntry("customerName", "[redacted personal data]");
        assertThat(engine.govern(masked("billing.eu"), content, false).properties())
                .containsEntry("customerName", "Jane Doe");
        assertThat(findings).isEmpty();
    }

    @Test
    void aDismissalExemptsOneClassInOneField() {
        Rule dismissal = new Rule(
                UUID.randomUUID(),
                "orders",
                RuleTarget.PROPERTY,
                "orderNumber",
                DataClass.PHONE,
                null,
                false,
                true,
                true);
        GovernedMessage governed = engine(List.of(dismissal), 1024)
                .govern(masked("orders"), props(Map.of("orderNumber", "+4915112345678"), null), false);

        assertThat(governed.properties()).containsEntry("orderNumber", "+4915112345678");
        assertThat(governed.redactions()).isEmpty();
    }

    @Test
    void clearAccessShowsSensitiveValuesMarkedAsSensitive() {
        GovernedMessage governed = engine(List.of(), 1024)
                .govern(clear("orders"), props(Map.of("contact", "jane@example.com"), null), false);

        assertThat(governed.properties()).containsEntry("contact", "jane@example.com");
        assertThat(governed.redactions())
                .containsExactly(new Redaction(Location.PROPERTY, "contact", DataClass.EMAIL, Action.REDACT, true));
        assertThat(governed.clearCount()).isEqualTo(1);
    }

    @Test
    void aBinaryBodyIsWithheldUnlessClear() {
        MessageContent binary = new MessageContent(Map.of(), Map.of(), "AAEC", true, null);

        GovernedMessage forMasked = engine(List.of(), 1024).govern(masked("orders"), binary, false);
        assertThat(forMasked.body()).isNull();
        assertThat(forMasked.withheld())
                .singleElement()
                .satisfies(w -> assertThat(w.reason()).contains("Binary"));

        GovernedMessage forClear = engine(List.of(), 1024).govern(clear("orders"), binary, false);
        assertThat(forClear.body()).isEqualTo("AAEC");
        assertThat(forClear.withheld()).isEmpty();
    }

    @Test
    void bytesPastTheScanLimitAreWithheldAndNameTheSetting() {
        String body = "a".repeat(40) + " jane@example.com";
        GovernedMessage governed = engine(List.of(), 10).govern(masked("orders"), props(Map.of(), body), false);

        assertThat(governed.body()).isEqualTo("a".repeat(10));
        assertThat(governed.withheld())
                .singleElement()
                .satisfies(w -> assertThat(w.settingKey()).isEqualTo(GovernanceSettings.SCAN_LIMIT));
    }

    @Test
    void storageSealsOriginalsButNeverACredential() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("authToken", "s3cr3t");
        properties.put("contact", "jane@example.com");
        String body = "{\"note\":\"mail jane@example.com with Bearer abcdef.ghijkl\"}";

        GovernedMessage stored = engine(List.of(), 1024).govern(masked("orders"), props(properties, body), true);

        assertThat(stored.properties()).containsEntry("authToken", "[dropped credential]");
        assertThat(stored.sealable()).containsEntry("PROPERTY:contact", "jane@example.com");
        assertThat(stored.sealable()).doesNotContainKey("PROPERTY:authToken");
        assertThat(stored.sealable().get("BODY:"))
                .contains("jane@example.com")
                .contains("[dropped credential]")
                .doesNotContain("abcdef");
        assertThat(stored.policyVersion()).isEqualTo(7);
    }

    @Test
    void governingAMaskedMessageAgainChangesNothing() {
        PolicyEngine engine = engine(List.of(), 1024);
        MessageContent original =
                props(Map.of("contact", "jane@example.com", "card", "5555555555554444"), "{\"a\":\"x@y.io\"}");
        GovernedMessage once = engine.govern(masked("orders"), original, false);

        GovernedMessage twice = engine.govern(
                masked("orders"),
                new MessageContent(once.headers(), once.properties(), once.body(), false, null),
                false);

        assertThat(twice.properties()).isEqualTo(once.properties());
        assertThat(twice.body()).isEqualTo(once.body());
    }

    @Test
    void freeTextHasDetectedValuesMasked() {
        assertThat(engine(List.of(), 1024).governText("email = 'jane@example.com' AND card = '4242424242424242'"))
                .isEqualTo("email = '[redacted email]' AND card = '[payment card number ending 4242]'");
    }
}
