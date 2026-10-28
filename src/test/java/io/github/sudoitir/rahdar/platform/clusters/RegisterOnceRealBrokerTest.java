package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisBrokers;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Registrations;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A cluster's brokers are registered once (ADR-0167), against real brokers: the same URL, another URL of
 * the same broker, and a registration that only partly overlaps are all refused naming the cluster that
 * holds them, by the check and by the registration; two registrations racing end with one cluster.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RegisterOnceRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    BrokerIdentityClaims claims;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient jdbc;

    private final String run = "register-once-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
    }

    private RegisterClusterRequest request(String name, String... seeds) {
        return Registrations.artemis(
                List.of(seeds),
                run + "-" + name,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false);
    }

    private UUID registered(RegisterClusterRequest request) {
        if (!(service.register(request) instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register " + request.seedUrls());
        }
        return ok.value().id();
    }

    /** The shared broker by its IP address where the suite reaches it by name, or the other way round. */
    private static String aliasOfThePrimary() throws Exception {
        URI url = URI.create(ArtemisIntegrationTest.jolokiaUrl());
        String ip = InetAddress.getByName(url.getHost()).getHostAddress();
        String alias = ip.equals(url.getHost()) ? InetAddress.getByName(ip).getCanonicalHostName() : ip;
        assumeThat(alias).as("another name for the broker's host").isNotEqualTo(url.getHost());
        // A trailing slash too: the NodeID is what matches, not the spelling.
        return "http://%s:%d/console/jolokia/".formatted(alias, url.getPort());
    }

    private long clustersOfThisRun() {
        return clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .count();
    }

    @Test
    void theSameUrlIsRefusedByTheCheckAndByTheRegistration() {
        UUID first = registered(request("first", ArtemisIntegrationTest.jolokiaUrl()));

        for (boolean dryRun : new boolean[] {true, false}) {
            RegisterClusterRequest again = request("again", ArtemisIntegrationTest.jolokiaUrl());
            if (dryRun) {
                // The check ran, and its finding is whose brokers these are.
                assertThat(service.checkConnection(again).alreadyRegistered()).satisfies(held -> {
                    assertThat(held.id()).isEqualTo(first);
                    assertThat(held.name()).isEqualTo(run + "-first");
                });
            } else {
                assertThatThrownBy(() -> service.register(again))
                        .isInstanceOfSatisfying(ClusterAlreadyRegisteredException.class, e -> {
                            assertThat(e.existingClusterId()).isEqualTo(first);
                            assertThat(e.existingClusterName()).isEqualTo(run + "-first");
                            assertThat(e.overlappingNodes()).isNotEmpty();
                        });
            }
        }
        assertThat(clustersOfThisRun()).isEqualTo(1);
    }

    @Test
    void anotherUrlOfTheSameBrokerIsRefused() {
        UUID first = registered(request("first", ArtemisIntegrationTest.jolokiaUrl()));

        assertThatThrownBy(() -> service.register(request("alias", aliasOfThePrimary())))
                .isInstanceOfSatisfying(
                        ClusterAlreadyRegisteredException.class,
                        e -> assertThat(e.existingClusterId()).isEqualTo(first));
        assertThat(clustersOfThisRun()).isEqualTo(1);
    }

    @Test
    void aPartialOverlapIsRefusedNamingOnlyTheNodesThatOverlap() {
        UUID first = registered(request("first", ArtemisIntegrationTest.jolokiaUrl()));
        Set<String> firstNodes = Set.copyOf(nodes.findByClusterIdOrderByNameAsc(first).stream()
                .map(BrokerNodeEntity::getName)
                .toList());

        RegisterClusterRequest both =
                request("both", ArtemisBrokers.second().jolokiaUrl(), ArtemisIntegrationTest.jolokiaUrl());
        assertThat(service.checkConnection(both).alreadyRegistered().id()).isEqualTo(first);
        assertThatThrownBy(() -> service.register(both))
                .isInstanceOfSatisfying(ClusterAlreadyRegisteredException.class, e -> {
                    assertThat(e.existingClusterId()).isEqualTo(first);
                    assertThat(e.overlappingNodes())
                            .isNotEmpty()
                            .allSatisfy(n -> assertThat(firstNodes).contains(n));
                });
        // Nothing of the refused registration is kept, the second broker included.
        assertThat(clustersOfThisRun()).isEqualTo(1);
    }

    @Test
    void twoRegistrationsRacingEndWithOneCluster() throws Exception {
        Authentication admin = SecurityContextHolder.getContext().getAuthentication();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> outcomes = new ArrayList<>();
            for (String name : List.of("left", "right")) {
                outcomes.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(admin);
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        return service.register(request(name, ArtemisIntegrationTest.jolokiaUrl()));
                    } catch (ClusterAlreadyRegisteredException e) {
                        return e;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> outcome : outcomes) {
                results.add(outcome.get(120, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(Attempt.Ok.class::isInstance).hasSize(1);
            assertThat(results)
                    .filteredOn(ClusterAlreadyRegisteredException.class::isInstance)
                    .singleElement()
                    .satisfies(e -> assertThat(((ClusterAlreadyRegisteredException) e).existingClusterId())
                            .isNotNull());
            assertThat(clustersOfThisRun()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The database's half on its own: a claim another transaction holds uncommitted makes the second wait,
     * and once the first commits, the second finds the brokers taken by it rather than failing.
     */
    @Test
    void aClaimWaitsForTheOtherRegistrationAndThenNamesIt() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Map<String, Set<String>> claim = Map.of(ClusterIdentity.NODE_ID, Set.of(run + "-node"));
        CountDownLatch firstClaimed = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<UUID> firstCluster = pool.submit(() -> tx.execute(status -> {
                UUID id = clusters.saveAndFlush(new ClusterEntity(ArtemisModule.SYSTEM, run + "-first", null, null))
                        .getId();
                assertThat(claims.claim(id, claim)).isEmpty();
                firstClaimed.countDown();
                // Commit only once the second claim is waiting on this one.
                await().atMost(Duration.ofSeconds(30))
                        .until(() -> jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                                        .query(Long.class)
                                        .single()
                                > 0);
                return id;
            }));
            assertThat(firstClaimed.await(30, TimeUnit.SECONDS)).isTrue();

            Optional<UUID> holder = tx.execute(status -> claims.claim(
                    clusters.saveAndFlush(new ClusterEntity(ArtemisModule.SYSTEM, run + "-second", null, null))
                            .getId(),
                    claim));

            assertThat(holder).contains(firstCluster.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
