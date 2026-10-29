package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.KindLabel;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.kernel.core.KindName;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Rahdar's way into the SDK's resource model: a system's kind for a class, ids, and attribute values. */
public final class ResourceIds {

    /**
     * A driver's kind catalogue, once per connection. Listing it is a broker round, and a page of subscriptions
     * asks for a label on every row.
     */
    private static final Map<ClusterConnection, List<Kind>> KINDS = Collections.synchronizedMap(new WeakHashMap<>());

    private ResourceIds() {}

    /** The connection's kinds, fetched once and kept while the connection is alive. */
    private static List<Kind> kinds(ClusterConnection connection) {
        List<Kind> cached = KINDS.get(connection);
        if (cached != null) {
            return cached;
        }
        List<Kind> loaded = List.copyOf(connection.resources().kinds());
        KINDS.put(connection, loaded);
        return loaded;
    }

    /** The connection's system's kind of this class; the first, when the system has several. */
    public static KindId kind(ClusterConnection connection, ResourceClass resourceClass) {
        return kinds(connection).stream()
                .filter(k -> k.resourceClass() == resourceClass)
                .map(Kind::id)
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.BAD_RESPONSE,
                        "This broker has no " + resourceClass.name().toLowerCase() + " resources."));
    }

    /** The id of a resource of this class named by these segments, outermost first. */
    public static ResourceId of(ClusterConnection connection, ResourceClass resourceClass, String... segments) {
        return new ResourceId(kind(connection, resourceClass), ResourcePath.of(segments));
    }

    /** The id a client named by its kind and its path (the path as one URL segment, decoded once). */
    public static ResourceId parse(String kind, String path) {
        try {
            return ResourceId.parse(kind + "/" + path);
        } catch (RuntimeException _) {
            throw new InvalidResourceIdException("'" + kind + "/" + path + "' is not a resource id.");
        }
    }

    /** A driver's label of a kind, as the name Rahdar's views carry. */
    public static KindName name(KindLabel label) {
        return new KindName(label.singular(), label.plural());
    }

    /** The kind's name for people, as the connection's system's driver gives it. */
    public static KindName label(ClusterConnection connection, KindId kind) {
        return name(kinds(connection).stream()
                .filter(k -> k.id().equals(kind))
                .findFirst()
                .orElseThrow(() -> new InvalidResourceIdException("This cluster has no resource kind '" + kind + "'."))
                .label());
    }

    /** The id of the kind a canonical id text names, or {@code null} for no id. */
    public static String kindOf(String id) {
        return id == null ? null : ResourceId.parse(id).kind().value();
    }

    /** The kind's name for people, of the kind a canonical id text names, or {@code null} for no id. */
    public static KindName labelOf(ClusterConnection connection, String id) {
        return id == null ? null : label(connection, ResourceId.parse(id).kind());
    }

    /** The name of the resource a canonical id text names, or {@code null} for no id. */
    public static String nameOf(String id) {
        return id == null ? null : ResourceId.parse(id).name();
    }

    /** The connection's system's kind of this id, or a 400 when the system has no such kind. */
    public static Kind kindOf(ClusterConnection connection, ResourceId id) {
        return kinds(connection).stream()
                .filter(k -> k.id().equals(id.kind()))
                .findFirst()
                .orElseThrow(
                        () -> new InvalidResourceIdException("This cluster has no resource kind '" + id.kind() + "'."));
    }

    /** A text attribute, or {@code null} when the resource does not carry it. */
    public static String text(Resource r, AttributeKey<String> key) {
        return r.attributes().get(key).orElse(null);
    }

    /** The name of an id-valued attribute, or {@code null}. */
    public static String name(Resource r, AttributeKey<ResourceId> key) {
        return r.attributes().get(key).map(ResourceId::name).orElse(null);
    }

    /** The canonical id text of an id-valued attribute, or {@code null}. */
    public static String idText(Resource r, AttributeKey<ResourceId> key) {
        return r.attributes().get(key).map(ResourceId::format).orElse(null);
    }

    /** A metric's value, zero when it is absent. Never use it where zero would mislead. */
    public static long count(Resource r, AttributeKey<MetricValue> key) {
        return r.attributes().get(key).map(MetricValue::asLong).orElse(0L);
    }

    /** When a resource was created, as ISO-8601, or {@code null}. */
    public static String createdAt(Resource r) {
        return r.attributes()
                .get(StandardAttributes.CREATED_AT)
                .map(Instant::toString)
                .orElse(null);
    }
}
