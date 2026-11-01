package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A queue row says how far its backlog can be trusted, and why when it is only an estimate (ADR-0224). */
class QueueRowTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private static QueueRow row(MetricValue backlog, Condition... conditions) {
        Resource queue = new Resource(
                ResourceId.of("rabbitmq.classic-queue", "orders"),
                Attributes.builder().put(MetricKeys.BACKLOG, backlog).build(),
                List.of(conditions),
                List.of(),
                Instant.now());
        return QueueRow.of(CLUSTER, NODE, queue);
    }

    @Test
    void anEstimateWithNoConditionSaysItsCountersAreSampled() {
        QueueRow row = row(MetricValue.estimate(40));

        assertThat(row.messageCount()).isEqualTo(40L);
        assertThat(row.attributes())
                .containsEntry(QueueRow.BACKLOG_PRECISION, "ESTIMATE")
                .containsEntry(QueueRow.BACKLOG_REASON, "The system's counters are sampled, so this is an estimate.");
    }

    @Test
    void anEstimateKeepsTheReasonTheSystemGives() {
        Condition why = new Condition(
                "kafka.backlog_estimate", ConditionStatus.TRUE, "ESTIMATE", "Read at one moment.", Optional.empty());

        QueueRow row = row(MetricValue.estimate(40), why);

        assertThat(row.attributes()).containsEntry(QueueRow.BACKLOG_REASON, "Read at one moment.");
    }

    @Test
    void anExactBacklogNeedsNoReason() {
        QueueRow row = row(MetricValue.exact(40));

        assertThat(row.attributes()).doesNotContainKeys(QueueRow.BACKLOG_PRECISION, QueueRow.BACKLOG_REASON);
    }

    @Test
    void aRowWithoutBacklogHasNoCountAndSaysWhy() {
        QueueRow row = row(MetricValue.estimate(40)).withoutBacklog("A stream keeps its messages.");

        assertThat(row.messageCount()).isNull();
        assertThat(row.backlogKnown()).isFalse();
        assertThat(row.attributes())
                .containsEntry(QueueRow.BACKLOG_REASON, "A stream keeps its messages.")
                .doesNotContainKey(QueueRow.BACKLOG_PRECISION);
        assertThat(row.queueName()).isEqualTo("orders");
    }
}
