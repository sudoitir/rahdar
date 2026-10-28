package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerNotifications;
import io.github.sudoitir.rahdar.platform.broker.NodeAddress;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Registrations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What one scrape tick asks of a real broker (non-negotiable #1): one management call per node for
 * each of tiers A and B, and one per page of queues for tier C. Counted at the call interceptor, the
 * point every management call passes, so a facet that quietly fans out into several calls fails here.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ScrapeLoadRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusterRows;

    @Autowired
    ClusterDirectory directory;

    @Autowired
    ClusterOwnership ownership;

    @Autowired
    SettingsService settings;

    @Autowired
    BrokerGateway gateway;

    @Autowired
    NodeStateRecorder persist;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    MetricSampleWriter samples;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    MeterRegistry meters;

    private final String name = "scrape-load-" + UUID.randomUUID().toString().substring(0, 8);
    private final String node = NodeAddress.hostPort(ArtemisIntegrationTest.jolokiaUrl());
    private ScrapeScheduler scheduler;

    @BeforeEach
    void register() {
        Attempt<ClusterDetail> attempt = clusterService.register(Registrations.artemis(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                name,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        assertThat(attempt).isInstanceOf(Attempt.Ok.class);
        scheduler = new ScrapeScheduler(
                settings,
                directory,
                ownership,
                clusterService,
                gateway,
                new ScrapeCycle(persist),
                persist,
                upsert,
                samples,
                new StreamSignals(mock(SseHub.class)),
                mock(BrokerNotifications.class),
                events);
    }

    @AfterEach
    void unregister() {
        scheduler.destroy();
        clusterRows.findAll().stream()
                .filter(c -> c.getName().equals(name))
                .forEach(c -> clusterRows.deleteById(c.getId()));
    }

    @Test
    void tierAReadsEachNodeOnce() {
        long before = calls();

        scheduler.tierA();

        // A tier A probe finishes on its own thread; wait for it, then make sure nothing follows it.
        await().atMost(Duration.ofSeconds(10)).until(() -> calls() > before);
        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
        assertThat(calls() - before).isEqualTo(1);
    }

    @Test
    void tierBReadsTheFirstPageOfEachNodeOnce() {
        long before = calls();

        scheduler.tierB();

        assertThat(calls() - before).isEqualTo(1);
    }

    @Test
    void tierCReadsEachPageOnce() {
        long before = calls();

        scheduler.tierC();

        // The test broker's queues fit one page.
        assertThat(calls() - before).isEqualTo(1);
    }

    /** Management calls admitted for the test broker so far. */
    private long calls() {
        return meters.find("rahdar.broker.management").tag("node", node).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }
}
