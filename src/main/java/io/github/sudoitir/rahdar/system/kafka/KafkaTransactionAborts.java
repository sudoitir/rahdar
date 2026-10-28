package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Resolve;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.Resolution;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortResult;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Aborts a transaction another producer left open (ADR-0216): previewed, held for approval where a policy says so, audited. */
@Service
@RequiredArgsConstructor
public class KafkaTransactionAborts {

    /** The parameters of {@code transaction.abort}; the etag pins the producer and epoch the operator saw. */
    public record AbortParams(UUID clusterId, String transactionalId, String etag) {}

    private final KafkaTransactions transactions;
    private final BrokerGateway gateway;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    public AbortResult abort(UUID clusterId, String id, String etag) {
        KafkaEtags.require(etag, transactions.abortPreview(clusterId, id).etag());
        return gated(new AbortParams(clusterId, id, etag));
    }

    public Effect estimate(AbortParams p) {
        AbortPreview preview = transactions.abortPreview(p.clusterId(), p.transactionalId());
        return new Effect(preview.released().size(), "partitions", preview.etag(), preview.effect());
    }

    public void replay(AbortParams p) {
        run(p);
    }

    @Gated("transaction.abort")
    private AbortResult gated(AbortParams p) {
        return gate.run(Operation.of(p), () -> run(p));
    }

    private AbortResult run(AbortParams p) {
        AbortPreview preview = transactions.abortPreview(p.clusterId(), p.transactionalId());
        KafkaEtags.require(p.etag(), preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "ABORT_TRANSACTION",
                "TRANSACTION",
                p.transactionalId(),
                p.clusterId(),
                null,
                Map.of("state", preview.state(), "released", preview.released()),
                false);
        try {
            ClusterConnection connection = gateway.cluster(p.clusterId());
            var result = KafkaFailures.call(() -> connection
                    .operations()
                    .execute(
                            KafkaTransactions.idOf(p.transactionalId()),
                            new Resolve(Resolution.ROLLBACK),
                            Scope.whole(),
                            Precondition.ifMatch(p.etag()))
                    .orThrow());
            boolean aborted = !result.alreadyInState();
            audit.finish(event, false, aborted ? 1 : 0, null, Map.of("aborted", aborted));
            return new AbortResult(p.transactionalId(), aborted);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }
}
