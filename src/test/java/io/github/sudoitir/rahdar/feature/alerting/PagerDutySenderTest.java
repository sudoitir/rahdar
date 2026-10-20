package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PagerDutySenderTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final UUID ruleId = UUID.randomUUID();

    private String payload(String... kinds) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < kinds.length; i++) {
            t.append(i > 0 ? "," : "")
                    .append("{\"subject\":\"subscription:q")
                    .append(i)
                    .append("\",\"subjectLabel\":\"queue q")
                    .append(i)
                    .append("\",\"kind\":\"")
                    .append(kinds[i])
                    .append("\",\"value\":3,\"at\":\"2026-09-24T10:00:00Z\"}");
        }
        return """
                {"ruleId":"%s","ruleName":"Depth","severity":"CRITICAL","clusterName":"prod","transitions":[%s]}""".formatted(ruleId, t);
    }

    @Test
    void oneEventPerTransitionTriggerThenResolveOnTheSameKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PagerDutySender sender = new PagerDutySender(builder.build(), mapper);

        AlertMessage fired = AlertMessage.parse(payload("FIRED"), mapper);
        String key = PagerDutySender.dedupKey(fired, fired.transitions().get(0));

        server.expect(requestTo(PagerDutySender.DEFAULT_URL))
                .andExpect(jsonPath("$.event_action").value("trigger"))
                .andExpect(jsonPath("$.dedup_key").value(key))
                .andExpect(jsonPath("$.payload.severity").value("critical"))
                .andExpect(jsonPath("$.payload.summary").value("[CRITICAL] Depth — queue q0 (prod)"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(PagerDutySender.DEFAULT_URL))
                .andExpect(jsonPath("$.event_action").value("resolve"))
                .andExpect(jsonPath("$.dedup_key").value(key))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(sender.send(1, "{}", KEY, payload("FIRED")).success()).isTrue();
        assertThat(sender.send(2, "{}", KEY, payload("RESOLVED")).success()).isTrue();
        server.verify();
    }

    @Test
    void aMalformedEventIsPermanentAndAConfiguredEndpointIsUsed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PagerDutySender sender = new PagerDutySender(builder.build(), mapper);
        server.expect(requestTo("https://events.eu.pagerduty.com/v2/enqueue")).andRespond(withBadRequest());

        var result = sender.send(1, "{\"url\":\"https://events.eu.pagerduty.com/v2/enqueue\"}", KEY, payload("FIRED"));

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void aRateLimitMidwayIsRetriedAfterTheReceiversDelay() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PagerDutySender sender = new PagerDutySender(builder.build(), mapper);
        server.expect(ExpectedCount.once(), requestTo(PagerDutySender.DEFAULT_URL))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");
        server.expect(requestTo(PagerDutySender.DEFAULT_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        var result = sender.send(1, "{}", KEY, payload("FIRED", "FIRED", "FIRED"));

        assertThat(result.permanent()).isFalse();
        assertThat(result.retryAfter()).hasSeconds(30);
        assertThat(result.error()).startsWith("1 of 3 events accepted");
    }

    @Test
    void theKeyIsStableAndBounded() {
        AlertMessage m = new AlertMessage(
                ruleId,
                "r",
                "WARNING",
                null,
                null,
                null,
                List.of(new AlertMessage.Line("subscription:" + "x".repeat(400), "l", true, null, Instant.now())));
        String key = PagerDutySender.dedupKey(m, m.transitions().get(0));
        assertThat(key)
                .hasSize(64)
                .isEqualTo(PagerDutySender.dedupKey(m, m.transitions().get(0)));
        Map<String, Object> event =
                PagerDutySender.event(KEY, m, m.transitions().get(0));
        assertThat(event).containsEntry("event_action", "trigger");
        assertThat(PagerDutySender.severity("WARNING")).isEqualTo("warning");
        assertThat(PagerDutySender.severity("INFO")).isEqualTo("info");
    }
}
