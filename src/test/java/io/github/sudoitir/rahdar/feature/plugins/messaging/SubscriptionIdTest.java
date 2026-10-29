package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A subscription id is the shared API's canonical id, validated by the SDK. */
class SubscriptionIdTest {

    @Test
    void ofWritesTheCanonicalIdAndParseReadsItBack() {
        SubscriptionId queue = SubscriptionId.of("artemis.queue", "orders");

        assertThat(queue.id()).isEqualTo("artemis.queue/orders");
        assertThat(queue.kind()).isEqualTo("artemis.queue");
        assertThat(queue.segments()).containsExactly("orders");
        assertThat(SubscriptionId.parse(queue.id())).isEqualTo(queue);
    }

    @Test
    void aPathOfSeveralSegmentsKeepsThemOutermostFirst() {
        SubscriptionId group = SubscriptionId.of("kafka.group", "billing", "payments");

        assertThat(group.segments()).containsExactly("billing", "payments");
        assertThat(SubscriptionId.parse(group.id())).isEqualTo(group);
    }

    @Test
    void aSegmentWithASlashSurvivesTheRoundTrip() {
        SubscriptionId queue = SubscriptionId.of("artemis.queue", "a/b");

        assertThat(queue.segments()).containsExactly("a/b");
        assertThat(SubscriptionId.parse(queue.id()).segments()).containsExactly("a/b");
    }

    @Test
    void anIdWithoutAKindOrPathIsRefused() {
        assertThatThrownBy(() -> new SubscriptionId(" ", "orders")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionId("artemis.queue", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionId.parse("orders")).isInstanceOf(IllegalArgumentException.class);
    }
}
