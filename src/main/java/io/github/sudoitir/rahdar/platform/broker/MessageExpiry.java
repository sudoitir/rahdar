package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;
import java.util.UUID;

/**
 * Expiring messages now: the broker sends them where expired messages go, as if their time had run
 * out. Only some systems offer it, so their system module provides this; where none does, the
 * action is unavailable.
 */
public interface MessageExpiry {

    /** Expires the messages with these ids in the queue on the node; reports what it did not act on. */
    BulkResult byIds(UUID clusterId, UUID nodeId, String queue, List<Long> ids);

    /** Expires the messages that match a filter in the system's own dialect, or every message for a blank one. */
    long byFilter(UUID clusterId, UUID nodeId, String queue, String filter);
}
