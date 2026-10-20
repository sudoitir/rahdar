package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.MessageTaps;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapRefusal;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapSpec;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

/**
 * What capture owns about a tap: its names, the broker role it needs, the bounds it hands the broker
 * system, and when the shared settings go. How a system installs the tap is its own test.
 */
class CaptureTapTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private MessageTaps taps;
    private SystemExtensions extensions;
    private CaptureTap tap;

    private final CaptureTap.Spec spec = new CaptureTap.Spec(UUID.randomUUID(), "ORDER.IN", 10_000L, null);

    @BeforeEach
    void setUp() {
        taps = mock(MessageTaps.class);
        extensions = mock(SystemExtensions.class);
        when(extensions.require(CLUSTER, MessageTaps.class)).thenReturn(taps);
        when(taps.installed(any(), any(), any())).thenReturn(List.of());
        tap = new CaptureTap(extensions, properties("rahdar"));
    }

    @Test
    void installHandsTheSystemTheNamesAndBounds() {
        String name = tap.install(CLUSTER, NODE, "abc12345", spec);

        assertThat(name).isEqualTo("rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());
        ArgumentCaptor<TapSpec> sent = ArgumentCaptor.forClass(TapSpec.class);
        verify(taps).install(eq(CLUSTER), eq(NODE), sent.capture());
        assertThat(sent.getValue().name()).isEqualTo(name);
        assertThat(sent.getValue().queue()).isEqualTo(name + ".q");
        assertThat(sent.getValue().match()).isEqualTo("rahdar.capture.abc12345.#");
        assertThat(sent.getValue().destination()).isEqualTo("ORDER.IN");
        assertThat(sent.getValue().ringSize()).isEqualTo(10_000L);
        assertThat(sent.getValue().maxBytes())
                .isEqualTo(DataSize.ofMegabytes(64).toBytes());
        assertThat(sent.getValue().expiry()).isEqualTo(Duration.ofHours(24));
        assertThat(sent.getValue().brokerRole()).isEqualTo("rahdar");
    }

    @Test
    void aRefusalOfTheSystemIsACaptureRefusalThatKeepsTheConfigurationToAdd() {
        doThrow(new TapRefusal("second copy", "<security-setting/>")).when(taps).install(any(), any(), any());

        assertThatThrownBy(() -> tap.install(CLUSTER, NODE, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("second copy")
                .extracting(e -> ((CaptureRefusedException) e).getBrokerXml())
                .isEqualTo("<security-setting/>");
    }

    @Test
    void removingTheLastTapClearsOnlyThisInstancesSettingsWhileAnotherInstanceStillCaptures() {
        when(taps.installed(CLUSTER, NODE, "rahdar.capture.")).thenReturn(List.of("rahdar.capture.ff99ff99.PAY.IN.x"));

        tap.remove(CLUSTER, NODE, "abc12345", "rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());

        verify(taps).removeSettings(CLUSTER, NODE, "rahdar.capture.abc12345.#");
        verify(taps, never()).removeSettings(CLUSTER, NODE, "rahdar.capture.#");
    }

    @Test
    void removingATapThatLeavesOthersOfThisInstanceKeepsTheSettings() {
        when(taps.installed(CLUSTER, NODE, "rahdar.capture.abc12345."))
                .thenReturn(List.of("rahdar.capture.abc12345.x"));

        tap.remove(CLUSTER, NODE, "abc12345", "rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());

        verify(taps, never()).removeSettings(any(), any(), any());
    }

    @Test
    void theLegacySharedMatchGoesOnlyWhenNoCaptureOfAnyInstanceIsLeft() {
        tap.remove(CLUSTER, NODE, "abc12345", "rahdar.capture.abc12345.ORDER.IN." + spec.subscriptionId());

        verify(taps).removeSettings(CLUSTER, NODE, "rahdar.capture.#");
    }

    @Test
    void captureIsRefusedUntilTheBrokerRoleIsConfigured() {
        CaptureTap unconfigured = new CaptureTap(extensions, properties(null));

        assertThatThrownBy(() -> unconfigured.install(CLUSTER, NODE, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("rahdar.capture.broker-role");
        verify(taps, never()).install(any(), any(), any());
    }

    private static CaptureProperties properties(String role) {
        return new CaptureProperties(
                role, Duration.ofSeconds(30), Duration.ofHours(24), DataSize.ofMegabytes(64), Duration.ofSeconds(1));
    }
}
