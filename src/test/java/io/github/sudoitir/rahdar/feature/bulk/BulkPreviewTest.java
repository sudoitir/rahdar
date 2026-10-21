package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The preview freezes the set and states its blast radius from the aggregated snapshot (ADR-0093 D2). */
class BulkPreviewTest extends BulkTestSupport {

    @Test
    void theSetIsFrozenAtPreview() {
        queue(nodeA, "orders.a", 1, 0, false);
        queue(nodeA, "orders.b", 2, 0, false);
        queue(nodeA, "payments", 3, 0, false);

        BulkRunDetailView preview = preview(BulkOperation.PURGE, "orders");
        queue(nodeA, "orders.late", 4, 0, false);

        assertThat(names(bulk.get(clusterId, preview.run().id()))).containsExactly("orders.a", "orders.b");
        assertThat(preview.run().status()).isEqualTo(BulkRunStatus.PREVIEWED);
        assertThat(preview.run().estimate()).isEqualTo(3L);
        assertThat(preview.run().estimateComplete()).isTrue();
    }

    @Test
    void anUnknownFigureIsStatedAndTheTotalIsIncomplete() {
        queue(nodeA, "orders.a", 5, 0, false);
        queue(nodeB, "orders.a", 7, 0, false);
        stale(nodeB);

        BulkRunDetailView preview = preview(BulkOperation.DELETE, "orders");

        assertThat(preview.run().estimate()).isEqualTo(5L);
        assertThat(preview.run().estimateComplete()).isFalse();
        BulkItemView item = preview.items().getFirst();
        assertThat(item.nodes())
                .filteredOn(n -> n.nodeId().equals(nodeB))
                .singleElement()
                .satisfies(n -> assertThat(n.messageCount()).isNull());
        assertThat(item.warning()).contains("b").contains("unknown");
    }

    @Test
    void aRefusedQueueIsNamedAndLeftOutOfTheBlastRadius() {
        queue(nodeA, "orders.a", 5, 0, false);
        queue(nodeA, "orders.busy", 100, 2, false);

        BulkRunDetailView preview = preview(BulkOperation.DELETE, "orders");

        assertThat(preview.items())
                .filteredOn(i -> ResourceIds.nameOf(i.subscription()).equals("orders.busy"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.status()).isEqualTo(BulkItemStatus.REFUSED);
                    assertThat(i.error()).contains("2 consumers");
                });
        assertThat(preview.run().estimate()).isEqualTo(5L);
    }

    @Test
    void anExplicitNameThatNoLongerExistsIsRefused() {
        queue(nodeA, "orders.a", 5, 0, false);

        BulkRunDetailView preview = preview(BulkOperation.PAUSE, List.of("orders.a", "gone"));

        assertThat(preview.items())
                .extracting(i -> ResourceIds.nameOf(i.subscription()))
                .containsExactly("orders.a", "gone");
        assertThat(preview.items().get(1).status()).isEqualTo(BulkItemStatus.REFUSED);
    }

    @Test
    void moreQueuesThanTheQueueCapIsRefusedWithTheNumbers() {
        settings.put(BrokerSettings.BULK_SUBSCRIPTION_CAP, "2");
        queue(nodeA, "orders.a", 0, 0, false);
        queue(nodeA, "orders.b", 0, 0, false);
        queue(nodeA, "orders.c", 0, 0, false);

        assertThatThrownBy(() -> preview(BulkOperation.PAUSE, "orders"))
                .isInstanceOf(BulkRefusedException.class)
                .hasMessageContaining("3 subscriptions matched")
                .hasMessageContaining("capped at 2");
    }

    @Test
    void thePlanHashIsStableForTheSamePlanAndChangesWithIt() {
        queue(nodeA, "orders.a", 0, 0, false);
        queue(nodeA, "orders.b", 0, 0, false);

        String first = preview(BulkOperation.DELETE, "orders").run().planHash();
        String again = preview(BulkOperation.DELETE, "orders").run().planHash();
        String otherOperation = preview(BulkOperation.PURGE, "orders").run().planHash();
        String otherOptions = bulk.preview(
                        clusterId, new BulkPreviewRequest(BulkOperation.DELETE, null, "orders", true))
                .run()
                .planHash();

        assertThat(again).isEqualTo(first);
        assertThat(otherOperation).isNotEqualTo(first);
        assertThat(otherOptions).isNotEqualTo(first);
    }

    private static List<String> names(BulkRunDetailView run) {
        return run.items().stream()
                .map(i -> ResourceIds.nameOf(i.subscription()))
                .toList();
    }
}
