package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.rahdar.feature.sql.CaptureProperties;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapRefusal;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapSource;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapSpec;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Installs and removes one plugin tap on one node (ADR-0111): capture's tap (ADR-0062, ADR-0079)
 * under its own prefix, installed by the broker system's {@link MessageTaps}.
 *
 * <p>The tap is bounded and restricted to Rahdar's broker role like capture's. Rahdar's broker role, the
 * expiry and the byte bound are capture's settings, so an estate configures one role for both.
 *
 * <p>The tap carries the tapped queue's own filter, so the copy is what that queue receives.
 */
@Component
@RequiredArgsConstructor
class PluginTap {

    private final SystemExtensions extensions;
    private final ObjectProvider<CaptureProperties> captureProperties;

    private CaptureProperties capture() {
        CaptureProperties props = captureProperties.getIfAvailable();
        if (props == null) {
            throw new TapRefusedException("Taps use capture's broker role and bounds, and the SQL console feature that"
                    + " holds them is disabled. Enable it, or register in consume mode.");
        }
        return props;
    }

    /** Whether the cluster's system can copy a destination's traffic into a tap at all. */
    boolean supported(UUID clusterId) {
        return extensions.forCluster(clusterId, MessageTaps.class).isPresent();
    }

    /** The tapped queue on this node, or empty when the node does not have it. */
    Optional<TapSource> source(UUID clusterId, UUID nodeId, String queue) {
        return extensions.require(clusterId, MessageTaps.class).source(clusterId, nodeId, queue);
    }

    /** Refuse, or install (idempotently) and verify. Returns the tap's name. */
    String install(
            UUID clusterId, UUID nodeId, String instanceId, UUID registrationId, TapSource source, long ringSize) {
        CaptureProperties capture = capture();
        String role = capture.brokerRole();
        if (role == null || role.isBlank()) {
            throw new TapRefusedException(
                    "Taps restrict their queues to the broker role Rahdar's own user holds, and none is configured."
                            + " Set rahdar.capture.broker-role (RAHDAR_CAPTURE_BROKER_ROLE) to that"
                            + " role, then the tap is installed on the next pass.");
        }
        String name = TapNames.of(instanceId, registrationId);
        try {
            extensions
                    .require(clusterId, MessageTaps.class)
                    .install(
                            clusterId,
                            nodeId,
                            new TapSpec(
                                    name,
                                    TapNames.queueOf(name),
                                    TapNames.matchFor(instanceId),
                                    source.destination(),
                                    source.filter(),
                                    ringSize,
                                    capture.maxRingBytes().toBytes(),
                                    capture.expiry(),
                                    role));
        } catch (TapRefusal e) {
            throw new TapRefusedException(e.getMessage());
        }
        return name;
    }

    /** Remove a tap, and this instance's settings once it has no tap left on the node. Idempotent. */
    void remove(UUID clusterId, UUID nodeId, String instanceId, String name) {
        MessageTaps taps = extensions.require(clusterId, MessageTaps.class);
        taps.remove(clusterId, nodeId, name, TapNames.queueOf(name), true);
        if (installedNames(clusterId, nodeId, instanceId).isEmpty()) {
            taps.removeSettings(clusterId, nodeId, TapNames.matchFor(instanceId));
        }
    }

    /** This instance's tap diverts on the node: the reconciler's view of what exists. */
    List<String> installedNames(UUID clusterId, UUID nodeId, String instanceId) {
        return extensions
                .forCluster(clusterId, MessageTaps.class)
                .map(taps -> taps.installed(clusterId, nodeId, RahdarNames.PLUGIN_TAP_PREFIX + instanceId + '.'))
                .orElse(List.of());
    }

    /**
     * Copies the broker dropped because the tap queue was full or they expired unread. {@code null} when
     * the node did not answer.
     */
    Long dropped(UUID clusterId, UUID nodeId, String name) {
        OptionalLong dropped =
                extensions.require(clusterId, MessageTaps.class).dropped(clusterId, nodeId, TapNames.queueOf(name));
        return dropped.isPresent() ? dropped.getAsLong() : null;
    }
}
