package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A live SQL row keeps the decoded view browse already filled. The index does not. */
class SqlDecodedRowTest {

    @Test
    void aBrokerRowCarriesTheDecodedViewIntoThePolicyContent() {
        DecodedRecordView decoded = new DecodedRecordView(
                "JSON", 2, "orders-value", Map.of("sku", "a"), null, "{\"sku\":\"a\"}", Map.of("sku", "a"));
        Row row = new Row(
                UUID.randomUUID(),
                "n",
                "sub",
                "orders",
                1L,
                0,
                true,
                4,
                0L,
                0L,
                8L,
                null,
                null,
                null,
                null,
                null,
                "{\"sku\":\"a\"}",
                false,
                false,
                null,
                Map.of(),
                Source.BROKER,
                null,
                null,
                null,
                null,
                "kafka.topic/orders",
                decoded,
                null);

        MessageContent content = SqlGovernance.content(row);

        assertThat(content.decoded()).isSameAs(decoded);
        assertThat(content.decoded().subject()).isEqualTo("orders-value");
    }
}
