package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChannelConfigValidatorTest {

    private final ChannelConfigValidator validator =
            new ChannelConfigValidator(JsonMapper.builder().build(), new DestinationPolicy());

    private static final String EMAIL = """
            {"host":"smtp.example.com","port":587,"security":"STARTTLS","from":"rahdar@example.com","to":%s}""";

    @Test
    void aValidEmailChannelPasses() {
        assertThatCode(() -> validator.validate("EMAIL", EMAIL.formatted("[\"oncall@example.com\"]"), null, false))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmailChannelWithoutRecipientsNamesTheField() {
        String config = EMAIL.formatted("[]");

        assertThatThrownBy(() -> validator.validate("EMAIL", config, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("to:");
    }

    @Test
    void aMalformedRecipientIsNamed() {
        assertThatThrownBy(() -> validator.validate("EMAIL", EMAIL.formatted("[\"not an address\"]"), null, false))
                .hasMessageContaining("\"not an address\"");
    }

    @Test
    void aPortOutOfRangeIsRejected() {
        String config = """
                {"host":"h","port":70000,"security":"STARTTLS","from":"a@example.com","to":["b@example.com"]}""";
        assertThatThrownBy(() -> validator.validate("EMAIL", config, null, false))
                .hasMessageStartingWith("port:");
    }

    @Test
    void aWebhookNeedsAnHttpUrlAndABase64Secret() {
        assertThatThrownBy(() ->
                        validator.validate("WEBHOOK", "{\"url\":\"ftp://x\"}", "whsec_AAAAAAAAAAAAAAAAAAAAAA==", false))
                .hasMessageStartingWith("url:");
        assertThatThrownBy(() -> validator.validate("WEBHOOK", "{\"url\":\"https://x.example\"}", "not base64!", false))
                .hasMessageStartingWith("secret:");
        assertThatCode(() -> validator.validate(
                        "WEBHOOK", "{\"url\":\"https://x.example\"}", "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", false))
                .doesNotThrowAnyException();
    }

    @Test
    void aBlankSecretOnUpdateKeepsTheStoredOne() {
        assertThatCode(() -> validator.validate("TEAMS", "{}", "", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate("TEAMS", "{}", "", false)).hasMessageStartingWith("secret:");
    }

    @Test
    void aPagerDutyRoutingKeyIs32CharactersOnTheDefaultEndpoint() {
        assertThatThrownBy(() -> validator.validate("PAGERDUTY", "{}", "short", false))
                .hasMessageStartingWith("secret:");
        assertThatCode(() -> validator.validate("PAGERDUTY", "{}", "0123456789abcdef0123456789abcdef", false))
                .doesNotThrowAnyException();
        // A compatible receiver may use keys of its own shape.
        assertThatCode(() ->
                        validator.validate("PAGERDUTY", "{\"url\":\"https://oncall.example/v2/enqueue\"}", "k", false))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownKindIsNamed() {
        assertThatThrownBy(() -> validator.validate("SMS", "{}", null, false)).hasMessageContaining("SMS");
    }
}
