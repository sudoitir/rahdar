package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity.Condition;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code 013-alerting.sql} against a real Postgres: the discriminated
 * {@code alert_rule} shape, the evaluator's index, and the dispatcher's
 * {@code FOR UPDATE SKIP LOCKED} claim query (ADR-0035, ADR-0036).
 */
class AlertSchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    NotificationChannelRepository channels;

    @Autowired
    AlertDeliveryRepository deliveries;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;
    private final List<UUID> createdChannels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
        createdChannels.forEach(channels::deleteById);
    }

    private NotificationChannelEntity channel(String namePrefix, String kind) {
        NotificationChannelEntity c =
                channels.save(new NotificationChannelEntity(namePrefix + "-" + UUID.randomUUID(), kind, "{}", null));
        createdChannels.add(c.getId());
        return c;
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    @Test
    void thresholdAndStateRulesBothPersistAndAreFilterableByKind() {
        UUID c = cluster();
        rules.save(AlertRuleEntity.threshold(
                c, "Deep queue", new Condition("openbroker.backlog", "GT", 1000.0), 60, "WARNING", null));
        rules.save(AlertRuleEntity.state(c, "Split-brain", "SPLIT_BRAIN", 0, "CRITICAL"));

        List<AlertRuleEntity> thresholds = rules.findByClusterIdAndKindAndEnabledTrue(c, "METRIC_THRESHOLD");
        List<AlertRuleEntity> states = rules.findByClusterIdAndKindAndEnabledTrue(c, "STATE");

        assertThat(thresholds).hasSize(1);
        assertThat(states).hasSize(1);
    }

    /** Every condition the API accepts is one the database accepts — CONFIG_DRIFT once was not. */
    @Test
    void everyStateConditionTheApiOffersPersists() {
        UUID c = cluster();
        for (String condition : List.of(
                "SPLIT_BRAIN",
                "NODE_DOWN",
                "REPLICATION_BEHIND",
                "CLUSTER_DEGRADED",
                "CLOCK_SKEW",
                "CONFIG_DRIFT",
                "SETUP_RISK")) {
            rules.save(AlertRuleEntity.state(c, "rule " + condition, condition, 0, "WARNING"));
        }
        assertThat(rules.findByClusterIdAndKindAndEnabledTrue(c, "STATE")).hasSize(7);
    }

    @Test
    void everyChannelKindPersists() {
        for (String kind : List.of("WEBHOOK", "SLACK", "EMAIL", "TEAMS", "PAGERDUTY")) {
            assertThat(channel("kind-" + kind, kind).getKind()).isEqualTo(kind);
        }
    }

    @Test
    void kindShapeCheckRejectsAThresholdRuleWithAStateCondition() {
        var params = Map.of("c", cluster());
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO alert_rule (cluster_id, kind, metric, comparator, threshold, state_condition, name)
                        VALUES (:c, 'METRIC_THRESHOLD', 'openbroker.backlog', 'GT', 1, 'SPLIT_BRAIN', 'bad')
                        """, params)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notificationChannelNameIsUnique() {
        String name = "ops-" + UUID.randomUUID();
        NotificationChannelEntity first = channels.save(new NotificationChannelEntity(name, "SLACK", "{}", null));
        createdChannels.add(first.getId());
        NotificationChannelEntity duplicate = new NotificationChannelEntity(name, "WEBHOOK", "{}", null);
        assertThatThrownBy(() -> channels.save(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimDueOnlyReturnsPendingDeliveriesAtOrBeforeNow() {
        UUID c = cluster();
        AlertRuleEntity rule = rules.save(AlertRuleEntity.state(c, "Split-brain", "SPLIT_BRAIN", 0, "CRITICAL"));
        NotificationChannelEntity channel = channel("ops", "SLACK");

        AlertDeliveryEntity due = deliveries.save(new AlertDeliveryEntity(rule.getId(), channel.getId(), "{}"));
        AlertDeliveryEntity notYetDue = deliveries.save(new AlertDeliveryEntity(rule.getId(), channel.getId(), "{}"));
        notYetDue.setNextAttemptAt(Instant.now().plusSeconds(600));
        deliveries.save(notYetDue);

        List<AlertDeliveryEntity> claimed = deliveries.claimDue(10);

        assertThat(claimed).extracting(AlertDeliveryEntity::getSeq).contains(due.getSeq());
        assertThat(claimed).extracting(AlertDeliveryEntity::getSeq).doesNotContain(notYetDue.getSeq());
    }
}
