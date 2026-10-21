package io.github.sudoitir.rahdar.feature.plugins;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.plugin.PluginLifecycleListener;
import io.github.sudoitir.rahdar.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.rahdar.kernel.plugin.internal.host.PluginSummary;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Closes the audit row an activation began once the host reports how it ended — activation runs
 * on its own thread, after the request that began it has answered — and records what the host
 * does on its own at boot. Every step is also a {@code plugin-lifecycle} line on stdout, which
 * the host writes itself: plugins share the database role, so a plugin could edit
 * {@code audit_event}, but not a log line already written (design.md §7).
 */
@Component
@RequiredArgsConstructor
class PluginAuditTrail implements PluginLifecycleListener {

    private static final Set<String> FINAL = Set.of("succeeded", "failed", "needs-restart", "incompatible");

    private final AuditService audit;
    /** Lazily: the host calls this listener, so it cannot be a constructor dependency of it. */
    private final ObjectProvider<PluginHost> host;

    private final Map<String, AuditEvent> activating = new ConcurrentHashMap<>();

    /** Called before the host starts the activation, so its outcome can never arrive first. */
    void activationBegan(String pluginId, AuditEvent event) {
        activating.put(pluginId, event);
    }

    /** The host refused before starting; nothing will report an outcome. */
    void activationRefused(String pluginId) {
        activating.remove(pluginId);
    }

    @Override
    public void onStep(
            String pluginId,
            String fromVersion,
            String toVersion,
            String sha256,
            String actor,
            String step,
            String outcome) {
        if (!FINAL.contains(outcome)) {
            return;
        }
        AuditEvent event = activating.remove(pluginId);
        if (event != null) {
            finish(event, pluginId, outcome, step);
            return;
        }
        if (Actor.SYSTEM.equals(actor)) {
            // Boot: nobody asked, so the row is begun and finished here.
            AuditEvent boot = audit.begin(
                    Actor.ofSystem(),
                    "PLUGIN_" + step.toUpperCase(Locale.ROOT).replace('-', '_'),
                    "plugin",
                    pluginId,
                    null,
                    null,
                    Map.of("version", toVersion == null ? "" : toVersion),
                    false);
            finish(boot, pluginId, outcome, step);
        }
    }

    private void finish(AuditEvent event, String pluginId, String outcome, String step) {
        if ("succeeded".equals(outcome)) {
            audit.succeed(event, 1);
            return;
        }
        String cause =
                host.getObject().status(pluginId).map(PluginSummary::failure).orElse(null);
        audit.fail(event, outcome + " at " + step + (cause == null ? "" : ": " + cause));
    }
}
