package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.security.PluginSecrets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Where a fixture plugin (compiled inside the test, loaded by its own classloader) leaves what it
 * was handed, and takes its orders from: a JVM-wide rendezvous on the test classpath, which a
 * plugin's classloader delegates to. Keyed by plugin id so fixtures do not see each other.
 */
public final class MessagingProbe {

    public static final Map<String, BlockingQueue<PluginMessage>> INBOX = new ConcurrentHashMap<>();
    public static final Map<String, Disposition> ANSWER = new ConcurrentHashMap<>();
    /** When present, a delivery waits for a permit before answering: a plugin that has stopped keeping up. */
    public static final Map<String, Semaphore> GATE = new ConcurrentHashMap<>();
    /** When present, runs inside the handler before it answers, such as the plugin stopping its own registration. */
    public static final Map<String, Runnable> ON_RECEIVE = new ConcurrentHashMap<>();

    public static final Map<String, PluginMessaging> MESSAGING = new ConcurrentHashMap<>();
    public static final Map<String, PluginSecrets> SECRETS = new ConcurrentHashMap<>();

    private MessagingProbe() {}

    public static Disposition received(String pluginId, PluginMessage message) {
        // Taken before the message is visible: a test that sees it and changes the answer is changing the
        // answer to the next one, not to this one.
        Disposition answer = ANSWER.getOrDefault(pluginId, Disposition.ACCEPT);
        INBOX.computeIfAbsent(pluginId, k -> new LinkedBlockingQueue<>()).add(message);
        Semaphore gate = GATE.get(pluginId);
        if (gate != null) {
            gate.acquireUninterruptibly();
        }
        Runnable then = ON_RECEIVE.get(pluginId);
        if (then != null) {
            then.run();
        }
        return answer;
    }

    public static BlockingQueue<PluginMessage> inbox(String pluginId) {
        return INBOX.computeIfAbsent(pluginId, k -> new LinkedBlockingQueue<>());
    }

    public static void forget(String pluginId) {
        INBOX.remove(pluginId);
        ANSWER.remove(pluginId);
        ON_RECEIVE.remove(pluginId);
        Semaphore gate = GATE.remove(pluginId);
        if (gate != null) {
            gate.release(10_000);
        }
        MESSAGING.remove(pluginId);
        SECRETS.remove(pluginId);
    }
}
