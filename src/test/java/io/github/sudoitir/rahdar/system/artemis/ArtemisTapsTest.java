package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapRefusal;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * The two refusals (ADR-0062 D6). Both exist because the alternative is a tap that
 * installs cleanly and is wrong in a way nothing else in the product would reveal.
 */
class ArtemisTapsTest {

    private static final String BROKER = "org.apache.activemq.artemis:broker=\"primary\"";
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private DivertOperations diverts;
    private QueueLifecycleOperations queues;
    private ArtemisNode client;
    private ArtemisTaps taps;

    private final String name = "rahdar.capture.abc12345.ORDER.IN." + UUID.randomUUID();
    private final TapSpec spec = new TapSpec(
            name,
            name + ".q",
            "rahdar.capture.abc12345.#",
            "ORDER.IN",
            null,
            10_000L,
            64_000_000L,
            Duration.ofHours(24),
            "rahdar");

    @BeforeEach
    void setUp() {
        diverts = mock(DivertOperations.class);
        queues = mock(QueueLifecycleOperations.class);
        client = mock(ArtemisNode.class);
        ArtemisNodes nodes = mock(ArtemisNodes.class);
        when(nodes.on(CLUSTER, NODE)).thenReturn(client);
        deployed.clear();
        when(diverts.listDiverts(any(), any(), any())).thenAnswer(invocation -> List.copyOf(deployed));
        when(client.resolveBrokerObjectName()).thenReturn(BROKER);
        when(client.single(any(ManagementRequest.class))).thenReturn(ok());
        taps = new ArtemisTaps(nodes, diverts, queues, new ObjectMapper());
    }

    @Test
    void refusesWhenAnExclusiveDivertWouldShadowTheTapDivert() {
        deployed.add(divert("legacy.router", "ORDER.IN", "ORDER.ROUTED", true));

        assertThatThrownBy(() -> taps.install(CLUSTER, NODE, spec))
                .isInstanceOf(TapRefusal.class)
                .hasMessageContaining("legacy.router")
                .hasMessageContaining("exclusive")
                // The refusal names where the traffic actually goes, because that is
                // the address the operator can tap instead.
                .hasMessageContaining("ORDER.ROUTED");

        verify(diverts, never()).createDivert(any(), any(), any());
        verify(queues, never()).createQueue(any(), any(), any());
    }

    @Test
    void anExclusiveDivertOnAnotherAddressDoesNotShadowThisOne() {
        deployed.add(divert("elsewhere", "PAYMENT.IN", "PAYMENT.ROUTED", true));
        installOnCreate();

        taps.install(CLUSTER, NODE, spec);

        verify(diverts).createDivert(any(), any(), any());
    }

    @Test
    void refusesWhenTheTapQueueCannotBeRestricted() {
        when(client.single(any(ManagementRequest.class))).thenAnswer(invocation -> {
            ManagementRequest request = invocation.getArgument(0);
            return String.valueOf(request.operation()).startsWith("addSecuritySettings")
                    ? refused("AMQ229032: unauthorized")
                    : ok();
        });

        assertThatThrownBy(() -> taps.install(CLUSTER, NODE, spec))
                .isInstanceOf(TapRefusal.class)
                .hasMessageContaining("second copy")
                .extracting(e -> ((TapRefusal) e).snippet())
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

        taps.install(CLUSTER, NODE, spec);

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

        assertThatThrownBy(() -> taps.install(CLUSTER, NODE, spec))
                .isInstanceOf(TapRefusal.class)
                .hasMessageContaining("did not deploy");
    }

    @Test
    void settingsGoOnTheTapsMatchAndBoundTheQueueInBytes() {
        installOnCreate();

        taps.install(CLUSTER, NODE, spec);

        ArgumentCaptor<ManagementRequest> requests = ArgumentCaptor.forClass(ManagementRequest.class);
        verify(client, Mockito.atLeastOnce()).single(requests.capture());
        ManagementRequest addressSettings = requests.getAllValues().stream()
                .filter(r -> String.valueOf(r.operation()).startsWith("addAddressSettings"))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(addressSettings.arguments()))
                .contains("rahdar.capture.abc12345.#")
                .contains("maxSizeBytes");
    }

    @Test
    void listsOnlyTheTapsUnderThePrefix() {
        deployed.add(divert("rahdar.capture.abc12345.PAY.IN." + UUID.randomUUID(), "PAY.IN", "x", false));
        deployed.add(divert("rahdar.capture.ff99ff99.PAY.IN." + UUID.randomUUID(), "PAY.IN", "x", false));
        deployed.add(divert("legacy.router", "ORDER.IN", "ORDER.ROUTED", true));

        assertThat(taps.installed(CLUSTER, NODE, "rahdar.capture.abc12345.")).hasSize(1);
        assertThat(taps.installed(CLUSTER, NODE, "rahdar.capture.")).hasSize(2);
    }

    @Test
    void removingSettingsRemovesBothKindsUnderTheMatch() {
        taps.removeSettings(CLUSTER, NODE, "rahdar.capture.abc12345.#");

        ArgumentCaptor<ManagementRequest> requests = ArgumentCaptor.forClass(ManagementRequest.class);
        verify(client, Mockito.atLeastOnce()).single(requests.capture());
        assertThat(requests.getAllValues().stream().map(r -> String.valueOf(r.operation())))
                .containsExactly("removeAddressSettings(java.lang.String)", "removeSecuritySettings(java.lang.String)");
        assertThat(requests.getAllValues())
                .allSatisfy(r -> assertThat(String.valueOf(r.arguments())).contains("rahdar.capture.abc12345.#"));
    }

    // ---- fixtures --------------------------------------------------------

    /** The broker's divert list, as the tap sees it — mutated by a successful create. */
    private final List<DivertRow> deployed = new ArrayList<>();

    /** Make {@code listDiverts} answer the way a broker that actually deployed it would. */
    private void installOnCreate() {
        Mockito.doAnswer(invocation -> {
                    Map<String, Object> config = invocation.getArgument(2);
                    deployed.add(divert(String.valueOf(config.get("name")), spec.destination(), "x", false));
                    return null;
                })
                .when(diverts)
                .createDivert(any(), any(), any());
    }

    private static DivertRow divert(String name, String address, String forwarding, boolean exclusive) {
        return new DivertRow(
                null, null, name, name, address, forwarding, null, "ANYCAST", null, Map.of(), exclusive, false);
    }

    private static ManagementResponse ok() {
        return new ManagementResponse(200, null, null, null, null);
    }

    private static ManagementResponse refused(String error) {
        return new ManagementResponse(403, null, error, "java.lang.SecurityException", null);
    }
}
