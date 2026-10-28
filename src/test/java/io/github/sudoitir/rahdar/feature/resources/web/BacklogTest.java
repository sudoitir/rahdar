package io.github.sudoitir.rahdar.feature.resources.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Precision;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog.Accuracy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A backlog says how far to trust its figure, and an unknown one is never a zero (ADR-0216). */
class BacklogTest {

    private static Resource resource(Attributes attributes, Condition... conditions) {
        return new Resource(
                ResourceId.of("kafka.group-topic", "billing", "orders"),
                attributes,
                List.of(conditions),
                List.of(),
                Instant.now());
    }

    @Test
    void anExactMetricIsExact() {
        Backlog backlog = Backlog.of(resource(Attributes.builder()
                .put(MetricKeys.BACKLOG, MetricValue.exact(42))
                .build()));

        assertThat(backlog.accuracy()).isEqualTo(Accuracy.EXACT);
        assertThat(backlog.value()).isEqualTo(42);
        assertThat(backlog.reason()).isNull();
        assertThat(backlog.breakdown()).isFalse();
    }

    @Test
    void anEstimateCarriesTheReasonTheSystemGivesAndSaysItHasABreakdown() {
        MetricValue lag = new MetricValue(
                12400, Precision.ESTIMATE, Optional.empty(), Map.of(Shard.partition(PartitionId.of("0")), 12400.0));
        Condition why = new Condition(
                "kafka.backlog_estimate",
                ConditionStatus.TRUE,
                "ESTIMATE",
                "Read from committed offsets, at one moment.",
                Optional.empty());

        Backlog backlog = Backlog.of(
                resource(Attributes.builder().put(MetricKeys.BACKLOG, lag).build(), why));

        assertThat(backlog.accuracy()).isEqualTo(Accuracy.ESTIMATE);
        assertThat(backlog.value()).isEqualTo(12400);
        assertThat(backlog.reason()).isEqualTo("Read from committed offsets, at one moment.");
        assertThat(backlog.breakdown()).isTrue();
    }

    @Test
    void aResourceWithNoBacklogMetricIsUnavailableNotZero() {
        Backlog backlog = Backlog.of(resource(Attributes.empty()));

        assertThat(backlog.accuracy()).isEqualTo(Accuracy.UNAVAILABLE);
        assertThat(backlog.value()).isNull();
        assertThat(backlog.reason()).isNotBlank();
        assertThat(backlog.valueOrZero()).isZero();
    }
}
