package io.github.sudoitir.rahdar.platform.broker;

import java.util.Map;

/**
 * A queue's full configuration as one node holds it. The broker SDK's resource listing carries a queue's
 * standard attributes only, so the system that has more to show provides this; where none does, the
 * configuration is unavailable.
 */
public interface QueueSettings extends SystemExtension {

    /** The queue's configuration on the node, keyed by the system's own configuration names. */
    Map<String, Object> read(BrokerTarget node, String queue, String address, String routingType);
}
