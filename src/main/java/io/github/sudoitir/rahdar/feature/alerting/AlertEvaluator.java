package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.Transition;
import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleChannelRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateRepository;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates one cluster's rules of one kind (design.md decision 3) — called
 * by {@link AlertScrapeListener} right after the scrape tier that kind's data
 * source depends on has persisted, never from an independent timer. DB-only:
 * reads already-persisted {@code subscription_snapshot}/{@code metric_sample}/HA state
 * and writes {@code alert_state}/{@code alert_firing}/{@code alert_delivery} —
 * no broker I/O, preserving ADR-0015's rule that network calls never share a
 * transaction with persistence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertEvaluator {

    private final AlertRuleRepository rules;
    private final AlertStateRepository states;
    private final AlertFiringRepository firings;
    private final AlertDeliveryRepository deliveries;
    private final AlertRuleChannelRepository ruleChannels;
    /**
     * Every rule kind's predicate, in {@code @Order} sequence — derived metrics ahead of
     * the raw gauge and rate conditions, which match on broader sets. Injected as a list
     * rather than named one by one so a module that may depend on alerting can contribute
     * a kind without this class knowing its type (ADR-0089).
     */
    private final List<AlertCondition> conditions;

    /** The state of Rahdar itself that installation-scoped rules read (ADR-0135). */
    private final List<InstallationSignalSource> installationSignals;

    private final ClusterDirectory clusters;
    private final SseHub hub;
    private final AlertPayloads payloads;

    @Transactional
    public void evaluate(UUID clusterId, String kind) {
        boolean anyTransition = false;
        for (AlertRuleEntity rule : rules.findByClusterIdAndKindAndEnabledTrue(clusterId, kind)) {
            AlertRuleSpec spec = specOf(rule);
            AlertCondition condition = conditionFor(spec);
            if (condition != null) {
                anyTransition |= advance(rule, condition.evaluate(clusterId, spec));
            }
        }
        if (anyTransition) {
            hub.publish(clusterId, "alerts");
        }
    }

    /**
     * Evaluates the enabled installation-scoped rules of one kind (ADR-0135), through the same
     * debounce, history and delivery as a cluster's, with no cluster anywhere. Called when fresh
     * storage numbers exist, not per cluster, so a condition fires once however many clusters
     * there are.
     */
    @Transactional
    public void evaluateInstallation(String kind) {
        boolean anyTransition = false;
        for (AlertRuleEntity rule : rules.findByClusterIdIsNullAndKindAndEnabledTrue(kind)) {
            InstallationSignalSource source = installationSignals.stream()
                    .filter(s -> s.condition().equals(rule.getStateCondition()))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                log.warn("Installation alert rule {} has an unrecognised condition; skipping", rule.getId());
                continue;
            }
            anyTransition |= advance(rule, source.evaluate());
        }
        if (anyTransition) {
            // The stream is per cluster and an installation alert shows in every cluster's view.
            clusters.clusters().forEach(c -> hub.publish(c.getId(), "alerts"));
        }
    }

    /** Runs one rule's evaluation through state, history and delivery; whether anything changed. */
    private boolean advance(AlertRuleEntity rule, Evaluation evaluation) {
        List<Transition> transitions = process(rule, evaluation);
        if (transitions.isEmpty()) {
            return false;
        }
        enqueueDelivery(rule, transitions);
        return true;
    }

    private AlertCondition conditionFor(AlertRuleSpec rule) {
        for (AlertCondition condition : conditions) {
            if (condition.supports(rule)) {
                return condition;
            }
        }
        log.warn("Alert rule {} has an unrecognised metric '{}'; skipping", rule.id(), rule.metric());
        return null;
    }

    /** The exported projection a condition evaluates, so none of them sees the entity. */
    static AlertRuleSpec specOf(AlertRuleEntity rule) {
        return new AlertRuleSpec(
                rule.getId(),
                rule.isThreshold(),
                rule.getMetric(),
                rule.getComparator(),
                rule.getThreshold(),
                rule.getScope(),
                rule.getStateCondition());
    }

    private List<Transition> process(AlertRuleEntity rule, Evaluation evaluation) {
        Instant now = Instant.now();
        Map<String, AlertStateEntity> bySubject = new HashMap<>();
        for (AlertStateEntity s : states.findByRuleId(rule.getId())) {
            bySubject.put(s.getSubjectKey(), s);
        }

        List<Transition> transitions = new ArrayList<>();
        for (String subject : evaluation.universe()) {
            AlertStateEntity state = bySubject.remove(subject);
            boolean isNew = state == null;
            if (isNew) {
                state = new AlertStateEntity(rule.getId(), subject);
            }
            boolean active = evaluation.active().containsKey(subject);
            Double value = evaluation.active().get(subject);

            Transition t = AlertStateMachine.advance(state, active, value, rule.getForSeconds(), now);
            if (!"OK".equals(state.getState())) {
                states.save(state);
            } else if (!isNew) {
                states.delete(state);
            }
            if (t != null) {
                transitions.add(t);
                recordHistory(rule, t, now);
            }
        }

        // Subjects with a tracked state that vanished from this tick's universe
        // (e.g. a deleted queue) resolve rather than being left stuck forever.
        for (AlertStateEntity orphan : bySubject.values()) {
            if ("FIRING".equals(orphan.getState())) {
                Transition t = new Transition(orphan.getSubjectKey(), TransitionKind.RESOLVED, orphan.getLastValue());
                transitions.add(t);
                recordHistory(rule, t, now);
            }
            states.delete(orphan);
        }
        return transitions;
    }

    private void recordHistory(AlertRuleEntity rule, Transition t, Instant now) {
        if (t.kind() == TransitionKind.FIRED) {
            firings.save(new AlertFiringEntity(
                    rule.getClusterId(), rule.getId(), t.subjectKey(), rule.getSeverity(), t.value(), now));
        } else {
            firings.findFirstByRuleIdAndSubjectKeyAndResolvedAtIsNull(rule.getId(), t.subjectKey())
                    .ifPresent(f -> {
                        f.resolve(now);
                        firings.save(f);
                    });
        }
    }

    /** One delivery row per bound channel, carrying every transition this tick produced for the rule. */
    private void enqueueDelivery(AlertRuleEntity rule, List<Transition> transitions) {
        List<UUID> channelIds = ruleChannels.findByRuleId(rule.getId()).stream()
                .map(rc -> rc.getChannelId())
                .toList();
        if (channelIds.isEmpty()) {
            return;
        }
        String json = payloads.build(
                rule.getId(),
                rule.getName(),
                rule.getSeverity(),
                rule.getClusterId(),
                transitions.stream().map(AlertPayloads.Item::of).toList(),
                Instant.now());
        for (UUID channelId : channelIds) {
            deliveries.save(new AlertDeliveryEntity(rule.getId(), channelId, json));
        }
    }
}
