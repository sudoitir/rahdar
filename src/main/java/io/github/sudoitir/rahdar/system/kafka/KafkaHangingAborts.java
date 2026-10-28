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
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortResult;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Aborts a hanging transaction on one partition (KIP-664, ADR-0216): previewed, held for approval where a policy
 * says so, and audited before and after. The etag pins the start offset and producer epoch the operator saw, so a
 * transaction that closed or moved since is refused as stale.
 */
@Service
@RequiredArgsConstructor
public class KafkaHangingAborts {

    /** The parameters of {@code transaction.abort-hanging}. */
    public record HangingAbortParams(UUID clusterId, String topic, int partition, long producerId, String etag) {}

    private final KafkaHangingTransactions hanging;
    private final BrokerGateway gateway;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    public HangingAbortResult abort(UUID clusterId, String topic, int partition, long producerId, String etag) {
        KafkaEtags.require(
                etag,
                hanging.abortPreview(clusterId, topic, partition, producerId).etag());
        return gated(new HangingAbortParams(clusterId, topic, partition, producerId, etag));
    }

    public Effect estimate(HangingAbortParams p) {
        HangingAbortPreview preview = hanging.abortPreview(p.clusterId(), p.topic(), p.partition(), p.producerId());
        return new Effect(preview.recordsHeldBack(), "records", preview.etag(), preview.effect());
    }

    public void replay(HangingAbortParams p) {
        run(p);
    }

    @Gated("transaction.abort-hanging")
    private HangingAbortResult gated(HangingAbortParams p) {
        return gate.run(Operation.of(p), () -> run(p));
    }

    private HangingAbortResult run(HangingAbortParams p) {
        HangingAbortPreview preview = hanging.abortPreview(p.clusterId(), p.topic(), p.partition(), p.producerId());
        KafkaEtags.require(p.etag(), preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "ABORT_HANGING_TRANSACTION",
                "TRANSACTION",
                p.topic() + "-" + p.partition() + "/" + p.producerId(),
                p.clusterId(),
                null,
                Map.of("recordsHeldBack", preview.recordsHeldBack()),
                false);
        try {
            ClusterConnection connection = gateway.cluster(p.clusterId());
            var result = KafkaFailures.call(() -> connection
                    .operations()
                    .execute(
                            KafkaHangingTransactions.id(p.topic(), p.partition(), p.producerId()),
                            new Resolve(Resolution.ROLLBACK),
                            Scope.whole(),
                            Precondition.ifMatch(p.etag()))
                    .orThrow());
            boolean aborted = !result.alreadyInState();
            audit.finish(event, false, aborted ? 1 : 0, null, Map.of("aborted", aborted));
            return new HangingAbortResult(p.topic(), p.partition(), p.producerId(), aborted);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }
}
