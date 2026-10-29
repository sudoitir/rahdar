package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A tap outlives a queue delete only while its subscription matches another queue on the address. */
class CaptureTapCoverageTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String ADDRESS = "ORDERS";

    private final MessageIndexSubscriptionRepository subscriptions = mock(MessageIndexSubscriptionRepository.class);
    private final CaptureAddresses addresses = mock(CaptureAddresses.class);
    private final CaptureTapCoverage coverage = new CaptureTapCoverage(subscriptions, addresses);
    private final MessageIndexSubscriptionEntity subscription = new MessageIndexSubscriptionEntity();
    private String tap;

    @BeforeEach
    void setUp() {
        subscription.setId(UUID.randomUUID());
        tap = CaptureNames.of("inst", ADDRESS, subscription.getId());
        when(subscriptions.findById(subscription.getId())).thenReturn(Optional.of(subscription));
    }

    private static QueryPlan.Target target(String queue, String address) {
        return new QueryPlan.Target(UUID.randomUUID(), "n", queue, address, "ANYCAST", 0L, null, false);
    }

    @Test
    void aTapIsKeptWhileThePatternMatchesAnotherQueueOnTheAddress() {
        when(addresses.targets(CLUSTER, subscription))
                .thenReturn(List.of(target("orders", ADDRESS), target("audit", ADDRESS)));

        assertThat(coverage.coversWithout(CLUSTER, tap, ADDRESS, "orders")).isTrue();
    }

    @Test
    void aTapWhoseOnlyMatchOnTheAddressIsTheDeletedQueueGoes() {
        when(addresses.targets(CLUSTER, subscription))
                .thenReturn(List.of(target("orders", ADDRESS), target("audit", "ELSEWHERE")));

        assertThat(coverage.coversWithout(CLUSTER, tap, ADDRESS, "orders")).isFalse();
    }

    @Test
    void aTapNoSubscriptionOwnsGoes() {
        assertThat(coverage.coversWithout(CLUSTER, "rahdar.capture.junk", ADDRESS, "orders"))
                .isFalse();
    }
}
