package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.SchemaBindingLookup;
import io.github.sudoitir.rahdar.platform.broker.SchemaDecoder;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Decodes a browsed record through the cluster's schema catalog. A system with no catalog leaves the field empty;
 * it is not an error, and the connection is unwrapped only here.
 */
@Service
@RequiredArgsConstructor
public class KafkaRecordDecoder implements SchemaDecoder {

    private final BrokerGateway gateway;
    private final SchemaBindingLookup bindings;

    @Override
    public Sides open(UUID clusterId, String topic) {
        SchemaCatalog catalog =
                gateway.cluster(clusterId).unwrap(SchemaCatalog.class).orElse(null);
        if (catalog == null) {
            return Sides.none();
        }
        SchemaDecoding values = new SchemaDecoding(catalog, bindings.serde(clusterId, topic, "value"));
        SchemaDecoding keys = new SchemaDecoding(catalog, bindings.serde(clusterId, topic, "key"));
        return new Sides(values, (body, _) -> keys.key(body.bytes().orElse(null)));
    }
}
