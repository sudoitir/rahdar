package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity.Condition;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistered;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Rules a Kafka or RabbitMQ cluster cannot answer are refused, and each system is seeded with the rules it can. */
class SystemRulesTest {

    private static AlertRuleEntity state(String condition) {
        return AlertRuleEntity.state(null, "r", condition, 0, "WARNING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "rabbitmq"})
    void anArtemisOnlyConditionIsRefusedOnAnotherSystemNamingIt(String system) {
        assertThatThrownBy(() -> SystemRules.requireOn(system, state("SPLIT_BRAIN")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SPLIT_BRAIN")
                .hasMessageContaining(system.equals("kafka") ? "Kafka" : "RabbitMQ")
                .hasMessageContaining("CLUSTER_DEGRADED");
        assertThatThrownBy(() -> SystemRules.requireOn(
                        system,
                        AlertRuleEntity.threshold(
                                null,
                                "r",
                                new Condition(SlowConsumerCondition.METRIC, "LT", 1),
                                0,
                                "WARNING",
                                "QUEUE")))
                .isInstanceOf(ConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "rabbitmq", "artemis"})
    void healthAndBacklogRulesWatchEverySystem(String system) {
        assertThatCode(() -> SystemRules.requireOn(system, state("CLUSTER_DEGRADED")))
                .doesNotThrowAnyException();
        assertThatCode(() -> SystemRules.requireOn(
                        system,
                        AlertRuleEntity.threshold(
                                null, "r", new Condition("BACKLOG", "GT", 1000), 0, "WARNING", "QUEUE")))
                .doesNotThrowAnyException();
    }

    @Test
    void artemisKeepsItsHaRules() {
        assertThatCode(() -> SystemRules.requireOn("artemis", state("SPLIT_BRAIN")))
                .doesNotThrowAnyException();
    }

    private static List<String> seeded(String system) {
        AlertRuleRepository rules = mock(AlertRuleRepository.class);
        ClusterKinds kinds = mock(ClusterKinds.class);
        UUID clusterId = UUID.randomUUID();
        when(kinds.systemId(clusterId)).thenReturn(system);
        new BuiltinAlertRules(rules, kinds).onClusterRegistered(new ClusterRegistered(clusterId));
        ArgumentCaptor<AlertRuleEntity> saved = ArgumentCaptor.forClass(AlertRuleEntity.class);
        verify(rules, atLeastOnce()).save(saved.capture());
        List<String> out = new ArrayList<>();
        saved.getAllValues().forEach(r -> out.add(r.getStateCondition()));
        return out;
    }

    @Test
    void anArtemisClusterIsSeededWithItsHaRules() {
        assertThat(seeded("artemis"))
                .containsExactlyInAnyOrder("SPLIT_BRAIN", "CLOCK_SKEW", "NODE_DOWN", "REPLICATION_BEHIND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "rabbitmq"})
    void aKafkaOrRabbitMqClusterIsSeededWithItsHealthOnly(String system) {
        assertThat(seeded(system)).containsExactly("CLUSTER_DEGRADED");
    }
}
