package io.github.sudoitir.rahdar.kernel.stream;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What {@link io.github.sudoitir.rahdar.kernel.stream.web.StreamController} treats as a
 * known topic and what it replays on reconnect (design.md, task 6.4): a fixed built-in half, from
 * the enabled modules' descriptors exactly as before, plus a copy-on-write plugin half that
 * {@link StreamPluginBridge} adds to and removes from as plugins activate and deactivate. Built-in
 * behaviour is unchanged — the built-in sets are computed once, here, the same way
 * {@code StreamController} used to compute them itself.
 */
@Component
public class StreamTopicRegistry {

    private static final Set<String> DEFAULT_TOPICS = Set.of("topology", "health", "subscriptions");

    private final Map<String, TopicDef> builtinDefs;
    private final Set<String> builtinTopics;
    private final Set<String> builtinDefaults;
    private final Map<String, EventReplay> builtinReplays;

    private final Map<String, List<String>> pluginTopics = new ConcurrentHashMap<>();
    private final Map<String, List<EventReplay>> pluginReplays = new ConcurrentHashMap<>();

    private volatile Set<String> known;
    private volatile Set<String> defaultTopics;
    private volatile Map<String, EventReplay> replays;

    public StreamTopicRegistry(FeatureRegistry features, List<EventReplay> replayBeans) {
        this.builtinDefs = features.enabled().stream()
                .flatMap(d -> d.streamTopics().stream())
                .collect(Collectors.toUnmodifiableMap(TopicDef::name, Function.identity()));
        this.builtinTopics = builtinDefs.keySet();
        this.builtinDefaults =
                DEFAULT_TOPICS.stream().filter(builtinTopics::contains).collect(Collectors.toUnmodifiableSet());
        this.builtinReplays = replayBeans.stream()
                .filter(r -> builtinTopics.contains(r.topic()))
                .collect(Collectors.toUnmodifiableMap(EventReplay::topic, Function.identity()));
        this.known = builtinTopics;
        this.defaultTopics = builtinDefaults;
        this.replays = builtinReplays;
    }

    public Set<String> known() {
        return known;
    }

    /**
     * What a known topic needs of a subscriber. A plugin's topic is a signal that needs the cluster's read
     * permission, as the manifest declares no more; an unknown topic has none, and nothing is delivered on it.
     */
    public TopicDef definition(String topic) {
        TopicDef builtin = builtinDefs.get(topic);
        if (builtin != null) {
            return builtin;
        }
        return known.contains(topic) ? TopicDef.signal(topic) : null;
    }

    public Set<String> defaultTopics() {
        return defaultTopics;
    }

    public Map<String, EventReplay> replays() {
        return replays;
    }

    /** Adds a plugin's declared topics, and any {@link EventReplay} bean its own context defines. */
    public synchronized void addPlugin(String pluginId, List<String> topicNames, List<EventReplay> replayBeans) {
        pluginTopics.put(pluginId, List.copyOf(topicNames));
        pluginReplays.put(pluginId, List.copyOf(replayBeans));
        recompute();
    }

    /** Removes every topic and replay a plugin contributed. A plugin that never attached is a no-op. */
    public synchronized void removePlugin(String pluginId) {
        pluginTopics.remove(pluginId);
        pluginReplays.remove(pluginId);
        recompute();
    }

    private void recompute() {
        Set<String> nextKnown = new LinkedHashSet<>(builtinTopics);
        pluginTopics.values().forEach(nextKnown::addAll);
        Map<String, EventReplay> nextReplays = new LinkedHashMap<>(builtinReplays);
        pluginReplays.values().stream()
                .flatMap(List::stream)
                .filter(r -> nextKnown.contains(r.topic()))
                .forEach(r -> nextReplays.put(r.topic(), r));
        known = Set.copyOf(nextKnown);
        replays = Map.copyOf(nextReplays);
        // The default subscription (no ?topics= given) stays the built-in one on purpose: a plugin
        // topic is always opted into explicitly, never delivered to a client that asked for nothing.
        defaultTopics = builtinDefaults;
    }
}
