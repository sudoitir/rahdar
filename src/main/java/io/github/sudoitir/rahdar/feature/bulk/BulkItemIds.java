package io.github.sudoitir.rahdar.feature.bulk;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.platform.broker.InvalidResourceIdException;

/**
 * A bulk item is a subscription named by its canonical id ({@code artemis.queue/orders},
 * {@code kafka.group-topic/billing/payments}). Permissions and the single-queue commands know it by its first
 * path segment: the queue, or the group.
 */
final class BulkItemIds {

    private BulkItemIds() {}

    /** The queue or group the id names, which permissions are held on. */
    static String name(String id) {
        return parse(id).path().segments().getFirst();
    }

    /** The id of the subscription's kind. */
    static String kind(String id) {
        return parse(id).kind().value();
    }

    /** The id as the SDK reads it; a text that is not one is a 400. */
    static ResourceId parse(String id) {
        try {
            return ResourceId.parse(id);
        } catch (RuntimeException _) {
            throw new InvalidResourceIdException(
                    "'" + id + "' is not a subscription id, such as artemis.queue/orders.");
        }
    }
}
