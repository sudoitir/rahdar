package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SampleGateTest {

    private static final Instant T0 = Instant.parse("2026-10-29T12:00:00Z");
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    @Test
    void aChangedValueInsideTheHeartbeatIsNotStoredAgain() {
        SampleGate gate = new SampleGate();

        assertThat(gate.due("QUEUE", CLUSTER, NODE, "orders", "openbroker.enqueued", T0))
                .isTrue();
        assertThat(gate.due("QUEUE", CLUSTER, NODE, "orders", "openbroker.enqueued", T0.plusSeconds(15)))
                .isFalse();
    }

    @Test
    void aQuietSeriesIsStoredOncePerHeartbeat() {
        SampleGate gate = new SampleGate();

        assertThat(gate.due("QUEUE", CLUSTER, NODE, "orders", "openbroker.backlog", T0))
                .isTrue();
        assertThat(gate.due(
                        "QUEUE",
                        CLUSTER,
                        NODE,
                        "orders",
                        "openbroker.backlog",
                        T0.plus(SampleGate.HEARTBEAT).minusSeconds(1)))
                .isFalse();
        assertThat(gate.due("QUEUE", CLUSTER, NODE, "orders", "openbroker.backlog", T0.plus(SampleGate.HEARTBEAT)))
                .isTrue();
    }

    @Test
    void anotherMetricOrSubjectKeepsItsOwnHeartbeat() {
        SampleGate gate = new SampleGate();

        assertThat(gate.due("QUEUE", CLUSTER, NODE, "orders", "openbroker.enqueued", T0))
                .isTrue();
        assertThat(gate.due("QUEUE", CLUSTER, NODE, "orders", "openbroker.acknowledged", T0))
                .isTrue();
        assertThat(gate.due("QUEUE", CLUSTER, NODE, "billing", "openbroker.enqueued", T0))
                .isTrue();
    }

    @Test
    void theRateWindowIsTwoHeartbeatsUnlessTheScrapeIsSlower() {
        assertThat(SampleGate.rateWindow(Duration.ofSeconds(15))).isEqualTo(Duration.ofSeconds(120));
        assertThat(SampleGate.rateWindow(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(10));
    }
}
