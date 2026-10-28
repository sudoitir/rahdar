package io.github.sudoitir.rahdar.platform.clusters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A per-cluster Postgres advisory lock, so that two Rahdar instances sharing one
 * database do not both reconcile the same cluster (ADR-0062 D5).
 *
 * <p>Ownership encoded in a divert's name defends against a <em>different</em> Rahdar;
 * it cannot defend against a second instance of the <em>same</em> one, which shares the
 * instance id and would see its peer's taps as its own. This is that defence.
 *
 * <p>Not holding the lock is not a failure. The other instance is doing the work, so
 * the correct response is to stop reconciling until the next pass — not to log an
 * error, not to raise an alert, and above all not to act anyway. The same is true of a
 * database that is momentarily unavailable: the pass is skipped, and the reconciler is
 * an idempotent loop that converges on the next one.
 *
 * <p>The lock is session-scoped and therefore held on one pooled connection for the
 * duration of the pass, which includes broker calls. Passes are sequential, so at most
 * one connection is held this way at a time; Postgres releases the lock on its own if
 * the connection dies, which is what makes a crashed instance recoverable without a
 * lease table.
 *
 * <p>The same applies to a failover or a network partition: the lock lives and dies with its
 * session, so a dropped connection means another instance may hold it now. While the work runs,
 * a watchdog probes the held connection every {@link #WATCH}, and when it is gone it logs the
 * loss and interrupts the working thread, so the duty stops. The next pass takes the lock again,
 * if it is free.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClusterLock {

    /**
     * The first half of the advisory-lock key, distinguishing Rahdar's locks from any
     * other application's on the same database. {@code 0x4153} is "AS".
     */
    private static final int NAMESPACE = 0x4153;

    /** How often the held connection is probed while the work runs. */
    static final Duration WATCH = Duration.ofSeconds(2);

    /** How often {@link #runWhenFree} asks for a lock someone else holds. */
    private static final Duration WAIT_POLL = Duration.ofMillis(50);

    private final DataSource dataSource;

    /**
     * Run {@code work} while holding this cluster's lock, or do nothing at all.
     *
     * @return whether the work ran. {@code false} means another instance holds the
     *     lock, or the database could not be reached — both of which mean "stop
     *     reconciling", not "something is wrong".
     */
    public boolean runIfHeld(UUID clusterId, Runnable work) {
        return runIfHeld(clusterId, Scope.RECONCILE, work);
    }

    /**
     * What a lock guards. Each scope is its own advisory-lock namespace, so a
     * configuration apply refusing to run "because another apply is in progress" is
     * telling the truth and is not merely colliding with a reconcile pass.
     */
    public enum Scope {
        RECONCILE(NAMESPACE),
        CONFIG_APPLY(NAMESPACE + 1),
        /**
         * Drift evaluation. Its own namespace because it is neither: sharing
         * {@code RECONCILE} made a drift pass and the capture reconciler wait for each
         * other for no reason, while leaving two drift passes free to stack.
         */
        CONFIG_DRIFT(NAMESPACE + 2),
        /**
         * Client-activity sampling for the flow view (ADR-0081): one instance samples a
         * cluster, every instance serves the persisted result.
         */
        FLOW_SAMPLE(NAMESPACE + 3),
        /** Setup review (ADR-0106): one instance reads a cluster's nodes per interval. */
        SETUP_REVIEW(NAMESPACE + 4),
        /** Plugins' message registrations (ADR-0111): one instance converges a cluster's taps and drains. */
        PLUGIN_MESSAGING(NAMESPACE + 5);

        private final int namespace;

        Scope(int namespace) {
            this.namespace = namespace;
        }
    }

    /**
     * Whether someone else currently holds this cluster's lock in this scope.
     *
     * <p>Answered by taking the lock and letting it go again, which is the only
     * question Postgres will answer about an advisory lock without a catalogue scan.
     * The answer is therefore a moment in the past: a caller that acts on it races
     * anything that takes the lock immediately afterwards. That is tolerable where
     * the consequence of losing the race is bounded and self-correcting — refusing
     * an adoption, or skipping a drift pass that runs again on the next interval —
     * and it is not a substitute for holding the lock around work that must be
     * exclusive.
     */
    public boolean isHeld(UUID clusterId, Scope scope) {
        int key = key(clusterId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!call(connection, "SELECT pg_try_advisory_lock(?, ?)", scope.namespace, key)) {
                return true;
            }
            call(connection, "SELECT pg_advisory_unlock(?, ?)", scope.namespace, key);
            return false;
        } catch (SQLException e) {
            // A database Rahdar cannot reach is not evidence that an apply is running.
            log.debug("Could not probe the {} lock for cluster {}: {}", scope, clusterId, e.getMessage());
            return false;
        }
    }

    public boolean runIfHeld(UUID clusterId, Scope scope, Runnable work) {
        int namespace = scope.namespace;
        int key = key(clusterId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (!call(connection, "SELECT pg_try_advisory_lock(?, ?)", namespace, key)) {
                log.debug("Cluster {} is being reconciled by another instance; skipping this pass", clusterId);
                return false;
            }
            try (var _ = watch(connection, scope, clusterId)) {
                work.run();
            } finally {
                release(connection, namespace, key);
            }
            return true;
        } catch (SQLException e) {
            log.debug("Could not take the reconcile lock for cluster {}: {}", clusterId, e.getMessage());
            return false;
        }
    }

    /**
     * Run {@code work} while holding this cluster's lock in this scope, waiting up to {@code wait} for
     * whoever holds it now. For work a caller needs done rather than merely attempted: a change that must
     * be converged before the call that made it returns, where skipping because a pass is running would
     * leave it to the next scheduled pass.
     *
     * @return whether the work ran; {@code false} means the lock was not free within {@code wait}, or the
     *     database could not be reached
     */
    public boolean runWhenFree(UUID clusterId, Scope scope, Duration wait, Runnable work) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (!runIfHeld(clusterId, scope, work)) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(WAIT_POLL);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** A lost connection has already lost the lock, so a failed unlock changes nothing. */
    private void release(Connection connection, int namespace, int key) {
        try {
            call(connection, "SELECT pg_advisory_unlock(?, ?)", namespace, key);
        } catch (SQLException e) {
            log.debug("Could not release a cluster lock, its connection is gone: {}", e.getMessage());
        }
    }

    private Watchdog watch(Connection connection, Scope scope, UUID clusterId) {
        Watchdog watchdog = new Watchdog(Thread.currentThread());
        watchdog.start(() -> {
            String reason = "its connection is gone";
            try {
                while (connection.isValid((int) WATCH.toSeconds())) {
                    Thread.sleep(WATCH);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // The work finished.
                return;
            } catch (SQLException e) {
                reason = e.getMessage();
            }
            if (watchdog.stopWork()) {
                log.warn("The {} lock of cluster {} was lost ({}); stopping the work", scope, clusterId, reason);
            }
        });
        return watchdog;
    }

    /**
     * Probes the held connection on its own thread and interrupts the worker when it fails. Closing it
     * ends the probe and clears an interrupt it sent, so the caller's thread is not left interrupted.
     */
    private static final class Watchdog implements AutoCloseable {
        private final Thread worker;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean fired = new AtomicBoolean();
        private Thread thread;

        Watchdog(Thread worker) {
            this.worker = worker;
        }

        void start(Runnable probe) {
            thread = Thread.ofVirtual().name("cluster-lock-watch").start(probe);
        }

        /** Interrupts the worker unless the work has finished; whether it did. */
        boolean stopWork() {
            if (finished.get()) {
                return false;
            }
            fired.set(true);
            worker.interrupt();
            return true;
        }

        @Override
        public void close() {
            finished.set(true);
            thread.interrupt();
            boolean interrupted = Thread.interrupted();
            try {
                thread.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                interrupted = true;
            }
            // The caller's thread is not left interrupted by the probe's own interrupt, only by a real one.
            if (fired.get()) {
                Thread.interrupted();
            } else if (interrupted) {
                worker.interrupt();
            }
        }
    }

    private boolean call(Connection connection, String sql, int namespace, int key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, namespace);
            statement.setInt(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * The second half of the key. A UUID does not fit in the 32 bits Postgres gives
     * this half, so it is folded; a collision would mean two clusters serialise against
     * each other for the length of a pass, which is a cost and not a correctness
     * problem.
     */
    private static int key(UUID clusterId) {
        return Long.hashCode(clusterId.getMostSignificantBits() ^ clusterId.getLeastSignificantBits());
    }
}
