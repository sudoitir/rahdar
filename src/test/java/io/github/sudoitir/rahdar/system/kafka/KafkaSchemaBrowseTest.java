package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.kafka.schema.DecodedRecord;
import io.github.sudoitir.openbroker.kafka.schema.SchemaCatalog;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.Subject;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessages;
import io.github.sudoitir.rahdar.platform.broker.SchemaDecoding;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Browse names a decoded record, a tombstone, a truncated body and an unknown schema, and never an empty stand-in. */
class KafkaSchemaBrowseTest {

    @Test
    void aDecodedRecordIsTheRecord() {
        SchemaCatalog catalog = mock(SchemaCatalog.class);
        when(catalog.decode(anyName(), anyBody()))
                .thenReturn(new DecodedRecord.Decoded(SchemaType.AVRO, 4, "orders-value", Map.of("id", 1)));

        DecodedRecordView view = new SchemaDecoding(catalog, "avro").value(MessageBody.of(new byte[] {1, 2}), false);

        assertThat(view.decoded()).isTrue();
        assertThat(view.schemaId()).isEqualTo(4);
        assertThat(view.record()).isEqualTo(Map.of("id", 1));
        assertThat(view.paths()).isNotEmpty();
    }

    @Test
    void anUnknownSchemaIsNotAnEmptyRecord() {
        SchemaCatalog catalog = mock(SchemaCatalog.class);
        when(catalog.decode(anyName(), anyBody()))
                .thenReturn(new DecodedRecord.Undecoded(DecodedRecord.SCHEMA_NOT_FOUND));

        DecodedRecordView view = new SchemaDecoding(catalog, null).value(MessageBody.of(new byte[] {1}), false);

        assertThat(view.reason()).isEqualTo("schema-not-found");
        assertThat(view.record()).isNull();
        assertThat(view.paths()).isNull();
    }

    @Test
    void aTombstoneIsNoSchemaId() {
        DecodedRecordView view =
                new SchemaDecoding(mock(SchemaCatalog.class), null).value(MessageBody.tombstone(), false);

        assertThat(view.reason()).isEqualTo("no-schema-id");
        assertThat(view.record()).isNull();
    }

    @Test
    void aTruncatedBodyIsNotDecoded() {
        DecodedRecordView view =
                new SchemaDecoding(mock(SchemaCatalog.class), "avro").value(MessageBody.of(new byte[] {1}), true);

        assertThat(view.reason()).isEqualTo("truncated");
        assertThat(view.record()).isNull();
    }

    @Test
    void browseFillsTheDecodedField() {
        SchemaCatalog catalog = mock(SchemaCatalog.class);
        when(catalog.decode(anyName(), anyBody()))
                .thenReturn(new DecodedRecord.Decoded(SchemaType.JSON, 2, "orders-value", Map.of("sku", "a")));
        Message message = new Message(
                new MessageRef(ResourceId.of("kafka.topic", "orders"), Shard.node(NodeId.of("n")), "1"),
                MessageHeaders.empty(),
                Map.of(),
                MessageBody.of("{\"sku\":\"a\"}".getBytes()),
                Set.of(),
                Map.of());

        BrowsedMessage browsed = BrowsedMessages.of(message, new SchemaDecoding(catalog, "json"));

        assertThat(browsed.decoded().subject()).isEqualTo("orders-value");
        assertThat(browsed.body()).contains("sku");
    }

    @Test
    void aSoftDeletedVersionStaysReadable() {
        var view = KafkaSchemaViews.subject(new Subject(
                "orders-value",
                List.of(new SubjectVersion(3, 1, SchemaType.AVRO, "{\"type\":\"record\"}", true)),
                Optional.empty(),
                Optional.of("deleted")));

        assertThat(view.versions()).hasSize(1);
        assertThat(view.versions().getFirst().deleted()).isTrue();
        assertThat(view.schemaText()).isEqualTo("{\"type\":\"record\"}");
    }

    private static String anyName() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static MessageBody anyBody() {
        return org.mockito.ArgumentMatchers.any();
    }
}
