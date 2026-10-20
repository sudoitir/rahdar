package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowRepository;
import io.github.sudoitir.rahdar.kernel.core.PartitionProvisioner;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves flows past their deadline out of {@code AWAITING_REPLY} (design.md D4):
 * {@code ORPHANED} when no responder was ever observed for the request address,
 * {@code TIMED_OUT} otherwise. Neither state comes from a single observation —
 * "nothing happened for long enough" is a fact only a sweep can produce.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RrDeadlineSweep {

    private final RrFlowRepository flows;
    private final RrEventRepository events;
    private final SseHub sseHub;
    private final PartitionProvisioner partitions;
    private final TransactionTemplate transactions;

    /**
     * Scheduled by {@code JobRunner} on {@code rr.sweep-interval}. It used to
     * hardcode 5s and ignore the configured value entirely.
     */
    public void sweep() {
        partitions.retryingNoPartition(() -> transactions.execute(status -> {
            moveOverdue();
            return null;
        }));
    }

    private void moveOverdue() {
        Instant now = Instant.now();
        List<RrFlowEntity> overdue = flows.findByStateAndDeadlineAtBefore(RrState.AWAITING_REPLY.name(), now);
        for (RrFlowEntity flow : overdue) {
            boolean hadResponder = flow.getResponderConsumer() != null;
            RrState next = hadResponder ? RrState.TIMED_OUT : RrState.ORPHANED;
            flow.setState(next.name());
            flow.setObservedAt(now);
            flows.save(flow);
            events.save(new RrEventEntity(flow.getId(), flow.getNodeId(), next.name(), now, null));
            sseHub.publishAboutAddress(flow.getClusterId(), "rr", flow.getRequestDestination());
        }
        if (!overdue.isEmpty()) {
            log.debug("Deadline sweep moved {} flows out of AWAITING_REPLY", overdue.size());
        }
    }
}
