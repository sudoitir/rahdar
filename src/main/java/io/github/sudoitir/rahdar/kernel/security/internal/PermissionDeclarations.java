package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.plugin.PluginBridge;
import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.security.internal.GuardPermissions.GuardRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * The permissions Rahdar's own guards name (read once at startup) and each active plugin's guards
 * and manifest reference (read on activation, dropped on deactivation), checked against
 * {@link FeatureRegistry#catalogue()} (operational-health spec). Never throws: a mismatch is
 * reported, not enforced.
 */
@Component
class PermissionDeclarations implements PluginBridge {

    private static final String RAHDAR_PACKAGE = "io.github.sudoitir.rahdar.";

    private final FeatureRegistry registry;
    private volatile List<GuardRef> rahdar = List.of();
    private final Map<String, List<GuardRef>> plugins = new ConcurrentHashMap<>();

    PermissionDeclarations(FeatureRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    void onReady(ApplicationReadyEvent event) {
        rahdar = scan(event.getApplicationContext(), RAHDAR_PACKAGE, getClass().getClassLoader());
    }

    @Override
    public void attach(PluginHandle handle) {
        PluginDescriptor descriptor = handle.descriptor();
        List<GuardRef> refs = new ArrayList<>(
                scan(handle.applicationContext(), descriptor.basePackage() + ".", handle.classLoader()));
        String source = "plugin " + descriptor.id() + " manifest";
        descriptor.mcpTools().forEach(t -> refs.add(new GuardRef(t.permission(), source, false)));
        descriptor.metrics().forEach(m -> refs.add(new GuardRef(m.permission(), source, false)));
        plugins.put(handle.id(), List.copyOf(refs));
    }

    @Override
    public void detach(PluginHandle handle) {
        plugins.remove(handle.id());
    }

    /** How many permission references Rahdar's own guards make; zero only before startup completes. */
    int rahdarReferences() {
        return rahdar.size();
    }

    /** Every mismatch, each a sentence naming the permission and where it comes from; empty when healthy. */
    List<String> mismatches() {
        Map<String, CatalogueEntry> catalogue = registry.catalogue().stream()
                .collect(Collectors.toMap(CatalogueEntry::action, Function.identity(), (a, b) -> a));
        List<String> found = new ArrayList<>();
        Stream.concat(Stream.of(rahdar), plugins.values().stream())
                .flatMap(List::stream)
                .forEach(ref -> {
                    CatalogueEntry entry = catalogue.get(ref.permission());
                    if (entry == null) {
                        found.add("'" + ref.permission() + "' is checked by " + ref.location()
                                + " but is not in the permission catalogue");
                    } else if (entry.scope() == PermissionScope.GLOBAL && ref.withCluster()) {
                        found.add("'" + ref.permission() + "' is declared at global scope but " + ref.location()
                                + " checks it against a cluster");
                    }
                });
        catalogue.values().stream()
                .filter(e -> e.description() == null || e.description().isBlank())
                .forEach(e -> found.add(describe(e) + " has no description"));
        catalogue.values().forEach(e -> {
            if (e.scope() == PermissionScope.RESOURCE && e.resourceTypes().isEmpty()) {
                found.add(describe(e) + " acts on a resource but names no kind");
            }
            e.requires().stream()
                    .filter(required -> !catalogue.containsKey(required))
                    .sorted()
                    .forEach(required -> found.add(
                            describe(e) + " requires '" + required + "', which is not in the permission catalogue"));
        });
        requirementCycles(catalogue).forEach(cycle -> found.add("Permissions require each other: " + cycle));
        return found.stream().distinct().toList();
    }

    /** How a catalogue entry is named in a mismatch: its action and the feature that declares it. */
    private static String describe(CatalogueEntry entry) {
        return "'" + entry.action() + "' of " + entry.featureId();
    }

    /** Each cycle of the requires graph once, written as the chain that closes it. */
    private static List<String> requirementCycles(Map<String, CatalogueEntry> catalogue) {
        Set<String> done = new HashSet<>();
        Set<String> reported = new HashSet<>();
        List<String> cycles = new ArrayList<>();
        for (String start : new TreeSet<>(catalogue.keySet())) {
            walk(start, catalogue, new ArrayList<>(), done, reported, cycles);
        }
        return cycles;
    }

    private static void walk(
            String action,
            Map<String, CatalogueEntry> catalogue,
            List<String> path,
            Set<String> done,
            Set<String> reported,
            List<String> cycles) {
        int at = path.indexOf(action);
        if (at >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(at, path.size()));
            if (reported.add(String.join(",", new TreeSet<>(cycle)))) {
                cycle.add(action);
                cycles.add(String.join(" -> ", cycle));
            }
            return;
        }
        CatalogueEntry entry = catalogue.get(action);
        if (entry == null || done.contains(action)) {
            return;
        }
        path.add(action);
        for (String required : new TreeSet<>(entry.requires())) {
            walk(required, catalogue, path, done, reported, cycles);
        }
        path.remove(path.size() - 1);
        done.add(action);
    }

    private static List<GuardRef> scan(ApplicationContext context, String packagePrefix, ClassLoader loader) {
        List<GuardRef> refs = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name, false);
            if (type == null) {
                continue;
            }
            Class<?> user = ClassUtils.getUserClass(type);
            if (user.getName().startsWith(packagePrefix)) {
                refs.addAll(GuardPermissions.of(user, loader));
            }
        }
        return refs;
    }
}
