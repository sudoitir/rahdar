package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;

/**
 * What an enabled feature adds to a registration check (ADR-0070), read from the first node the
 * check reached. The preview carries each contribution under its feature id, so a disabled feature
 * is simply absent from it and the clusters module never names the features that contribute.
 */
public interface RegistrationCheckContributor {

    /** The contributing feature's id, the key of its contribution in the preview. */
    String featureId();

    /**
     * The contribution, as the feature's own view type. It must not throw for a node it cannot read;
     * it says so in the view instead, because the check itself succeeded.
     *
     * @param coreUsername the account the cluster will connect to Core with, from the request;
     *     null when it connects anonymously
     */
    Object contribute(BrokerCapabilities capabilities, BrokerTarget seed, String coreUsername);
}
