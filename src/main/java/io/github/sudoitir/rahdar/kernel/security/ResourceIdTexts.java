package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * The canonical id text of a subscription or a destination a view names by role (ADR-0206), such as
 * {@code artemis.queue/orders}, and the name an id text carries back. The kind each system gives its
 * resources is the broker module's knowledge, so the module that reaches the brokers implements this and
 * the kernel and the features need no broker access.
 */
public interface ResourceIdTexts {

    /** The id of the cluster's subscription of this name; null for a null name. */
    String subscription(UUID clusterId, String name);

    /** The id of the cluster's destination of this name; null for a null name. */
    String destination(UUID clusterId, String name);

    /** The ids of one cluster's subscriptions and destinations, built from one lookup of the cluster. */
    Of of(UUID clusterId);

    /** The type of the resource an id text names, by the kinds the cluster's system has; empty for a kind it lacks. */
    Optional<ResourceType> typeOf(UUID clusterId, String id);

    /** One cluster's id builder, for a view that names many resources. */
    interface Of {

        String subscription(String name);

        String destination(String name);
    }

    /** The name an id text carries: its last path segment, decoded. An id with no path is returned as it is. */
    static String name(String id) {
        int slash = id.indexOf('/');
        if (slash < 0) {
            return id;
        }
        String path = id.substring(slash + 1);
        return URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
