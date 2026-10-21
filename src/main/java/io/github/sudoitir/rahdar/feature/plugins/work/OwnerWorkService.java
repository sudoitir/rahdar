package io.github.sudoitir.rahdar.feature.plugins.work;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.OwnerWorkEntity;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.OwnerWorkRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.plugin.PluginPurged;
import io.github.sudoitir.rahdar.kernel.plugin.PluginScopedBeans;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The host side of {@link OwnerWork}. Every method takes the plugin id from the bound object, never from the
 * plugin's arguments, so one plugin cannot publish or enable another's work. Needs are checked against the
 * owner's account as it stands now, through the same resolver as every request, so a role removed, a team
 * left or a share withdrawn is seen at the next check.
 */
@Service
@RequiredArgsConstructor
public class OwnerWorkService implements PluginScopedBeans {

    static final String BEAN_NAME = "pluginOwnerWork";
    private static final String TARGET_TYPE = "PLUGIN_OWNER_WORK";
    private static final String PLUGIN = "plugin";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final int MAX_NEEDS = 100;

    private final OwnerWorkRepository work;
    private final OperatorHandoff handoff;
    private final PermissionResolver perm;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new OwnerWork(this, pluginId));
    }

    @Transactional
    WorkStatus publish(String pluginId, String key, UUID ownerUserId, List<WorkNeed> needs) {
        validate(key, ownerUserId, needs);
        List<String> missing = missing(ownerUserId, needs);
        AuditEvent event = audit.begin(
                actor(pluginId, ownerUserId),
                "PLUGIN_WORK_PUBLISH",
                TARGET_TYPE,
                pluginId + "/" + key,
                null,
                null,
                Map.of(PLUGIN, pluginId, "needs", needs.size()),
                false);
        if (!missing.isEmpty()) {
            String why = refusal(ownerUserId, missing);
            audit.fail(event, why);
            throw new WorkRefusedException(why);
        }
        OwnerWorkEntity row = work.findByPluginIdAndKey(pluginId, key).orElseGet(() -> {
            OwnerWorkEntity fresh = new OwnerWorkEntity();
            fresh.setPluginId(pluginId);
            fresh.setKey(key);
            fresh.setCreatedAt(clock.instant());
            return fresh;
        });
        row.setOwnerUserId(ownerUserId);
        row.setNeeds(json.writeValueAsString(needs));
        row.setState(WorkState.ACTIVE);
        row.setReason(null);
        row.setUpdatedAt(clock.instant());
        audit.succeed(event, 1);
        return view(work.save(row));
    }

    @Transactional
    WorkStatus beforeRun(String pluginId, String key) {
        OwnerWorkEntity row = require(pluginId, key);
        if (row.getState() == WorkState.SUSPENDED) {
            return view(row);
        }
        List<WorkNeed> needs = needsOf(row);
        List<String> missing = missing(row.getOwnerUserId(), needs);
        if (missing.isEmpty()) {
            return view(row);
        }
        String why = refusal(row.getOwnerUserId(), missing);
        AuditEvent event = audit.begin(
                actor(pluginId, row.getOwnerUserId()),
                "PLUGIN_WORK_SUSPEND",
                TARGET_TYPE,
                pluginId + "/" + key,
                null,
                null,
                Map.of(PLUGIN, pluginId, "reason", why),
                false);
        row.setState(WorkState.SUSPENDED);
        row.setReason(why + " It stays suspended until the owner holds them again and the work is enabled.");
        row.setUpdatedAt(clock.instant());
        audit.succeed(event, 1);
        return view(work.save(row));
    }

    @Transactional
    WorkStatus enable(String pluginId, String key) {
        OwnerWorkEntity row = require(pluginId, key);
        if (row.getState() == WorkState.ACTIVE) {
            return view(row);
        }
        List<String> missing = missing(row.getOwnerUserId(), needsOf(row));
        AuditEvent event = audit.begin(
                actor(pluginId, row.getOwnerUserId()),
                "PLUGIN_WORK_ENABLE",
                TARGET_TYPE,
                pluginId + "/" + key,
                null,
                null,
                Map.of(PLUGIN, pluginId),
                false);
        if (!missing.isEmpty()) {
            String why = refusal(row.getOwnerUserId(), missing);
            audit.fail(event, why);
            throw new WorkRefusedException(why);
        }
        row.setState(WorkState.ACTIVE);
        row.setReason(null);
        row.setUpdatedAt(clock.instant());
        audit.succeed(event, 1);
        return view(work.save(row));
    }

    @Transactional(readOnly = true)
    Optional<WorkStatus> status(String pluginId, String key) {
        return work.findByPluginIdAndKey(pluginId, key).map(this::view);
    }

    @Transactional(readOnly = true)
    List<WorkStatus> all(String pluginId) {
        return work.findByPluginIdOrderByKey(pluginId).stream().map(this::view).toList();
    }

    @Transactional
    boolean withdraw(String pluginId, String key) {
        Optional<OwnerWorkEntity> row = work.findByPluginIdAndKey(pluginId, key);
        if (row.isEmpty()) {
            return false;
        }
        AuditEvent event = audit.begin(
                actor(pluginId, row.get().getOwnerUserId()),
                "PLUGIN_WORK_WITHDRAW",
                TARGET_TYPE,
                pluginId + "/" + key,
                null,
                null,
                Map.of(PLUGIN, pluginId),
                false);
        work.delete(row.get());
        audit.succeed(event, 1);
        return true;
    }

    /** Purging a plugin deletes its work. */
    @EventListener
    public void onPurged(PluginPurged purged) {
        work.deleteByPluginId(purged.pluginId());
    }

    // ---- helpers -----------------------------------------------------------

    private static void validate(String key, UUID ownerUserId, List<WorkNeed> needs) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new WorkRefusedException("A work key is 1 to 200 characters of letters, digits and . _ : -.");
        }
        if (ownerUserId == null) {
            throw new WorkRefusedException("Work runs as a user: name its owner.");
        }
        if (needs == null || needs.size() > MAX_NEEDS) {
            throw new WorkRefusedException("Declare what the work needs, at most " + MAX_NEEDS + " needs.");
        }
    }

    private OwnerWorkEntity require(String pluginId, String key) {
        return work.findByPluginIdAndKey(pluginId, key)
                .orElseThrow(() -> new WorkRefusedException("No work is published under the key '" + key + "'."));
    }

    /** Each need the owner does not hold, in words; every need when the owner is unknown or disabled. */
    private List<String> missing(UUID ownerUserId, List<WorkNeed> needs) {
        Optional<OperatorHandoff.Operator> owner = handoff.forUser(ownerUserId);
        if (owner.isEmpty()) {
            return needs.stream().map(OwnerWorkService::describe).toList();
        }
        RahdarPrincipal principal = owner.get().principal();
        return needs.stream()
                .filter(need -> !holds(principal, need))
                .map(OwnerWorkService::describe)
                .toList();
    }

    private boolean holds(RahdarPrincipal principal, WorkNeed need) {
        return need.resource() == null
                ? perm.can(principal, need.clusterId(), need.permission())
                : perm.can(principal, need.clusterId(), need.resource(), need.permission());
    }

    private String refusal(UUID ownerUserId, List<String> missing) {
        String who = handoff.forUser(ownerUserId).isPresent()
                ? "The owner does not hold "
                : "The owner " + ownerUserId + " is unknown or disabled, so none of these is held: ";
        return who + String.join(", ", missing) + ".";
    }

    /** {@code message:send on address billing.in of cluster 1234}, or {@code user:admin}. */
    private static String describe(WorkNeed need) {
        StringBuilder text = new StringBuilder(need.permission());
        if (need.resource() != null) {
            text.append(" on ")
                    .append(need.resource().type().name().toLowerCase(Locale.ROOT))
                    .append(' ')
                    .append(need.resource().name());
        }
        if (need.clusterId() != null) {
            text.append(need.resource() == null ? " on cluster " : " of cluster ")
                    .append(need.clusterId());
        }
        return text.toString();
    }

    private List<WorkNeed> needsOf(OwnerWorkEntity row) {
        return json.readValue(row.getNeeds(), new TypeReference<List<WorkNeed>>() {});
    }

    private WorkStatus view(OwnerWorkEntity row) {
        return new WorkStatus(
                row.getKey(), row.getOwnerUserId(), needsOf(row), row.getState(), row.getReason(), row.getUpdatedAt());
    }

    /** The plugin acting for its owner; an owner whose account is gone is not named, so the audit row can be written. */
    private Actor actor(String pluginId, UUID ownerUserId) {
        UUID known = handoff.forUser(ownerUserId).map(o -> o.actor().userId()).orElse(null);
        return new Actor("plugin " + pluginId, null, null, known);
    }
}
