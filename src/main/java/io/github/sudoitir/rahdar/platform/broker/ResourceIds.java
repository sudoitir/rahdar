package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import java.time.Instant;

/** Rahdar's way into the SDK's resource model: a system's kind for a class, ids, and attribute values. */
public final class ResourceIds {

    private ResourceIds() {}

    /** The connection's system's kind of this class; the first, when the system has several. */
    public static KindId kind(ClusterConnection connection, ResourceClass resourceClass) {
        return connection.resources().kinds().stream()
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

    /** The kind's name for people: the local part of its id in sentence case ({@code artemis.queue} is "Queue"). */
    public static String label(KindId kind) {
        String value = kind.value();
        String local = value.substring(value.indexOf('.') + 1).replace('-', ' ');
        return local.isEmpty() ? local : Character.toUpperCase(local.charAt(0)) + local.substring(1);
    }

    /** The id of the kind a canonical id text names, or {@code null} for no id. */
    public static String kindOf(String id) {
        return id == null ? null : ResourceId.parse(id).kind().value();
    }

    /** The kind's name for people, of the kind a canonical id text names, or {@code null} for no id. */
    public static String kindLabelOf(String id) {
        return id == null ? null : label(ResourceId.parse(id).kind());
    }

    /** The name of the resource a canonical id text names, or {@code null} for no id. */
    public static String nameOf(String id) {
        return id == null ? null : ResourceId.parse(id).name();
    }

    /** The connection's system's kind of this id, or a 400 when the system has no such kind. */
    public static Kind kindOf(ClusterConnection connection, ResourceId id) {
        return connection.resources().kinds().stream()
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
