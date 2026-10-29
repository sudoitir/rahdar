package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrEventRepository;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link RrDeadlineSweep}: the ORPHANED vs TIMED_OUT split turns on whether a responder was ever observed. */
class RrDeadlineSweepTest extends PostgresIntegrationTest {

    @Autowired
    RrDeadlineSweep sweep;

    @Autowired
    RrFlowRepository flows;

    @Autowired
    RrEventRepository events;

    @Autowired
    ClusterRepository clusters;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "rr-sweep-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    private RrFlowEntity overdueFlow(UUID clusterId, String responderConsumer) {
        Instant past = Instant.now().minusSeconds(60);
        RrFlowEntity flow = new RrFlowEntity(new RrFlowEntity.Awaiting(
                clusterId,
                null,
                "rr.request",
                null,
                "SHARED",
                "corr-sweep",
                "m-" + UUID.randomUUID(),
                past,
                past.plusSeconds(1))); // already overdue
        flow.setResponderConsumer(responderConsumer);
        return flows.save(flow);
    }

    @Test
    void noResponderEverObservedIsOrphaned() {
        cluster();
        RrFlowEntity flow = overdueFlow(clusterId, null);

        sweep.sweep();

        RrFlowEntity reloaded = flows.findById(flow.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(RrState.ORPHANED.name());
        assertThat(events.findByFlowIdOrderByTsAsc(flow.getId())).hasSize(1);
    }

    @Test
    void aResponderHavingBeenObservedTimesOut() {
        cluster();
        RrFlowEntity flow = overdueFlow(clusterId, "consumer-1");

        sweep.sweep();

        RrFlowEntity reloaded = flows.findById(flow.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(RrState.TIMED_OUT.name());
    }

    @Test
    void aFlowNotYetPastItsDeadlineIsUntouched() {
        cluster();
        Instant now = Instant.now();
        RrFlowEntity flow = flows.save(new RrFlowEntity(new RrFlowEntity.Awaiting(
                clusterId, null, "rr.request", null, "SHARED", "corr-future", "m-future", now, now.plusSeconds(300))));

        sweep.sweep();

        assertThat(flows.findById(flow.getId()).orElseThrow().getState()).isEqualTo(RrState.AWAITING_REPLY.name());
    }
}
