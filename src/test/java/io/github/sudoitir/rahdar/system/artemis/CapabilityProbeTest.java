package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities.CapabilityStatus;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.SubscriptionVerdict;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.RequestMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CapabilityProbeTest {

    private final JsonMapper mapper = new JsonMapper();
    private final CapabilityProbe probe = new CapabilityProbe(new ArtemisNodes(null));

    private record Fixture(BrokerTarget client, FixtureJolokia server) {
        BrokerCapabilities probe(CapabilityProbe probe) {
            return probe.probe(client, new SubscriptionVerdict.NotAttempted());
        }
    }

    /**
     * Every request the probe sends must be read-only (ADR-0002): search / read, or an
     * exec of one of the named read-only operations. {@code getAddressSettingsAsJSON}
     * joins {@code listNetworkTopology} here for the slow-consumer assessment — a
     * getter, like the name says. Anything else is a probe that mutates the broker.
     */
    private RequestMatcher readOnly() {
        return request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            JsonNode root = mapper.readTree(body);
            for (JsonNode entry :
                    root.isArray() ? root : mapper.createArrayNode().add(root)) {
                String type = entry.get("type").asText();
                assertThat(type).isIn("search", "read", "exec");
                if (type.equals("exec")) {
                    assertThat(entry.get("operation").asText())
                            .satisfiesAnyOf(
                                    op -> assertThat(op).startsWith("listNetworkTopology"),
                                    op -> assertThat(op).startsWith("getAddressSettingsAsJSON"));
                }
            }
        };
    }

    private Fixture fixture(String... orderedFixtureNames) {
        FixtureJolokia server = new FixtureJolokia();
        for (String name : orderedFixtureNames) {
            server.expect()
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(readOnly())
                    .andRespond(withSuccess(body(name), MediaType.APPLICATION_JSON));
        }
        return new Fixture(server.target(), server);
    }

    private static String body(String fixtureName) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixtureName).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readAvailableWriteUnknownNotificationsUnknown() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.managementRead().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        // A read says nothing about writing (ADR-0049 D5): unknown until one is tried.
        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        // No scrape cycle has produced a subscription outcome yet.
        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.notifications().reason()).contains("first scrape cycle has not completed");
        assertThat(caps.notifications().reason()).contains("CORE acceptor present");
        assertThat(caps.notifications().reason()).contains("activemq.notifications address present");
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.messageIo().reason()).contains("truncates").contains("Core client");
        f.server().verify();
    }

    /**
     * The probe makes no write, so it cannot report one as unavailable. Recorded
     * evidence from a real write is overlaid by {@code CapabilityLedger}; supplying
     * it here is what the caller does (ADR-0049 D5).
     */
    @Test
    void recordedRefusalIsReportedWhenOneHasBeenObserved() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps = probe.probe(
                f.client(),
                new SubscriptionVerdict.NotAttempted(),
                Optional.of(CapabilityAssessment.unavailable("The broker refused a management write.")));

        assertThat(caps.managementRead().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        f.server().verify();
    }

    /**
     * A bad argument is not an authorization refusal, so nothing about it may reach
     * the ledger — the probe keeps reporting unknown, and write operations stay
     * offered. One malformed request must not permanently disable a button.
     */
    @Test
    void writeStaysUnknownUntilAWriteIsActuallyAttempted() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.managementWrite().reason()).contains("No management write has been attempted");
        // Offered, not hidden: absence of evidence must not block the operator.
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.messageIo().reason()).contains("not yet established");
        f.server().verify();
    }

    @Test
    void notificationsReasonReportsMissingPreconditions() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors-empty.json",
                "addresses-without-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.notifications().reason()).contains("CORE acceptor not found");
        assertThat(caps.notifications().reason()).contains("activemq.notifications address not found");
        f.server().verify();
    }

    @Test
    void everythingUnknownWhenManagementReadFails() {
        // search resolves the broker, but the attribute read comes back non-200.
        Fixture f = fixture("search-broker.json", "exec-forbidden.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.managementRead().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        f.server().verify();
    }

    @Test
    void notificationsAvailableWhenSubscribed() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.Connected(1, Instant.now()));

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.notifications().reason()).contains("Subscribed to activemq.notifications");
        assertThat(caps.notifications().brokerXmlSnippet()).contains("NotificationActiveMQServerPlugin");
        f.server().verify();
    }

    @Test
    void notificationsUnavailableWhenSubscriptionRefusedForPermission() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps = probe.probe(
                f.client(), new SubscriptionVerdict.Failed(SubscriptionVerdict.Kind.PERMISSION_DENIED, "AMQ229213"));

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.notifications().brokerXmlSnippet()).contains("consume").contains("createNonDurableQueue");
        f.server().verify();
    }

    @Test
    void notificationsUnavailableWhenNoCoreUrl() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors-empty.json",
                "addresses-without-notifications.json",
                "address-settings.json");

        BrokerCapabilities caps =
                probe.probe(f.client(), new SubscriptionVerdict.Failed(SubscriptionVerdict.Kind.NO_ENDPOINT, "no url"));

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.notifications().brokerXmlSnippet()).contains("acceptor");
        f.server().verify();
    }

    /**
     * The two things one read of the catch-all settles. Both were previously
     * reported from a fixed sentence rather than from the broker: slow-consumer
     * detection as permanently unknowable (§16 M8 disproved that), and message I/O
     * as always truncating — which made the recommendation to lift the cap
     * impossible to ever close, however many times it was applied.
     */
    @Test
    void theCatchAllSettingsSettleTruncationAndSlowConsumerDetection() {
        Fixture configured = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings-configured.json");

        BrokerCapabilities caps = configured.probe(probe);
        assertThat(caps.slowConsumerDetection().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.slowConsumerDetection().reason())
                .contains("threshold 1")
                .contains("NOTIFY");
        assertThat(caps.messageIo().reason())
                .contains("whole bodies come back")
                .doesNotContain(CapabilityProbe.TRUNCATING);
        configured.server().verify();

        Fixture defaults = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json",
                "address-settings.json");

        BrokerCapabilities bare = defaults.probe(probe);
        // An absent threshold means none is set, not that Rahdar cannot tell.
        assertThat(bare.slowConsumerDetection().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(bare.messageIo().reason()).contains(CapabilityProbe.TRUNCATING);
        defaults.server().verify();
    }
}
