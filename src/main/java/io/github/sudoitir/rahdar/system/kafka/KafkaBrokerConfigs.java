package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Operation.Update;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerConfig;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerConfigEntry;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerConfigSource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A broker's configuration, or the cluster-wide default, with where each value comes from, and what a change would do
 * (ADR-0216). It is read and previewed through the cluster's resources; the preview writes nothing.
 */
@Service
@RequiredArgsConstructor
public class KafkaBrokerConfigs {

    /** The path word for the cluster-wide default; the driver names it {@code *}. */
    public static final String DEFAULT = "default";

    static final String DEFAULT_NOTE = "The cluster-wide default lists only the settings set on it. Which of the others"
            + " need a restart shows on a broker.";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public BrokerConfig read(UUID clusterId, String broker) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        String name = driverName(broker);
        Resource resource = find(clusterId, name);
        Set<String> readOnly = new HashSet<>(split(text(resource, KafkaKinds.CONFIG_READ_ONLY)));
        Map<String, String> sources = sources(resource);
        List<BrokerConfigEntry> entries = new ArrayList<>();
        new TreeMap<>(resource.attributes().asMap()).forEach((key, value) -> {
            if (key.startsWith(KafkaKinds.CONFIG_PREFIX) && value != null) {
                String setting = key.substring(KafkaKinds.CONFIG_PREFIX.length());
                entries.add(new BrokerConfigEntry(
                        setting,
                        String.valueOf(value),
                        source(sources.get(setting)),
                        readOnly.contains(setting),
                        unit(setting)));
            }
        });
        boolean clusterDefault = name.equals("*");
        return new BrokerConfig(
                clusterDefault ? DEFAULT : name, clusterDefault, entries, clusterDefault ? DEFAULT_NOTE : null);
    }

    public ConfigPreview preview(UUID clusterId, String broker, ConfigChangeRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.DESTINATION_UPDATE);
        String name = driverName(broker);
        Map<String, String> set = request.set() == null ? Map.of() : request.set();
        List<String> remove = request.remove() == null ? List.of() : request.remove();
        if (set.isEmpty() && remove.isEmpty()) {
            throw new IllegalArgumentException("Name a setting to change or an override to remove.");
        }
        Preview preview = KafkaFailures.call(() ->
                gateway.cluster(clusterId).operations().preview(id(name), new Update(patch(request)), Scope.whole()));
        List<ConfigChangeRow> rows = new ArrayList<>();
        for (AttributeChange change : preview.diff()) {
            String after = change.after()
                    .map(String::valueOf)
                    .filter(v -> !v.isEmpty())
                    .orElse(null);
            rows.add(new ConfigChangeRow(
                    change.key().substring(KafkaKinds.CONFIG_PREFIX.length()),
                    change.before().map(String::valueOf).orElse(null),
                    after,
                    null,
                    null));
        }
        rows.sort(Comparator.comparing(ConfigChangeRow::name));
        return new ConfigPreview(rows, true, preview.etag());
    }

    /** The driver's attributes for an update: an empty value removes the override. */
    static Attributes patch(ConfigChangeRequest request) {
        return Attributes.of(KafkaTopicConfigs.patch(request));
    }

    static ResourceId id(String driverName) {
        return new ResourceId(KafkaKinds.BROKER_CONFIG, ResourcePath.of(driverName));
    }

    /** The driver's name for a path word: a broker number, or {@code *} for the default. */
    static String driverName(String broker) {
        if (DEFAULT.equals(broker)) {
            return "*";
        }
        if (broker == null || !broker.matches("\\d{1,9}")) {
            throw new IllegalArgumentException("A broker is named by its number, or default for the cluster default.");
        }
        return broker;
    }

    private Resource find(UUID clusterId, String name) {
        // The default sorts first, so one result is enough for it; a number matches only itself.
        ResourceQuery query = ResourceQuery.of(KafkaKinds.BROKER_CONFIG).named(name);
        if (name.equals("*")) {
            query = query.limit(1);
        }
        return KafkaGroups.read(gateway.cluster(clusterId), query).items().stream()
                .filter(r -> r.id().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("broker", name.equals("*") ? DEFAULT : name));
    }

    private static String text(Resource resource, String attribute) {
        return resource.attributes().raw(attribute).map(String::valueOf).orElse("");
    }

    private static List<String> split(String commaSeparated) {
        return commaSeparated.isBlank() ? List.of() : List.of(commaSeparated.split(","));
    }

    private static Map<String, String> sources(Resource resource) {
        Map<String, String> sources = new HashMap<>();
        for (String pair : split(text(resource, KafkaKinds.CONFIG_SOURCES))) {
            int eq = pair.lastIndexOf('=');
            if (eq > 0) {
                sources.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return sources;
    }

    private static BrokerConfigSource source(String source) {
        if (source == null) {
            return BrokerConfigSource.DEFAULT;
        }
        return switch (source) {
            case "node" -> BrokerConfigSource.NODE;
            case "cluster" -> BrokerConfigSource.CLUSTER;
            case "static" -> BrokerConfigSource.STATIC;
            default -> BrokerConfigSource.DEFAULT;
        };
    }

    private static String unit(String name) {
        if (name.endsWith(".ms")) {
            return "ms";
        }
        if (name.endsWith(".bytes")) {
            return "bytes";
        }
        return null;
    }
}
