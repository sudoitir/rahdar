package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.platform.broker.MessageTaps;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapRefusal;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapSpec;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Installs and removes one capture tap on one node (ADR-0062 D1).
 *
 * <p>A tap is a bounded, restricted second copy of one address's traffic, installed beside
 * production routing and never in its way: the broker system's {@link MessageTaps} says how.
 * Capture owns what is Rahdar's about it: the names, the broker role it restricts the queue to
 * and the bounds it holds the queue to.
 *
 * <p>Removal is always an explicit act: none of a tap's objects disappears on a broker restart
 * (ADR-0065). Both directions are idempotent, because the reconciler is the only caller and it
 * runs on a schedule.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureTap {

    private final SystemExtensions extensions;
    private final CaptureProperties properties;

    /** What one tap covers: a source address on a node, for a subscription. */
    public record Spec(UUID subscriptionId, String address, long ringSize, String filter) {}

    // ---- install ---------------------------------------------------------

    /**
     * Install, or confirm already installed. Returns the tap's name, which is also its divert's name.
     *
     * @throws CaptureRefusedException when the broker's configuration means capture
     *     would either see nothing or leave payload unguarded — both of which are worse
     *     than not capturing at all.
     */
    public String install(UUID clusterId, UUID nodeId, String instanceId, Spec spec) {
        requireBrokerRole();
        String name = CaptureNames.of(instanceId, spec.address(), spec.subscriptionId());
        try {
            taps(clusterId)
                    .install(
                            clusterId,
                            nodeId,
                            tapSpec(instanceId, name, spec.address(), spec.filter(), spec.ringSize()));
        } catch (TapRefusal e) {
            throw new CaptureRefusedException(e.getMessage(), e.snippet());
        }
        return name;
    }

    /**
     * Remove a tap, and the shared settings with it once this instance has no tap left
     * on the node. Idempotent: anything already gone is a success.
     */
    public void remove(UUID clusterId, UUID nodeId, String instanceId, String name) {
        MessageTaps taps = taps(clusterId);
        taps.remove(clusterId, nodeId, name, CaptureNames.queueOf(name), false);
        if (installedNames(clusterId, nodeId, instanceId).isEmpty()) {
            // Only this instance's settings: another Rahdar's capture queues keep their DROP
            // policy and their restriction for as long as that Rahdar has taps (ADR-0079).
            taps.removeSettings(clusterId, nodeId, CaptureNames.matchFor(instanceId));
            // The shared match earlier versions wrote for every instance goes only once no
            // capture divert from any instance is left on this node.
            if (taps.installed(clusterId, nodeId, RahdarNames.CAPTURE_PREFIX).isEmpty()) {
                taps.removeSettings(clusterId, nodeId, CaptureNames.LEGACY_MATCH);
            }
        }
    }

    /**
     * Capture queues are restricted to Rahdar's own broker role, and Rahdar cannot discover
     * which role that is. With no role configured there is nothing to restrict to, so capture
     * is refused rather than installed readable by whoever the broker authorises (ADR-0079).
     */
    private void requireBrokerRole() {
        String role = properties.brokerRole();
        if (role == null || role.isBlank()) {
            throw new CaptureRefusedException(
                    "Capture restricts its queues to the broker role Rahdar's own user holds, and none is configured."
                            + " Set rahdar.capture.broker-role (RAHDAR_CAPTURE_BROKER_ROLE) to that"
                            + " role, then capture starts on the next pass.",
                    null);
        }
        if ("amq".equals(role) && warnedDefaultRole.compareAndSet(false, true)) {
            log.warn(
                    "Capture queues are restricted to the broker role 'amq', which every user created by `artemis create`"
                            + " holds. Give Rahdar's broker user a dedicated role and set"
                            + " rahdar.capture.broker-role to it.");
        }
    }

    private final AtomicBoolean warnedDefaultRole = new AtomicBoolean();

    /** The capture taps this instance owns on this node — the reconciler's actual state. */
    public List<String> installedNames(UUID clusterId, UUID nodeId, String instanceId) {
        return taps(clusterId).installed(clusterId, nodeId, RahdarNames.CAPTURE_PREFIX + instanceId + '.');
    }

    /** The configuration an operator would add to make a refusal go away. */
    public String captureBrokerXml(UUID clusterId, String instanceId, long ringSize) {
        return taps(clusterId).settingsSnippet(tapSpec(instanceId, null, null, null, ringSize));
    }

    private MessageTaps taps(UUID clusterId) {
        return extensions.require(clusterId, MessageTaps.class);
    }

    private TapSpec tapSpec(String instanceId, String name, String address, String filter, long ringSize) {
        return new TapSpec(
                name,
                name == null ? null : CaptureNames.queueOf(name),
                CaptureNames.matchFor(instanceId),
                address,
                filter,
                ringSize,
                properties.maxRingBytes().toBytes(),
                properties.expiry(),
                properties.brokerRole());
    }
}
