package io.github.sudoitir.rahdar.platform.governance;

import io.github.sudoitir.rahdar.kernel.replica.BusResumed;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.platform.governance.internal.persistence.GovernanceRuleEntity;
import io.github.sudoitir.rahdar.platform.governance.internal.persistence.GovernanceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Holds the current {@link PolicySnapshot}. A rule write drops it after commit on this replica, so the
 * writer reads its own change, and every other replica drops it on the {@code policy} signal the write
 * sent in the same transaction.
 */
@Component
@RequiredArgsConstructor
class PolicyStore {

    /** Published by a rule write, inside its transaction. */
    record PolicyChanged() {}

    private final GovernanceRuleRepository rules;
    private volatile PolicySnapshot current;

    PolicySnapshot current() {
        PolicySnapshot snapshot = current;
        return snapshot != null ? snapshot : reload();
    }

    synchronized PolicySnapshot reload() {
        current = PolicySnapshot.of(
                rules.policyVersion(),
                rules.findAll().stream().map(PolicyStore::toRule).toList());
        return current;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChange(PolicyChanged ignored) {
        current = null;
    }

    @EventListener(condition = "#signal.kind() == 'policy'")
    void onSignal(ReplicaSignal signal) {
        current = null;
    }

    /** The bus was down: a rule may have changed in the gap. */
    @EventListener
    void onBusResumed(BusResumed resumed) {
        current = null;
    }

    static Rule toRule(GovernanceRuleEntity e) {
        return new Rule(
                e.getId(),
                e.getDestinationPattern(),
                RuleTarget.valueOf(e.getTarget()),
                e.getSelector(),
                DataClass.valueOf(e.getDataClass()),
                e.getAction() == null ? null : Action.valueOf(e.getAction()),
                e.isBuiltin(),
                e.isEnabled(),
                e.isException());
    }
}
