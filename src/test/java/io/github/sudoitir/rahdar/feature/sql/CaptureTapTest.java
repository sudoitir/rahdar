package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

/**
 * The two refusals (ADR-0062 D6). Both exist because the alternative is a tap that
 * installs cleanly and is wrong in a way nothing else in the product would reveal.
 */
class CaptureTapTest {

    private static final String BROKER = "org.apache.activemq.artemis:broker=\"primary\"";

    private DivertOperations diverts;
    private QueueLifecycleOperations queues;
    private ArtemisNode client;
    private CaptureTap tap;

    private final CaptureTap.Spec spec = new CaptureTap.Spec(UUID.randomUUID(), "ORDER.IN", 10_000L, null);

    @BeforeEach
    void setUp() {
        diverts = mock(DivertOperations.class);
        queues = mock(QueueLifecycleOperations.class);
        client = mock(ArtemisNode.class);
        deployed.clear();
        when(diverts.listDiverts(any(), any(), any())).thenAnswer(invocation -> List.copyOf(deployed));
        when(client.resolveBrokerObjectName()).thenReturn(BROKER);
        when(client.single(any(JolokiaRequest.class))).thenReturn(ok());
        tap = new CaptureTap(diverts, queues, properties(), new ObjectMapper());
    }

    @Test
    void refusesWhenAnExclusiveDivertWouldShadowTheCaptureDivert() {
        deployed.add(divert("legacy.router", "ORDER.IN", "ORDER.ROUTED", true));

        assertThatThrownBy(() -> tap.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("legacy.router")
                .hasMessageContaining("exclusive")
                // The refusal names where the traffic actually goes, because that is
                // the address the operator can capture instead.
                .hasMessageContaining("ORDER.ROUTED");

        verify(diverts, never()).createDivert(any(), any(), any());
        verify(queues, never()).createQueue(any(), any(), any());
    }

    @Test
    void anExclusiveDivertOnAnotherAddressDoesNotShadowThisOne() {
        deployed.add(divert("elsewhere", "PAYMENT.IN", "PAYMENT.ROUTED", true));
        installOnCreate();

        String name = tap.install(client, "abc12345", spec);

        assertThat(name).isEqualTo("rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());
        verify(diverts).createDivert(any(), any(), any());
    }

    @Test
    void refusesWhenTheCaptureQueueCannotBeRestricted() {
        when(client.single(any(JolokiaRequest.class))).thenAnswer(invocation -> {
            JolokiaRequest request = invocation.getArgument(0);
            return String.valueOf(request.operation()).startsWith("addSecuritySettings")
                    ? refused("AMQ229032: unauthorized")
                    : ok();
        });

        assertThatThrownBy(() -> tap.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("second copy")
                .extracting(e -> ((CaptureRefusedException) e).getBrokerXml())
                .asString()
                // The refusal carries the configuration that would grant it, not just
                // the fact that it was refused (non-negotiable #5).
                .contains("security-setting")
                .contains("rahdar.capture.abc12345.#");

        verify(queues, never()).createQueue(any(), any(), any());
        verify(diverts, never()).createDivert(any(), any(), any());
    }

    @Test
    void installBuildsTheTapInDependencyOrder() {
        installOnCreate();

        tap.install(client, "abc12345", spec);

        var order = Mockito.inOrder(queues, diverts);
        order.verify(queues).createAddress(any(), any(), any(), any());
        order.verify(queues).createQueue(any(), any(), any());
        order.verify(diverts).createDivert(any(), any(), any());
    }

    @Test
    void refusesWhenTheBrokerAcceptsTheDivertButDoesNotDeployIt() {
        // Artemis answers 200 and writes AMQ222006 to its own log when a divert's name
        // collides with an existing binding. An install that trusted the status code
        // would report a tap that captures nothing.
        when(diverts.listDiverts(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> tap.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("did not deploy");
    }

    @Test
    void settingsGoOnThisInstancesOwnMatchAndBoundTheQueueInBytes() {
        installOnCreate();

        tap.install(client, "abc12345", spec);

        ArgumentCaptor<JolokiaRequest> requests = ArgumentCaptor.forClass(JolokiaRequest.class);
        verify(client, Mockito.atLeastOnce()).single(requests.capture());
        JolokiaRequest addressSettings = requests.getAllValues().stream()
                .filter(r -> String.valueOf(r.operation()).startsWith("addAddressSettings"))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(addressSettings.arguments()))
                .contains("rahdar.capture.abc12345.#")
                .contains("maxSizeBytes");
    }

    @Test
    void removingTheLastTapClearsOnlyThisInstancesSettingsWhileAnotherInstanceStillCaptures() {
        deployed.add(divert("rahdar.capture.ff99ff99.PAY.IN." + UUID.randomUUID(), "PAY.IN", "x", false));

        tap.remove(client, "abc12345", "rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());

        ArgumentCaptor<JolokiaRequest> requests = ArgumentCaptor.forClass(JolokiaRequest.class);
        verify(client, Mockito.atLeastOnce()).single(requests.capture());
        List<String> removals = requests.getAllValues().stream()
                .filter(r -> String.valueOf(r.operation()).startsWith("remove"))
                .map(r -> String.valueOf(r.arguments()))
                .toList();
        assertThat(removals).isNotEmpty().allSatisfy(r -> assertThat(r).contains("rahdar.capture.abc12345.#"));
    }

    @Test
    void theLegacySharedMatchGoesOnlyWhenNoCaptureOfAnyInstanceIsLeft() {
        tap.remove(client, "abc12345", "rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());

        ArgumentCaptor<JolokiaRequest> requests = ArgumentCaptor.forClass(JolokiaRequest.class);
        verify(client, Mockito.atLeastOnce()).single(requests.capture());
        assertThat(requests.getAllValues().stream().map(r -> String.valueOf(r.arguments())))
                .anySatisfy(r -> assertThat(r).contains("rahdar.capture.#"));
    }

    @Test
    void captureIsRefusedUntilTheBrokerRoleIsConfigured() {
        CaptureTap unconfigured = new CaptureTap(
                diverts,
                queues,
                new CaptureProperties(
                        null,
                        Duration.ofSeconds(30),
                        Duration.ofHours(24),
                        DataSize.ofMegabytes(64),
                        Duration.ofSeconds(1)),
                new ObjectMapper());

        assertThatThrownBy(() -> unconfigured.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("rahdar.capture.broker-role");
        verify(queues, never()).createQueue(any(), any(), any());
    }

    // ---- fixtures --------------------------------------------------------

    /** The broker's divert list, as the tap sees it — mutated by a successful create. */
    private final List<DivertRow> deployed = new ArrayList<>();

    /** Make {@code listDiverts} answer the way a broker that actually deployed it would. */
    private void installOnCreate() {
        Mockito.doAnswer(invocation -> {
                    Map<String, Object> config = invocation.getArgument(2);
                    deployed.add(divert(String.valueOf(config.get("name")), spec.address(), "x", false));
                    return null;
                })
                .when(diverts)
                .createDivert(any(), any(), any());
    }

    private static CaptureProperties properties() {
        return new CaptureProperties(
                "rahdar",
                Duration.ofSeconds(30),
                Duration.ofHours(24),
                DataSize.ofMegabytes(64),
                Duration.ofSeconds(1));
    }

    private static DivertRow divert(String name, String address, String forwarding, boolean exclusive) {
        return new DivertRow(
                null, null, name, name, address, forwarding, null, "ANYCAST", null, Map.of(), exclusive, false);
    }

    private static JolokiaResponse ok() {
        return new JolokiaResponse(200, null, null, null, null);
    }

    private static JolokiaResponse refused(String error) {
        return new JolokiaResponse(403, null, error, "java.lang.SecurityException", null);
    }
}
