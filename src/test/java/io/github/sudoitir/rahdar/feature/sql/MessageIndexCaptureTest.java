package io.github.sudoitir.rahdar.feature.sql;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@link MessageIndexCapture}'s reaction to a cluster this replica stops owning. */
class MessageIndexCaptureTest {

    /**
     * The pass runs on a thread nobody joins, and while the application closes its database is already going
     * away. A failure there must be logged, not left to the JVM's uncaught handler, which is where a test running
     * at that moment (Awaitility reports it) picks it up.
     */
    @Test
    void aFailingPassAfterAReleaseDoesNotEscapeItsThread() {
        MessageIndexSubscriptionRepository subscriptions = mock(MessageIndexSubscriptionRepository.class);
        when(subscriptions.findByEnabledTrue()).thenThrow(new IllegalStateException("the context is closed"));
        MessageIndexCapture capture =
                new MessageIndexCapture(subscriptions, null, null, null, null, mock(ClusterOwnership.class));

        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((_, e) -> uncaught.add(e));
        try {
            capture.onDutyReleased(new ClusterDutyReleased(UUID.randomUUID()));

            verify(subscriptions, timeout(5_000)).findByEnabledTrue();
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(uncaught::isEmpty);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }
}
