package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Create;
import io.github.sudoitir.openbroker.operation.Operation.Delete;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Adds and removes ACLs (ADR-0216): after a preview, held for approval where a policy says so, and audited. */
@Service
@RequiredArgsConstructor
public class KafkaAclChanges {

    /** The parameters of {@code access-rule.change}; the etag pins the ACLs the operator saw. */
    public record AclChangeParams(UUID clusterId, List<Acl> add, List<Acl> remove, String etag) {}

    private final KafkaAcls acls;
    private final BrokerGateway gateway;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    public AclResult apply(UUID clusterId, AclChangeRequest request) {
        AclPreview preview = acls.preview(clusterId, request);
        KafkaEtags.require(request.etag(), preview.etag());
        return gated(new AclChangeParams(
                clusterId,
                KafkaAcls.normalised(request.add()),
                KafkaAcls.normalised(request.remove()),
                request.etag()));
    }

    public Effect estimate(AclChangeParams p) {
        AclPreview preview = acls.preview(p.clusterId(), request(p));
        return new Effect(
                preview.added() + preview.removed(), "ACLs", preview.etag(), String.join(" ", preview.losses()));
    }

    public void replay(AclChangeParams p) {
        run(p);
    }

    @Gated("access-rule.change")
    private AclResult gated(AclChangeParams p) {
        return gate.run(Operation.of(p), () -> run(p));
    }

    private AclResult run(AclChangeParams p) {
        AclPreview preview = acls.preview(p.clusterId(), request(p));
        KafkaEtags.require(p.etag(), preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "CHANGE_ACLS",
                "ACCESS_RULE",
                "acls",
                p.clusterId(),
                null,
                Map.of("add", p.add(), "remove", p.remove(), "access", preview.access()),
                false);
        try {
            ClusterConnection connection = gateway.cluster(p.clusterId());
            // Rules are added before others are removed, so a replacement never leaves a gap in access.
            for (Acl a : p.add()) {
                KafkaFailures.call(() -> connection
                        .operations()
                        .execute(KafkaAcls.id(a), new Create(Attributes.empty()), Scope.whole())
                        .orThrow());
            }
            for (Acl a : p.remove()) {
                KafkaFailures.call(() -> connection
                        .operations()
                        .execute(KafkaAcls.id(a), new Delete(false, false), Scope.whole())
                        .orThrow());
            }
            audit.finish(event, false, preview.added() + preview.removed(), null, Map.of("access", preview.access()));
            return new AclResult(preview.added(), preview.removed());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private static AclChangeRequest request(AclChangeParams p) {
        return new AclChangeRequest(p.add(), p.remove(), p.etag());
    }
}
