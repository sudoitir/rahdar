package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Work that must be done waits for a lock someone else holds, for as long as its caller allows and no longer. */
class ClusterLockWaitTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static HikariDataSource dataSource;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(4);
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        POSTGRES.stop();
    }

    @Test
    void workWaitsForTheHolderAndRunsOnceItIsDone() throws Exception {
        ClusterLock lock = new ClusterLock(dataSource);
        UUID cluster = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = hold(lock, cluster, release);

        AtomicBoolean ran = new AtomicBoolean();
        assertThat(lock.runWhenFree(
                        cluster, ClusterLock.Scope.PLUGIN_MESSAGING, Duration.ofMillis(300), () -> ran.set(true)))
                .as("not free within the wait")
                .isFalse();
        assertThat(ran).isFalse();

        Thread.ofVirtual().start(release::countDown);
        assertThat(lock.runWhenFree(
                        cluster, ClusterLock.Scope.PLUGIN_MESSAGING, Duration.ofSeconds(10), () -> ran.set(true)))
                .isTrue();
        assertThat(ran).isTrue();
        holder.join();
    }

    @Test
    void anInterruptedCallerStopsWaitingAndKeepsItsInterrupt() throws Exception {
        ClusterLock lock = new ClusterLock(dataSource);
        UUID cluster = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = hold(lock, cluster, release);
        try {
            Thread.currentThread().interrupt();
            assertThat(lock.runWhenFree(cluster, ClusterLock.Scope.PLUGIN_MESSAGING, Duration.ofSeconds(10), () -> {}))
                    .isFalse();
            assertThat(Thread.interrupted()).as("the interrupt is kept").isTrue();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    /** Holds the cluster's plugin messaging lock on another thread until {@code release}; returns once held. */
    private static Thread hold(ClusterLock lock, UUID cluster, CountDownLatch release) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual()
                .start(() -> lock.runIfHeld(cluster, ClusterLock.Scope.PLUGIN_MESSAGING, () -> {
                    held.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(held.await(10, TimeUnit.SECONDS)).as("the lock is held").isTrue();
        return holder;
    }
}
