package io.github.sudoitir.rahdar.support.wire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records the management traffic Rahdar sends to the test brokers, so a refactoring of the broker
 * layer can be proved not to change what reaches a broker.
 *
 * <p>Off unless {@code -Drahdar.wire.record=<dir>} is set; then every Jolokia URL a test hands to
 * Rahdar goes through a {@link RecordingProxy}, and when the test JVM exits one file per test class
 * is written to that directory. A file is the sorted set of distinct, normalised requests the class
 * caused: scrape ticks run on timers, so the order and the number of repetitions vary between runs,
 * while the shape of every request does not. Compare two runs with {@code diff -r}.
 */
public final class WireRecorder {

    private static final String DIR = System.getProperty("rahdar.wire.record");

    private static final Map<String, Set<String>> BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<String, RecordingProxy> PROXIES = new ConcurrentHashMap<>();
    private static volatile String current = "(outside a test class)";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern EPOCH_MILLIS = Pattern.compile("(?<![0-9])1[0-9]{12}(?![0-9])");
    private static final Pattern HOST_PORT = Pattern.compile("(localhost|127\\.0\\.0\\.1|[a-z0-9.-]+):[0-9]{2,5}");
    private static final Pattern HEX_SUFFIX = Pattern.compile("(?<=[-_.])[0-9a-f]{8,}(?![0-9a-f])");
    private static final Pattern JDK_VERSION = Pattern.compile("Java-http-client/[0-9.]+");

    static {
        if (enabled()) {
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(WireRecorder::flush));
            Thread.ofPlatform().daemon().name("wire-core-sampler").start(WireRecorder::sampleCore);
        }
    }

    private WireRecorder() {}

    public static boolean enabled() {
        return DIR != null && !DIR.isBlank();
    }

    /** The URL Rahdar should use for {@code directUrl}: a recording proxy when recording is on. */
    public static String proxied(String directUrl) {
        if (!enabled()) {
            return directUrl;
        }
        RecordingProxy proxy = PROXIES.computeIfAbsent(baseOf(directUrl), RecordingProxy::start);
        return proxy.rewrite(directUrl);
    }

    static void testClassStarted(String className) {
        current = className;
    }

    static void record(String line) {
        BY_CLASS.computeIfAbsent(current, k -> new ConcurrentSkipListSet<>()).add(normalise(line));
    }

    static String normalise(String s) {
        int body = s.lastIndexOf(" | ");
        String out = body < 0 ? s : s.substring(0, body + 3) + canonicalJson(s.substring(body + 3));
        out = UUID.matcher(out).replaceAll("<uuid>");
        out = EPOCH_MILLIS.matcher(out).replaceAll("<millis>");
        out = HOST_PORT.matcher(out).replaceAll("<host>");
        out = HEX_SUFFIX.matcher(out).replaceAll("<hex>");
        return JDK_VERSION.matcher(out).replaceAll("Java-http-client/<v>");
    }

    /**
     * The same JSON with object keys sorted, JSON embedded in string values canonicalised too, and
     * the entries of a top-level batch sorted. Rahdar builds some argument objects from {@code Map.of}
     * and some batches from sets, whose iteration order changes from one JVM run to the next; the
     * requests are the same.
     */
    static String canonicalJson(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            JsonNode canonical = canonical(node);
            if (canonical instanceof ArrayNode batch) {
                List<String> entries = new ArrayList<>();
                batch.forEach(e -> entries.add(JSON.writeValueAsString(e)));
                Collections.sort(entries);
                return "[" + String.join(",", entries) + "]";
            }
            return JSON.writeValueAsString(canonical);
        } catch (JacksonException e) {
            return text;
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            new TreeMap<>(node.properties().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .forEach((k, v) -> sorted.set(k, canonical(v)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            node.forEach(e -> out.add(canonical(e)));
            return out;
        }
        if (node.isString()) {
            String t = node.asString().strip();
            if (t.startsWith("{") || t.startsWith("[")) {
                try {
                    return JSON.getNodeFactory().stringNode(JSON.writeValueAsString(canonical(JSON.readTree(t))));
                } catch (JacksonException e) {
                    return node;
                }
            }
        }
        return node;
    }

    /**
     * Every 250 ms, records the Core consumers open on each recorded broker, keeping what decides how
     * they read (queue, filter, browse-only, protocol), so a change in how Rahdar consumes shows up too.
     * Rahdar's consumers live during a test, so they are sampled while it runs.
     */
    private static void sampleCore() {
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            snapshotCore();
        }
    }

    private static void snapshotCore() {
        PROXIES.values()
                .forEach(proxy -> proxy.consumers().forEach(c -> {
                    ObjectNode kept = JSON.createObjectNode();
                    for (String field : List.of("queueName", "filter", "browseOnly", "protocol", "addressName")) {
                        if (c.has(field)) {
                            kept.set(field, c.get(field));
                        }
                    }
                    BY_CLASS.computeIfAbsent(current, k -> new ConcurrentSkipListSet<>())
                            .add(normalise("CORE consumer | " + JSON.writeValueAsString(kept)));
                }));
    }

    static void flush() {
        if (!enabled()) {
            return;
        }
        try {
            Path dir = Files.createDirectories(Path.of(DIR));
            for (Map.Entry<String, Set<String>> e : BY_CLASS.entrySet()) {
                Files.write(dir.resolve(e.getKey() + ".txt"), e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PROXIES.values().forEach(RecordingProxy::stop);
    }

    private static String baseOf(String url) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        return slash < 0 ? url : url.substring(0, slash);
    }
}
