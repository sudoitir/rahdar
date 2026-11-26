package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import java.util.UUID;

/**
 * Decodes a browsed record where the system's schema catalog lives. No bean, or a system with no catalog, leaves
 * the decoded field empty.
 */
public interface SchemaDecoder {

    /** Decoders for one topic's value and key. Both are no-ops when this system has no catalog. */
    Sides open(UUID clusterId, String topic);

    /** One side of a record, from the body this browse actually kept. */
    @FunctionalInterface
    interface RecordDecoder {
        DecodedRecordView value(MessageBody body, boolean truncated);

        static RecordDecoder none() {
            return (body, _) -> null;
        }
    }

    /** The value and key decoders for one topic. */
    record Sides(RecordDecoder values, RecordDecoder keys) {
        public static Sides none() {
            return new Sides(RecordDecoder.none(), RecordDecoder.none());
        }
    }

    static SchemaDecoder none() {
        return (_, _) -> Sides.none();
    }
}
