package io.github.sudoitir.rahdar.platform.governance;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.governance.internal.persistence.ClassificationFindingEntity;
import io.github.sudoitir.rahdar.platform.governance.internal.persistence.ClassificationFindingRepository;
import io.github.sudoitir.rahdar.platform.governance.internal.persistence.GovernanceRuleEntity;
import io.github.sudoitir.rahdar.platform.governance.web.FindingViews.FindingView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The classification inbox (data-governance spec). Confirming a finding turns it into a rule for that field and
 * class; dismissing it records an exception so the detector stops masking that class there. Both are audited in
 * the same transaction, and neither ever sees a detected value — findings never held one.
 */
@Service
@RequiredArgsConstructor
public class FindingsService {

    static final String TARGET_TYPE = "CLASSIFICATION_FINDING";

    private final ClassificationFindingRepository findings;
    private final GovernanceRuleService rules;
    private final AuditService audit;
    private final ActorResolver actors;

    /** Findings with {@code status}, newest first; every finding when {@code status} is null. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.platform.governance.GovernancePermissions).GOVERNANCE_READ)")
    @Transactional(readOnly = true)
    public List<FindingView> list(String status) {
        List<ClassificationFindingEntity> rows = status == null
                ? findings.findAllByOrderByLastSeenAtDesc()
                : findings.findByStatusOrderByLastSeenAtDesc(status);
        return rows.stream().map(FindingsService::toView).toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.platform.governance.GovernancePermissions).GOVERNANCE_WRITE)")
    @Transactional
    public FindingView confirm(UUID id) {
        return decide(id, "CONFIRMED", "CONFIRM_FINDING", false);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.platform.governance.GovernancePermissions).GOVERNANCE_WRITE)")
    @Transactional
    public FindingView dismiss(UUID id) {
        return decide(id, "DISMISSED", "DISMISS_FINDING", true);
    }

    private FindingView decide(UUID id, String status, String action, boolean exception) {
        ClassificationFindingEntity finding =
                findings.findById(id).orElseThrow(() -> new NotFoundException("classification finding", id));
        if (!"OPEN".equals(finding.getStatus())) {
            throw new ConflictException(
                    "finding-already-decided",
                    "This finding was already " + finding.getStatus().toLowerCase() + ". Change the rule instead.");
        }
        AuditEvent event = audit.begin(
                actors.resolve(),
                action,
                TARGET_TYPE,
                finding.getDestination() + " " + finding.getLocation() + ":" + finding.getFieldPath(),
                null,
                null,
                Map.of(
                        "destination", finding.getDestination(),
                        "location", finding.getLocation(),
                        "fieldPath", finding.getFieldPath(),
                        "dataClass", finding.getDataClass()),
                false);
        rules.createUnaudited(new GovernanceRuleEntity(
                finding.getDestination(),
                targetOf(finding.getLocation()),
                finding.getFieldPath(),
                finding.getDataClass(),
                exception ? Action.CLEAR.name() : null,
                exception));
        finding.setStatus(status);
        audit.succeed(event, 1);
        return toView(findings.save(finding));
    }

    private static String targetOf(String location) {
        return switch (Location.valueOf(location)) {
            case HEADER -> RuleTarget.HEADER.name();
            case PROPERTY -> RuleTarget.PROPERTY.name();
            case BODY -> RuleTarget.BODY_PATH.name();
        };
    }

    static FindingView toView(ClassificationFindingEntity e) {
        return new FindingView(
                e.getId(),
                e.getDestination(),
                e.getLocation(),
                e.getFieldPath(),
                e.getDataClass(),
                DataClass.valueOf(e.getDataClass()).label(),
                e.getStatus(),
                e.getHitCount(),
                e.getFirstSeenAt(),
                e.getLastSeenAt());
    }
}
