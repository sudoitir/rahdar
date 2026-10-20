package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AlertMessageFormatterTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static AlertMessage message(String ruleName, String rahdarUrl) {
        return new AlertMessage(
                UUID.randomUUID(),
                ruleName,
                "CRITICAL",
                UUID.randomUUID(),
                "prod-eu",
                rahdarUrl,
                List.of(
                        new AlertMessage.Line(
                                "node:1", "node primary-1", true, 1.0, Instant.parse("2026-09-24T10:00:00Z")),
                        new AlertMessage.Line("subscription:orders", "subscription orders", false, 12.5, null)));
    }

    @Test
    void titleStatesSeverityInWordsAndCounts() {
        assertThat(AlertMessageFormatter.title(message("Node down", null)))
                .isEqualTo("[CRITICAL] Node down — prod-eu: 1 firing, 1 resolved");
    }

    @Test
    void aLineBreakInARuleNameNeverSurvivesIntoTheTitle() {
        String title = AlertMessageFormatter.title(message("Depth\r\nBcc: attacker@example.com", null));
        assertThat(title).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void htmlEscapesEveryValue() {
        String html = AlertMessageFormatter.html(message("<script>alert(1)</script>", "https://rahdar/x?a=1&b=2"));
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("a=1&amp;b=2");
    }

    @Test
    void linesCarryValueAndTime() {
        AlertMessage m = message("Node down", null);
        assertThat(AlertMessageFormatter.line(m.transitions().get(0)))
                .isEqualTo("FIRING — node primary-1 (value 1) at 2026-09-24 10:00:00 UTC");
        assertThat(AlertMessageFormatter.line(m.transitions().get(1)))
                .isEqualTo("RESOLVED — subscription orders (value 12.500)");
    }

    @Test
    void aVersionOnePayloadStillRenders() {
        String v1 = """
                {"ruleId":"%s","ruleName":"Depth","severity":"WARNING",
                 "transitions":[{"subject":"subscription:orders","kind":"FIRED","value":5}]}""".formatted(UUID.randomUUID());
        AlertMessage m = AlertMessage.parse(v1, mapper);
        assertThat(m.clusterName()).isNull();
        assertThat(m.transitions()).singleElement().satisfies(t -> {
            assertThat(t.label()).isEqualTo("subscription:orders");
            assertThat(t.fired()).isTrue();
            assertThat(t.value()).isEqualTo(5.0);
        });
        assertThat(AlertMessageFormatter.title(m)).isEqualTo("[WARNING] Depth: 1 firing");
    }

    @Test
    void anInstallationPayloadSaysInstallationWhereAClusterWouldBeNamed() {
        String payload = """
                {"ruleId":"%s","ruleName":"Storage quota","severity":"WARNING","clusterId":null,"clusterName":null,
                 "transitions":[{"subject":"audit","kind":"FIRED","value":90}]}""".formatted(UUID.randomUUID());
        AlertMessage m = AlertMessage.parse(payload, mapper);
        assertThat(AlertMessageFormatter.title(m)).isEqualTo("[WARNING] Storage quota — Installation: 1 firing");
    }
}
