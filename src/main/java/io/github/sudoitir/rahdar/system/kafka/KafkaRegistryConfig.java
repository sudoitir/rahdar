package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import java.util.Map;
import java.util.Set;

/** The schema-registry fields of a Kafka connection, checked and copied onto the driver's options. */
final class KafkaRegistryConfig {

    static final Set<String> KINDS = Set.of(KafkaOptions.REGISTRY_NONE, KafkaOptions.REGISTRY_CONFLUENT);

    private KafkaRegistryConfig() {}

    static String kind(String given) {
        return given == null || given.isBlank() ? KafkaOptions.REGISTRY_NONE : given;
    }

    /** Refuses a kind or a Confluent URL the driver cannot use. Never echoes a credential. */
    static void require(String kind, String url) {
        String resolved = kind(kind);
        if (!KINDS.contains(resolved)) {
            throw new IllegalArgumentException("registryKind must be one of none, confluent.");
        }
        if (KafkaOptions.REGISTRY_CONFLUENT.equals(resolved) && blank(url) == null) {
            throw new IllegalArgumentException("registryUrl is required when the registry kind is confluent.");
        }
        if (KafkaOptions.REGISTRY_CONFLUENT.equals(resolved) && !isHttp(url)) {
            throw new IllegalArgumentException("registryUrl must be an http or https URL.");
        }
    }

    static void put(Map<String, String> options, String kind, String url, String credentials) {
        String resolved = kind(kind);
        options.put(KafkaOptions.REGISTRY_KIND, resolved);
        if (KafkaOptions.REGISTRY_NONE.equals(resolved)) {
            return;
        }
        if (blank(url) != null) {
            options.put(KafkaOptions.REGISTRY_URL, url);
        }
        if (blank(credentials) != null) {
            options.put(KafkaOptions.REGISTRY_CREDENTIALS, credentials);
        }
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isHttp(String url) {
        String trimmed = url.strip();
        return trimmed.startsWith("https://") || trimmed.startsWith("http://");
    }
}
