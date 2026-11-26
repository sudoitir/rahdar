package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.kafka.schema.DecodedRecord;
import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.openbroker.messaging.Blob;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a payload into a {@link DecodedRecordView}. A truncated body is not decoded. A tombstone is
 * {@code no-schema-id}, not an empty record.
 */
public final class SchemaDecoding {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SchemaCatalog catalog;
    private final String serde;

    public SchemaDecoding(SchemaCatalog catalog, String serde) {
        this.catalog = catalog;
        this.serde = serde;
    }

    /** No registry, so the message stays undecoded and the field stays empty. */
    public static SchemaDecoding none() {
        return new SchemaDecoding(null, null);
    }

    public DecodedRecordView value(MessageBody body, boolean truncated) {
        if (catalog == null) {
            return null;
        }
        if (truncated) {
            return DecodedRecordView.undecoded(DecodedRecord.TRUNCATED);
        }
        if (body.isTombstone()) {
            return DecodedRecordView.undecoded(DecodedRecord.NO_SCHEMA_ID);
        }
        return view(catalog.decode(serde, body));
    }

    /** A key has no tombstone. A missing key is not decoded. */
    public DecodedRecordView key(Blob key) {
        if (catalog == null || key == null) {
            return null;
        }
        return view(catalog.decode(serde, MessageBody.of(key.toByteArray())));
    }

    static DecodedRecordView view(DecodedRecord decoded) {
        return switch (decoded) {
            case DecodedRecord.Undecoded(var reason) -> DecodedRecordView.undecoded(reason);
            case DecodedRecord.Decoded(var type, var id, var subject, var record) -> {
                String serialized = JSON.writeValueAsString(record);
                yield new DecodedRecordView(type.name(), id, subject, record, null, serialized, paths(record, ""));
            }
        };
    }

    private static Map<String, String> paths(Object node, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node instanceof Map<?, ?> object) {
            object.forEach((key, value) -> {
                String next = path.isEmpty() ? String.valueOf(key) : path + "." + key;
                out.putAll(paths(value, next));
            });
            return out;
        }
        if (node instanceof List<?> list) {
            for (Object value : list) {
                out.putAll(paths(value, path + "[*]"));
            }
            return out;
        }
        if (!path.isEmpty() && node != null) {
            out.put(path, String.valueOf(node));
        }
        return out;
    }
}
