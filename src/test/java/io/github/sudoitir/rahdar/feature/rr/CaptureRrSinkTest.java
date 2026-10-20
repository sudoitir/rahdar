package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationRepository;
import io.github.sudoitir.rahdar.feature.sql.CaptureBus;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** What capture hands the correlator: request and reply facts, and nothing for addresses nobody traces. */
class CaptureRrSinkTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final CaptureBus bus = mock(CaptureBus.class);
    private final RrExpectationRepository expectations = mock(RrExpectationRepository.class);
    private final ReplyDestinationResolver replyDestinations = mock(ReplyDestinationResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RrObservationSink> provider = mock(ObjectProvider.class);

    private final List<Observation> observed = new ArrayList<>();
    private CaptureRrSink sink;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(observed::add);
        sink = new CaptureRrSink(bus, expectations, replyDestinations, provider);
    }

    private static Row row(
            String address, String correlationId, String replyTo, long timestamp, Map<String, Object> props) {
        return new Row(
                NODE,
                "primary",
                "q",
                address,
                42,
                3,
                true,
                4,
                timestamp,
                5000,
                10,
                null,
                correlationId,
                null,
                null,
                replyTo,
                "body",
                false,
                false,
                null,
                props,
                Source.INDEX,
                null,
                null,
                "CAPTURED",
                null);
    }

    private static CaptureBus.Captured captured(UUID cluster, Row row) {
        return new CaptureBus.Captured(cluster, UUID.randomUUID(), row, row.destination(), null, Instant.now());
    }

    private static RrExpectationEntity expectation(UUID cluster, String request) {
        return new RrExpectationEntity(cluster, request, List.of("rr.reply"), null, null, 10, false);
    }

    @Test
    void subscribesToTheBus() {
        sink.subscribe();

        verify(bus).register(sink);
    }

    @Test
    void doesNothingWithoutAnObservationSink() {
        when(provider.getIfAvailable()).thenReturn(null);

        sink.captured(captured(CLUSTER, row("rr.request", "c", null, 0, null)));

        assertThat(observed).isEmpty();
    }

    @Test
    void aMessageOnTheRequestDestinationIsARequest() {
        RrExpectationEntity e = expectation(CLUSTER, "rr.request");
        when(expectations.findByEnabledTrue()).thenReturn(List.of(e));

        sink.captured(captured(CLUSTER, row("rr.request", "corr-1", "queue://tmp[reply-1]", 1_700_000_000_000L, null)));

        assertThat(observed).singleElement().isInstanceOfSatisfying(Observation.RequestSeen.class, seen -> {
            assertThat(seen.clusterId()).isEqualTo(CLUSTER);
            assertThat(seen.nodeId()).isEqualTo(NODE);
            assertThat(seen.requestDestination()).isEqualTo("rr.request");
            assertThat(seen.messageId()).isEqualTo("42");
            assertThat(seen.correlationId()).isEqualTo("corr-1");
            assertThat(seen.replyTo()).isEqualTo("reply-1");
            assertThat(seen.expiration()).isEqualTo(5000);
            assertThat(seen.bodyPreview()).isEqualTo("body");
            assertThat(seen.enqueuedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        });
    }

    @Test
    void aRequestWithoutReplyToOrTimestampHasNeither() {
        when(expectations.findByEnabledTrue()).thenReturn(List.of(expectation(CLUSTER, "rr.request")));

        sink.captured(captured(CLUSTER, row("rr.request", "c", null, 0, null)));

        assertThat(observed).singleElement().isInstanceOfSatisfying(Observation.RequestSeen.class, seen -> {
            assertThat(seen.replyTo()).isNull();
            assertThat(seen.enqueuedAt()).isNull();
        });
    }

    @Test
    void aMessageOnAReplyDestinationIsAReply() {
        RrExpectationEntity e = expectation(CLUSTER, "rr.request");
        when(expectations.findByEnabledTrue()).thenReturn(List.of(e));
        when(replyDestinations.matches(e, "rr.reply")).thenReturn(true);

        sink.captured(captured(CLUSTER, row("rr.reply", "corr-1", null, 1_000L, null)));

        assertThat(observed).singleElement().isInstanceOfSatisfying(Observation.ReplySeen.class, seen -> {
            assertThat(seen.replyDestination()).isEqualTo("rr.reply");
            assertThat(seen.correlationId()).isEqualTo("corr-1");
            assertThat(seen.enqueuedAt()).isEqualTo(Instant.ofEpochMilli(1_000L));
        });
    }

    @Test
    void anUnrelatedAddressProducesNothing() {
        RrExpectationEntity e = expectation(CLUSTER, "rr.request");
        when(expectations.findByEnabledTrue()).thenReturn(List.of(e));
        when(replyDestinations.matches(e, "other")).thenReturn(false);

        sink.captured(captured(CLUSTER, row("other", "c", null, 0, null)));

        assertThat(observed).isEmpty();
    }

    @Test
    void aMessageWithoutAnAddressIsNeverAReply() {
        when(expectations.findByEnabledTrue()).thenReturn(List.of(expectation(CLUSTER, "rr.request")));

        sink.captured(captured(CLUSTER, row(null, "c", null, 0, null)));

        assertThat(observed).isEmpty();
    }

    @Test
    void anotherClustersExpectationIsIgnored() {
        when(expectations.findByEnabledTrue()).thenReturn(List.of(expectation(UUID.randomUUID(), "rr.request")));

        sink.captured(captured(CLUSTER, row("rr.request", "c", null, 0, null)));

        assertThat(observed).isEmpty();
    }

    @Test
    void correlationFallsBackToTheArtemisPropertyThenToNothing() {
        when(expectations.findByEnabledTrue()).thenReturn(List.of(expectation(CLUSTER, "rr.request")));

        sink.captured(captured(CLUSTER, row("rr.request", null, null, 0, Map.of("_AMQ_CORRELATION_ID", "amq-1"))));
        sink.captured(captured(CLUSTER, row("rr.request", null, null, 0, Map.of())));
        sink.captured(captured(CLUSTER, row("rr.request", null, null, 0, null)));

        assertThat(observed)
                .extracting(o -> ((Observation.RequestSeen) o).correlationId())
                .containsExactly("amq-1", null, null);
    }
}
