package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The partitions without a leader are read on the scrape's schedule, and health only ever reads the kept count. */
class KafkaPartitionsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final Instant[] now = {Instant.parse("2026-10-29T10:00:00Z")};
    private final KafkaPartitions partitions;

    KafkaPartitionsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        partitions = new KafkaPartitions(gateway, clock);
    }

    private Resource partition(int n, boolean led) {
        Attributes.Builder attributes = Attributes.builder();
        if (led) {
            attributes.putRaw("kafka.leader", 1L);
        }
        return new Resource(
                ResourceId.of("kafka.partition", "t/" + n), attributes.build(), List.of(), List.of(), now[0]);
    }

    private void listing(Resource... items) {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(items), Optional.empty()), List.of()));
    }

    @Test
    void healthReadsNeverTouchTheBroker() {
        assertThat(partitions.offline(CLUSTER)).isEmpty();

        listing(partition(0, true), partition(1, false), partition(2, false));
        partitions.refresh(CLUSTER);
        verify(resources, times(1)).read(any(ResourceQuery.class));

        for (int i = 0; i < 100; i++) {
            assertThat(partitions.offline(CLUSTER)).hasValue(2);
        }
        verify(resources, times(1)).read(any(ResourceQuery.class));
    }

    @Test
    void aReadingIsDueOnlyOnceEveryIntervalAndAStaleOneIsUnknown() {
        assertThat(partitions.due(CLUSTER)).isTrue();
        listing(partition(0, true));
        partitions.refresh(CLUSTER);

        assertThat(partitions.due(CLUSTER)).isFalse();
        now[0] = now[0].plus(KafkaPartitions.EVERY);
        assertThat(partitions.due(CLUSTER)).isTrue();

        now[0] = now[0].plus(KafkaPartitions.STALE);
        assertThat(partitions.offline(CLUSTER)).isEmpty();
    }

    @Test
    void aFailedListingIsNotRetriedBeforeTheIntervalAndKeepsNoInventedZero() {
        when(resources.read(any(ResourceQuery.class)))
                .thenThrow(new BrokerException(
                        Status.UNAVAILABLE,
                        new ErrorInfo(ErrorReasons.UNREACHABLE, ErrorReasons.DOMAIN, Map.of()),
                        Dispatch.NOT_SENT,
                        null,
                        "refused"));
        partitions.refresh(CLUSTER);

        assertThat(partitions.due(CLUSTER)).isFalse();
        assertThat(partitions.offline(CLUSTER)).isEmpty();
    }
}
