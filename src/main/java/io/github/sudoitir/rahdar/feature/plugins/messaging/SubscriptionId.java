package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.util.List;

/**
 * A subscription a registration reads, by the shared API's canonical id: the id of its kind and its
 * path ({@code artemis.queue/orders}, {@code kafka.group/billing/payments}). The path is the resource's
 * segments, outermost first, as the id writes them.
 *
 * @param kind the id of the subscription's kind, as {@code list_resources} reports it
 * @param path the subscription's path in the id's own spelling
 */
@PluginApi
public record SubscriptionId(String kind, String path) {

    public SubscriptionId {
        if (kind == null || kind.isBlank() || path == null || path.isBlank()) {
            throw new IllegalArgumentException("A subscription id needs a kind and a path.");
        }
        ResourceId.parse(kind + "/" + path);
    }

    /** The subscription of this kind whose path is these segments, outermost first. */
    public static SubscriptionId of(String kind, String... segments) {
        return parse(ResourceId.of(kind, segments).format());
    }

    /** The subscription a canonical id ({@code kind/path}) names. */
    public static SubscriptionId parse(String id) {
        ResourceId parsed = ResourceId.parse(id);
        String kind = parsed.kind().value();
        return new SubscriptionId(kind, parsed.format().substring(kind.length() + 1));
    }

    /** The canonical id, {@code kind/path}. */
    public String id() {
        return kind + "/" + path;
    }

    /** The path's segments, decoded, outermost first. */
    public List<String> segments() {
        return ResourceId.parse(id()).path().segments();
    }
}
