package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.Plane;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The verdict, which is the part an operator reads. Three clocks can be measured
 * directly; Rahdar's own is inferred from every node agreeing that it is wrong.
 */
class ClockOffsetServiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final long TEN_MINUTES = 600_000;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private final ClockOffsetRegistry registry = new ClockOffsetRegistry(clock);
    private final NodeDirectory nodes = mock(NodeDirectory.class);

    private ClockOffsetService service() {
        BrokerProperties properties = new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000);
        return new ClockOffsetService(nodes, registry, clock, properties);
    }

    private static NodeDirectory.KnownNode node(String name, String url) {
        return new NodeDirectory.KnownNode(
                UUID.randomUUID(), CLUSTER, name, List.of(new Endpoint(Plane.MANAGEMENT, BrokerGateway.JOLOKIA, url)));
    }

    private void reading(String url, long offsetMs) {
        long t0 = 1_000_000;
        // Round trip of 20ms, so the midpoint is t0 + 10 and the reading is clean.
        registry.recordReading(url, (t0 + 10 + offsetMs) / 1_000, t0, t0 + 20);
    }

    @Test
    void everyNodeAgreeingIsNoAlarm() {
        NodeDirectory.KnownNode a = node("broker-1", "http://broker-1/jolokia");
        NodeDirectory.KnownNode b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.nodes()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", 0);
        reading("http://broker-2/jolokia", 0);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.IN_AGREEMENT);
    }

    @Test
    void oneNodeOutOfStepIsThatNodesProblem() {
        NodeDirectory.KnownNode a = node("broker-1", "http://broker-1/jolokia");
        NodeDirectory.KnownNode b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.nodes()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        reading("http://broker-2/jolokia", 0);

        ClockOffsetService service = service();
        service.refresh();

        ClockOffsetService.Assessment assessment = service.assessmentFor(CLUSTER);
        assertThat(assessment.verdict()).isEqualTo(ClockOffsetService.Verdict.BROKER_SKEWED);
        assertThat(assessment.skewed())
                .singleElement()
                .satisfies(s -> assertThat(s.nodeName()).isEqualTo("broker-1"));
    }

    @Test
    void everyNodeOutOfStepTheSameWayImplicatesRahdarItself() {
        // Every broker in the estate being ten minutes fast, simultaneously, in the
        // same direction, has one plausible common cause and it is not the brokers.
        NodeDirectory.KnownNode a = node("broker-1", "http://broker-1/jolokia");
        NodeDirectory.KnownNode b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.nodes()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        reading("http://broker-2/jolokia", TEN_MINUTES);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.RAHDAR_SUSPECT);
    }

    @Test
    void oppositeDirectionsAreTwoBrokenBrokers() {
        NodeDirectory.KnownNode a = node("broker-1", "http://broker-1/jolokia");
        NodeDirectory.KnownNode b = node("broker-2", "http://broker-2/jolokia");
        when(nodes.nodes()).thenReturn(List.of(a, b));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        reading("http://broker-2/jolokia", -TEN_MINUTES);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.BROKER_SKEWED);
    }

    @Test
    void oneWitnessIsNotCorroboration() {
        // A single skewed node cannot distinguish "this broker is wrong" from "Rahdar
        // is wrong", and the cheaper, likelier answer is named rather than the alarming one.
        NodeDirectory.KnownNode only = node("broker-1", "http://broker-1/jolokia");
        when(nodes.nodes()).thenReturn(List.of(only));
        reading("http://broker-1/jolokia", TEN_MINUTES);

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.BROKER_SKEWED);
    }

    @Test
    void nothingMeasuredIsUnknownRatherThanHealthy() {
        when(nodes.nodes()).thenReturn(List.of(node("broker-1", "http://broker-1/jolokia")));

        ClockOffsetService service = service();
        service.refresh();

        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.UNKNOWN);
    }

    @Test
    void aSteppedRahdarClockThrowsAwayEveryEstimate() {
        NodeDirectory.KnownNode a = node("broker-1", "http://broker-1/jolokia");
        when(nodes.nodes()).thenReturn(List.of(a));
        reading("http://broker-1/jolokia", TEN_MINUTES);
        ClockOffsetService service = service();
        service.refresh();
        assertThat(service.offsetFor(a.id())).isPresent();

        service.onRahdarClockStepped();

        assertThat(service.offsetFor(a.id())).isEmpty();
        assertThat(service.assessmentFor(CLUSTER).verdict()).isEqualTo(ClockOffsetService.Verdict.UNKNOWN);
    }
}
