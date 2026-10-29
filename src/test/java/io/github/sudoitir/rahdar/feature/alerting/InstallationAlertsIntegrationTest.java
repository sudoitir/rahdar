package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleChannelRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateRepository;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertRuleRequest;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertRuleView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleService;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleService.StoreState;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.RegisteredStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.StorageHealthService;
import io.github.sudoitir.rahdar.kernel.lifecycle.StorageHealthService.TableHealth;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreUsage;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Installation-scoped alert rules (ADR-0135): the two storage rules exist once whatever the
 * clusters, a store over its quota warning fires one alert that every cluster's view shows, and
 * disabling the rule silences it. The lifecycle's numbers are stubbed; what they are is
 * {@code LifecycleService}'s and {@code StorageHealthService}'s own tests' business.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class InstallationAlertsIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    LifecycleService lifecycle;

    @MockitoBean
    StorageHealthService health;

    @Autowired
    AlertEvaluator evaluator;

    @Autowired
    AlertRuleService ruleService;

    @Autowired
    AlertService alertService;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    AlertRuleChannelRepository ruleChannels;

    @Autowired
    AlertStateRepository states;

    @Autowired
    AlertFiringRepository firings;

    @Autowired
    ClusterRepository clusters;

    @AfterEach
    void restore() {
        for (AlertRuleEntity rule : installationRules()) {
            firings.deleteAll(firings.findAll().stream()
                    .filter(f -> f.getRuleId().equals(rule.getId()))
                    .toList());
            states.deleteAll(states.findByRuleId(rule.getId()));
            rule.setEnabled(true);
            rules.save(rule);
        }
    }

    private List<AlertRuleEntity> installationRules() {
        return rules.findAll().stream().filter(r -> r.getClusterId() == null).toList();
    }

    private AlertRuleEntity rule(String condition) {
        return installationRules().stream()
                .filter(r -> condition.equals(r.getStateCondition()))
                .findFirst()
                .orElseThrow();
    }

    private List<AlertFiringEntity> firingsOf(AlertRuleEntity rule) {
        return firings.findAll().stream()
                .filter(f -> f.getRuleId().equals(rule.getId()))
                .toList();
    }

    /** A store with a 10 MiB quota (warning at 80%) using {@code mib} MiB. */
    private static StoreState store(String id, long mib) {
        ManagedStore managed = mock(ManagedStore.class);
        when(managed.def()).thenReturn(new StoreDef(id, id, List.of(), StoreDef.QuotaUnit.BYTES, null, null, null));
        return new StoreState(
                new RegisteredStore(id, RegisteredStore.CORE, managed, null),
                "forever",
                10,
                80,
                new StoreUsage(0, mib * 1024 * 1024),
                null,
                null);
    }

    /** Stubs the lifecycle after the stores are built, since building one stubs its own mock. */
    private void stores(List<StoreState> states) {
        when(lifecycle.states()).thenReturn(states);
    }

    private static TableHealth table(String name, int deadPercent, List<String> problems) {
        return new TableHealth("public", name, 0, 0, deadPercent, null, 0, null, false, List.of(), problems);
    }

    @Test
    void theInstallationRulesExistOnceForTheInstallationWhateverTheClusters() {
        assertThat(installationRules())
                .extracting(AlertRuleEntity::getStateCondition)
                .containsExactlyInAnyOrder("STORAGE_QUOTA", "STORAGE_HEALTH", "AUDIT_DELIVERY");
        assertThat(installationRules()).allSatisfy(r -> {
            assertThat(r.getKind()).isEqualTo("STATE");
            assertThat(r.getSeverity()).isEqualTo("WARNING");
            assertThat(r.isEnabled()).isTrue();
            assertThat(ruleChannels.findByRuleId(r.getId())).isEmpty();
        });
    }

    @Test
    void aStoreOverItsQuotaWarningFiresOneAlertAndResolvesBelowIt() {
        AlertRuleEntity quota = rule("STORAGE_QUOTA");
        stores(List.of(store("audit", 9), store("events", 1)));

        evaluator.evaluateInstallation("STATE");
        evaluator.evaluateInstallation("STATE");

        assertThat(firingsOf(quota)).singleElement().satisfies(f -> {
            assertThat(f.getClusterId()).isNull();
            assertThat(f.getSubjectKey()).isEqualTo("audit");
            assertThat(f.getValue()).isEqualTo(90.0);
            assertThat(f.getResolvedAt()).isNull();
        });

        stores(List.of(store("audit", 2), store("events", 1)));
        evaluator.evaluateInstallation("STATE");

        // A state alert holds its resolution. The first clear reading starts the hold and does not resolve.
        assertThat(firingsOf(quota))
                .singleElement()
                .satisfies(f -> assertThat(f.getResolvedAt()).isNull());

        AlertStateEntity held = states.findByRuleId(quota.getId()).getFirst();
        held.setSince(Instant.now().minusSeconds(AlertStateMachine.STATE_RESOLVE_SECONDS));
        states.saveAndFlush(held);
        evaluator.evaluateInstallation("STATE");

        assertThat(firingsOf(quota))
                .singleElement()
                .satisfies(f -> assertThat(f.getResolvedAt()).isNotNull());
    }

    @Test
    void anUnhealthyTableFiresTheHealthRule() {
        AlertRuleEntity rule = rule("STORAGE_HEALTH");
        when(health.health())
                .thenReturn(List.of(
                        table("audit_event", 35, List.of("35% of its rows are dead")),
                        table("app_user", 0, List.of()),
                        table("message_index", 0, List.of("no partition for 2026-10-01"))));

        evaluator.evaluateInstallation("STATE");

        assertThat(firingsOf(rule))
                .extracting(AlertFiringEntity::getSubjectKey, AlertFiringEntity::getValue)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("public.audit_event", 35.0), Tuple.tuple("public.message_index", 1.0));
    }

    @Test
    void aDisabledRuleStopsFiring() {
        AlertRuleEntity quota = rule("STORAGE_QUOTA");
        quota.setEnabled(false);
        rules.save(quota);
        stores(List.of(store("audit", 9)));

        evaluator.evaluateInstallation("STATE");

        assertThat(firingsOf(quota)).isEmpty();
    }

    @Test
    void everyClustersViewShowsTheInstallationRulesAndFiringsAndCanEditThem() {
        UUID clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        try {
            AlertRuleEntity quota = rule("STORAGE_QUOTA");
            stores(List.of(store("audit", 9)));
            evaluator.evaluateInstallation("STATE");

            assertThat(ruleService.list(clusterId))
                    .filteredOn(r -> r.clusterId() == null)
                    .extracting(AlertRuleView::stateCondition)
                    .containsExactlyInAnyOrder("STORAGE_QUOTA", "STORAGE_HEALTH", "AUDIT_DELIVERY");
            assertThat(alertService.firingNow(clusterId)).singleElement().satisfies(f -> {
                assertThat(f.clusterId()).isNull();
                assertThat(f.ruleName()).isEqualTo(quota.getName());
            });
            assertThat(alertService
                            .history(clusterId, ResourceQuery.ofPage(1, 50), null)
                            .data())
                    .hasSize(1);

            AlertRuleView edited = ruleService.update(
                    clusterId,
                    quota.getId(),
                    new AlertRuleRequest(
                            quota.getName(),
                            "STATE",
                            null,
                            null,
                            null,
                            "STORAGE_QUOTA",
                            0,
                            "CRITICAL",
                            null,
                            true,
                            List.of()));
            assertThat(edited.clusterId()).isNull();
            assertThat(edited.severity()).isEqualTo("CRITICAL");

            UUID quotaId = quota.getId();
            AlertRuleRequest nodeDown = new AlertRuleRequest(
                    quota.getName(), "STATE", null, null, null, "NODE_DOWN", 0, "WARNING", null, true, List.of());
            assertThatThrownBy(() -> ruleService.update(clusterId, quotaId, nodeDown))
                    .isInstanceOf(IllegalArgumentException.class);
            AlertRuleRequest storageHealth = new AlertRuleRequest(
                    "Mine", "STATE", null, null, null, "STORAGE_HEALTH", 0, "WARNING", null, true, List.of());
            assertThatThrownBy(() -> ruleService.create(clusterId, storageHealth))
                    .isInstanceOf(IllegalArgumentException.class);

            ruleService.update(
                    clusterId,
                    quota.getId(),
                    new AlertRuleRequest(
                            quota.getName(),
                            "STATE",
                            null,
                            null,
                            null,
                            "STORAGE_QUOTA",
                            0,
                            "WARNING",
                            null,
                            true,
                            List.of()));
        } finally {
            clusters.deleteById(clusterId);
        }
    }
}
