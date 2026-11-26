package io.github.sudoitir.rahdar.kernel.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * A payload that carried a schema id, or why it did not. An undecoded payload has a reason and no record: never an
 * empty map standing in for a tombstone or a missing schema. Null fields are omitted: the contract types each one as
 * non-null when it is present.
 *
 * @param serialized the record as JSON, so a reader can show it without a second pass
 * @param paths the record's fields by dotted path, so a body policy masks each one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecodedRecordView(
        String type,
        Integer schemaId,
        String subject,
        Object record,
        String reason,
        String serialized,
        Map<String, String> paths) {

    public static DecodedRecordView undecoded(String reason) {
        return new DecodedRecordView(null, null, null, null, reason, null, null);
    }

    public boolean decoded() {
        return reason == null;
    }
}
