package io.github.sudoitir.rahdar.platform.broker;

import java.util.UUID;

/** The serde a cluster binds to a topic's key or value, or null when none is stored. */
public interface SchemaBindingLookup {

    String serde(UUID clusterId, String topic, String side);
}
