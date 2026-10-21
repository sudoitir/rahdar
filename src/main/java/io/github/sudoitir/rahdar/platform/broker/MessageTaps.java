package io.github.sudoitir.rahdar.platform.broker;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Copies of a destination's messages into a bounded holding queue that Rahdar drains, installed on one
 * node without touching how production traffic is routed. Capture and plugin registrations use it;
 * only systems that can copy traffic server-side provide it.
 *
 * <p>Install and remove are idempotent: the reconcilers that call them run on a schedule.
 */
public interface MessageTaps extends SystemExtension {

    /**
     * One tap.
     *
     * @param name the tap's name; also the name Rahdar finds it by in {@link #installed}
     * @param queue the holding queue, which is also its address
     * @param match the pattern the tap's settings are stored under, covering its objects and no other tap's
     * @param destination the destination whose messages are copied
     * @param filter which messages are copied, or {@code null} for all of them
     * @param ringSize the most messages the holding queue keeps; it drops the oldest beyond that
     * @param maxBytes the most bytes it keeps
     * @param expiry how long an unread copy lives
     * @param brokerRole the role that alone may read the holding queue
     */
    record TapSpec(
            String name,
            String queue,
            String match,
            String destination,
            String filter,
            long ringSize,
            long maxBytes,
            Duration expiry,
            String brokerRole) {}

    /** A queue as the broker reports it, and the filter that decides which messages it receives. */
    record TapSource(String queue, String destination, String filter) {}

    /** The queue a tap would copy, as this node reports it, or empty when the node does not have it. */
    Optional<TapSource> source(UUID clusterId, UUID nodeId, String queue);

    /**
     * Install the tap, or confirm it is installed, and check that the broker really has it.
     *
     * @throws TapRefusal when the broker's configuration means the tap would copy nothing or leave the
     *     copies unguarded
     */
    void install(UUID clusterId, UUID nodeId, TapSpec spec);

    /** Remove the tap's objects; whatever is already gone is a success. */
    void remove(UUID clusterId, UUID nodeId, String name, String queue, boolean disconnectConsumers);

    /** Remove the settings stored under a pattern. */
    void removeSettings(UUID clusterId, UUID nodeId, String match);

    /** The names of the taps on the node that start with this prefix. */
    List<String> installed(UUID clusterId, UUID nodeId, String prefix);

    /** The copies the broker dropped because the holding queue was full or they expired unread; empty when unknown. */
    OptionalLong dropped(UUID clusterId, UUID nodeId, String queue);

    /** The configuration an operator would add to make a {@link TapRefusal} go away. */
    String settingsSnippet(TapSpec spec);

    /** The tap cannot be installed, and the next pass will not change that. */
    class TapRefusal extends RuntimeException {

        private final String snippet;

        public TapRefusal(String message, String snippet) {
            super(message);
            this.snippet = snippet;
        }

        /** The configuration that would make the install succeed, or {@code null} when none would. */
        public String snippet() {
            return snippet;
        }
    }
}
