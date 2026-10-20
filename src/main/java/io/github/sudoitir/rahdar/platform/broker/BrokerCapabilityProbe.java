package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities.CapabilityAssessment;
import java.util.Optional;

/**
 * What a connection can and cannot do (ADR-0002 non-negotiable #5), assessed against a reachable node.
 * The system module of the cluster's broker implements it.
 */
public interface BrokerCapabilityProbe {

    /**
     * In {@code messageIo}'s reason exactly when the broker still truncates management-returned bodies;
     * the recommendation that lifts the cap tests for the same string (ADR-0068).
     */
    String TRUNCATING = "truncates oversized body/property values at" + " management-message-attribute-size-limit";

    default BrokerCapabilities probe(BrokerTarget target, SubscriptionVerdict notificationVerdict) {
        return probe(target, notificationVerdict, Optional.empty());
    }

    /**
     * @param recordedWrite what an actual management write has established for this connection, if one
     *     has ever been attempted (ADR-0049 D5); the probe never writes to find out for itself
     */
    BrokerCapabilities probe(
            BrokerTarget target, SubscriptionVerdict notificationVerdict, Optional<CapabilityAssessment> recordedWrite);
}
