package io.github.sudoitir.rahdar.kernel.settings;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.GatePreview;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.HeldOperationQueries;
import io.github.sudoitir.rahdar.kernel.gate.HeldOperationView;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.PolicyRef;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDisabledException;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.replica.BusResumed;
import io.github.sudoitir.rahdar.kernel.replica.RahdarBus;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingEntity;
import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingRepository;
import io.github.sudoitir.rahdar.kernel.settings.web.SettingsViews.PendingChange;
import io.github.sudoitir.rahdar.kernel.settings.web.SettingsViews.SettingValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runtime configuration layer: a stored {@code rahdar_setting} row wins over the
 * packaged default, and deleting the row restores it. Nothing is ever seeded — an
 * absent row <em>means</em> "use the packaged default", which is what lets an upgrade
 * ship a new default instead of being pinned by a value written at first start.
 *
 * <p>The registry is assembled from the enabled modules' {@link SettingsContribution}s
 * (ADR-0047, ADR-0070). A setting of a disabled module is neither listed nor writable,
 * and its stored value is kept for when the module is enabled again.
 *
 * <p><b>Every key here applies without a restart.</b> There are two ways that happens,
 * and {@link SettingDef#apply()} is which one:
 *
 * <ul>
 *   <li><b>Pulled</b> ({@code apply == null}) — the consumer reads the value here each
 *       time it needs it, so the next read is already the new one.
 *   <li><b>Pushed</b> ({@code apply != null}) — the consumer holds the value in a
 *       {@code volatile} field because it is read too often to look up, so the
 *       registry pushes it there on boot and after every change.
 * </ul>
 *
 * <p>Cadences are neither: they are job triggers that re-read this service when
 * computing each next run (ADR-0025, ADR-0048), so a changed interval is honoured on
 * the following fire.
 *
 * <p>To add a setting: one {@link SettingDef} in the owning module's contribution and
 * its key in that module's descriptor. The API, the validation, the reset affordance,
 * the audit row and the Settings screen all follow from it.
 */
@Service
@Slf4j
public class SettingsService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RahdarSettingRepository repo;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final FeatureRegistry features;
    private final RahdarBus bus;
    private final PermissionResolver permissions;
    private final ObjectProvider<OperationGate> gate;
    /** Absent where the approval engine is not part of the application: nothing is then held. */
    private final ObjectProvider<HeldOperationQueries> held;

    private final TransactionTemplate tx;

    /** Whether the stored values have been pushed to their holders once, at boot. */
    private boolean pushed;

    /**
     * Insertion-ordered: this is also the order the settings screen renders. Immutable at rest,
     * copy-on-write (design.md, task 6.4): the built-in keys never change after the constructor
     * runs, and {@link #addSettings} / {@link #removeSettings} swap in a whole new map
     * so a concurrent read never sees a partially-updated registry.
     */
    private volatile Map<String, SettingDef> registry = new LinkedHashMap<>();

    /**
     * Every stored override, refreshed on boot and after each write. Reads are on the
     * scheduling hot path — a trigger asks for its interval on every fire — and writes
     * are the only thing that can invalidate this, all through {@link #refreshOverrides()}.
     */
    private volatile Map<String, String> overrides = Map.of();

    /**
     * The write permission of every key registered through {@link #addSettings} with one other than
     * {@code settings:write}. Such a key belongs to another screen (the Data page's retention
     * policies, ADR-0134), so {@link #effective()} does not list it.
     */
    private volatile Map<String, String> writePermissions = Map.of();

    public SettingsService(
            RahdarSettingRepository repo,
            AuditService audit,
            ActorResolver actorResolver,
            FeatureRegistry features,
            RahdarBus bus,
            PermissionResolver permissions,
            ObjectProvider<OperationGate> gate,
            ObjectProvider<HeldOperationQueries> held,
            PlatformTransactionManager transactions,
            List<SettingsContribution> contributions) {
        this.repo = repo;
        this.audit = audit;
        this.actorResolver = actorResolver;
        this.features = features;
        this.bus = bus;
        this.permissions = permissions;
        this.gate = gate;
        this.held = held;
        this.tx = new TransactionTemplate(transactions);
        for (FeatureDescriptor module : features.enabled()) {
            for (SettingsContribution contribution : contributions) {
                if (contribution.featureId().equals(module.id())) {
                    register(module, contribution);
                }
            }
            for (String key : module.settingKeys()) {
                if (!registry.containsKey(key)) {
                    throw new IllegalStateException("Module '" + module.id() + "' declares setting '" + key
                            + "' but contributes no definition");
                }
            }
        }
        registry = Collections.unmodifiableMap(registry);
    }

    private void register(FeatureDescriptor module, SettingsContribution contribution) {
        for (SettingDef def : contribution.settings()) {
            if (!module.settingKeys().contains(def.key())) {
                throw new IllegalStateException("Setting '" + def.key() + "' is contributed by '" + module.id()
                        + "' but not declared in its descriptor");
            }
            if (registry.putIfAbsent(def.key(), def) != null) {
                throw new IllegalStateException("Setting '" + def.key() + "' is contributed twice");
            }
        }
    }

    /**
     * Activates settings registered at runtime rather than by a module descriptor: a plugin's own
     * {@link SettingDef}s (design.md, task 6.4, with {@code namespace} its plugin id) and the
     * retention policies of the data lifecycle (ADR-0134). Every key must be namespaced under
     * {@code <namespace>.} and must not already be registered, or the whole call
     * fails without registering any of them. Once registered, each definition with an {@code apply}
     * is pushed once with its current effective value (the packaged default, since a freshly
     * installed plugin has no stored override yet) so a pushed-style consumer's cache is warm
     * before traffic reaches it — and if that first push throws, the registration is rolled back
     * and the exception rethrown, so this plugin's activation fails alone and the registry is left
     * exactly as it was.
     *
     * <p>A key's stored override, if an operator set one while it was last registered, takes effect
     * at once, without re-applying any other setting.
     *
     * <p>{@code writePermission} is what {@link #put} and {@link #reset} demand for these keys.
     *
     * <p>A second call for the same {@code namespace} supersedes the first rather than colliding
     * with it: the Instant activation class (design.md §5) attaches a new version's bridges before
     * the old version's detach runs, so both briefly hold the same id. {@code namespace}'s own
     * currently-registered keys are dropped from the collision check before the incoming ones are
     * added, the same way {@link FeatureRegistry
     * FeatureRegistry#addPlugin} excludes its own immediately-prior version.
     */
    public synchronized void addSettings(String namespace, List<SettingDef> defs, String writePermission) {
        Map<String, SettingDef> previous = registry;
        Map<String, String> previousPermissions = writePermissions;
        Map<String, SettingDef> next = new LinkedHashMap<>(previous);
        Map<String, String> nextPermissions = new LinkedHashMap<>(previousPermissions);
        next.keySet().removeIf(key -> key.startsWith(namespace + "."));
        nextPermissions.keySet().removeIf(key -> key.startsWith(namespace + "."));
        for (SettingDef def : defs) {
            if (!def.key().startsWith(namespace + ".")) {
                throw new IllegalArgumentException(
                        "Setting '" + def.key() + "' is not namespaced under '" + namespace + ".'");
            }
            if (next.putIfAbsent(def.key(), def) != null) {
                throw new IllegalStateException("Setting '" + def.key() + "' is already registered");
            }
            if (!SettingsPermissions.SETTINGS_WRITE.equals(writePermission)) {
                nextPermissions.put(def.key(), writePermission);
            }
        }
        registry = Collections.unmodifiableMap(next);
        writePermissions = Map.copyOf(nextPermissions);
        Map<String, String> previousOverrides = overrides;
        Map<String, String> withStored = new LinkedHashMap<>(previousOverrides);
        for (RahdarSettingEntity row :
                repo.findAllById(defs.stream().map(SettingDef::key).toList())) {
            withStored.put(row.getKey(), unquote(row.getValue()));
        }
        overrides = Map.copyOf(withStored);
        try {
            for (SettingDef def : defs) {
                if (def.apply() != null) {
                    def.apply().accept(this);
                }
            }
        } catch (RuntimeException e) {
            registry = previous;
            writePermissions = previousPermissions;
            overrides = previousOverrides;
            throw e;
        }
    }

    /**
     * Deactivates every setting registered under {@code namespace}, dropping its {@code apply} lambdas
     * with it so they stop pinning the plugin's classloader. The stored override rows, if any, are
     * left in place — the same "an absent module's stored value is kept" contract the constructor
     * already gives a disabled built-in module.
     */
    public synchronized void removeSettings(String namespace) {
        Map<String, SettingDef> next = new LinkedHashMap<>(registry);
        next.keySet().removeIf(key -> key.startsWith(namespace + "."));
        Map<String, String> nextPermissions = new LinkedHashMap<>(writePermissions);
        nextPermissions.keySet().removeIf(key -> key.startsWith(namespace + "."));
        registry = Collections.unmodifiableMap(next);
        writePermissions = Map.copyOf(nextPermissions);
    }

    /** The permission {@link #put} and {@link #reset} demand for {@code key}. */
    public String writePermission(String key) {
        return writePermissions.getOrDefault(key, SettingsPermissions.SETTINGS_WRITE);
    }

    // ---- typed reads ------------------------------------------------------

    public Duration duration(String key) {
        return Duration.parse(toIso(value(key)));
    }

    public int intValue(String key) {
        return Integer.parseInt(value(key).trim());
    }

    public boolean bool(String key) {
        return Boolean.parseBoolean(value(key).trim());
    }

    /** The effective raw value: the stored override, else the packaged default. */
    public String value(String key) {
        String override = overrides.get(key);
        return override != null ? override : requireKnown(key).defaultValue().get();
    }

    // ---- read / write -----------------------------------------------------

    /**
     * Every operator-tunable key of the Settings screen: its effective value, its default, its category, how to
     * render it, and the changes waiting for approval. Keys another screen owns (a different write permission)
     * are left out.
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.SettingsPermissions).SETTINGS_READ)")
    public Map<String, SettingValue> effective() {
        Map<String, String> stored = overrides;
        Map<String, List<PendingChange>> pending = pendingChanges();
        Map<String, SettingValue> out = new LinkedHashMap<>();
        registry.forEach((key, spec) -> {
            if (writePermissions.containsKey(key)) {
                return;
            }
            String defaultValue = spec.defaultValue().get();
            String override = stored.get(key);
            FeatureDescriptor owner = features.ownerOfSetting(key).orElse(null);
            out.put(
                    key,
                    new SettingValue(
                            override != null ? override : defaultValue,
                            override != null,
                            defaultValue,
                            spec.group(),
                            spec.label(),
                            spec.hint(),
                            spec.kind().name(),
                            owner != null ? owner.id() : key.substring(0, Math.max(0, key.indexOf('.'))),
                            owner != null ? owner.title() : spec.group(),
                            spec.kind() == SettingDef.Kind.INT && spec.min() == null ? "1" : spec.min(),
                            spec.max(),
                            pending.getOrDefault(key, List.of())));
        });
        return out;
    }

    /** The open {@code settings.apply} requests, by the keys they would change. */
    private Map<String, List<PendingChange>> pendingChanges() {
        Map<String, List<PendingChange>> byKey = new LinkedHashMap<>();
        HeldOperationQueries queries = held.getIfAvailable();
        if (queries == null) {
            return Map.of();
        }
        for (HeldOperationView view : queries.openByType(ApplySettingsOperation.TYPE)) {
            SettingsChangeSet set;
            try {
                set = JSON.readValue(view.params(), SettingsChangeSet.class);
            } catch (RuntimeException e) {
                log.warn("A held settings change {} could not be read: {}", view.id(), e.toString());
                continue;
            }
            for (SettingChange change : set.changes()) {
                byKey.computeIfAbsent(change.key(), k -> new ArrayList<>())
                        .add(new PendingChange(
                                view.id(),
                                change.value(),
                                change.isReset(),
                                view.requester().username(),
                                view.requestedAt()));
            }
        }
        return byKey;
    }

    /** Sets one setting: a change set of one. */
    public void put(String key, String rawValue) {
        apply(List.of(SettingChange.set(key, rawValue)));
    }

    /** Clears one override so the packaged default takes over again: a change set of one. */
    public void reset(String key) {
        apply(List.of(SettingChange.reset(key)));
    }

    /**
     * Applies several changes and resets together or not at all. A change set is checked as a whole before anything
     * is written, so one invalid value leaves every setting as it was. It is the gated operation
     * {@code settings.apply}: with an approval provider installed it may be held, and the stored values it was
     * approved against must still be the same when it runs.
     *
     * @throws SettingsInvalidException when a value is not allowed, naming each setting
     */
    @Gated(ApplySettingsOperation.TYPE)
    public void apply(List<SettingChange> changes) {
        SettingsChangeSet set = prepare(changes);
        gate.getObject().run(Operation.of(set), () -> {
            write(set.changes());
            return null;
        });
    }

    /** Says what {@link #apply} would do now, without changing or holding anything. */
    public SettingsChangePreview preview(List<SettingChange> changes) {
        SettingsChangeSet set;
        try {
            set = prepare(changes);
        } catch (SettingsInvalidException e) {
            return new SettingsChangePreview(null, null, null, e.fieldErrors());
        }
        GatePreview preview = gate.getObject().preview(Operation.of(set));
        return new SettingsChangePreview(preview.outcome(), policyLabel(preview.policy()), preview.reason(), Map.of());
    }

    private static String policyLabel(PolicyRef policy) {
        if (policy == null) {
            return null;
        }
        return policy.name() != null ? policy.name() : policy.id();
    }

    /** Sorts, normalizes, authorizes and validates a change set; the same checks run again on replay. */
    private SettingsChangeSet prepare(List<SettingChange> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("A change set needs at least one setting.");
        }
        Map<String, SettingChange> byKey = authorizedByKey(changes);
        Map<String, String> errors = new LinkedHashMap<>();
        for (SettingChange change : byKey.values()) {
            SettingDef spec = requireKnown(change.key());
            if (!change.isReset()) {
                validationError(spec, change).ifPresent(message -> errors.put(change.key(), message));
            }
        }
        if (!errors.isEmpty()) {
            throw new SettingsInvalidException(errors);
        }
        return new SettingsChangeSet(new ArrayList<>(byKey.values()));
    }

    /** The changes keyed and sorted by setting, each value unquoted and its write permission checked. */
    private Map<String, SettingChange> authorizedByKey(List<SettingChange> changes) {
        Map<String, SettingChange> byKey = new TreeMap<>();
        for (SettingChange change : changes) {
            if (!permissions.can(writePermission(change.key()))) {
                throw new AccessDeniedException(
                        "Changing " + change.key() + " needs the " + writePermission(change.key()) + " permission.");
            }
            SettingChange normalized =
                    change.isReset() ? change : new SettingChange(change.key(), unquote(change.value()));
            if (byKey.put(change.key(), normalized) != null) {
                throw new IllegalArgumentException("The setting " + change.key() + " appears twice in the change set.");
            }
        }
        return byKey;
    }

    /** What is wrong with one change's value, in operator words, or empty when it is valid. */
    private Optional<String> validationError(SettingDef spec, SettingChange change) {
        try {
            validate(spec, change.value());
            return Optional.empty();
        } catch (NumberFormatException _) {
            return Optional.of(change.key() + " must be a whole number");
        } catch (DateTimeException _) {
            return Optional.of(change.key() + " must be a duration such as 30s, 15m, 12h or 7d");
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * One transaction for the whole change set, with an audit row per setting (non-negotiable #3), so the changes
     * and the record of them commit or roll back together. Validation happened before, so a rejected value never
     * becomes a transaction.
     */
    private void write(List<SettingChange> changes) {
        tx.executeWithoutResult(status -> {
            for (SettingChange change : changes) {
                SettingDef spec = requireKnown(change.key());
                String current =
                        overrides.getOrDefault(change.key(), spec.defaultValue().get());
                if (change.isReset()) {
                    AuditEvent event = audit.begin(
                            actorResolver.resolve(),
                            "RESET_SETTING",
                            "SETTING",
                            change.key(),
                            null,
                            null,
                            Map.of("from", current, "to", spec.defaultValue().get()),
                            false);
                    repo.deleteById(change.key());
                    audit.succeed(event, 1);
                } else {
                    AuditEvent event = audit.begin(
                            actorResolver.resolve(),
                            "UPDATE_SETTING",
                            "SETTING",
                            change.key(),
                            null,
                            null,
                            Map.of("from", current, "to", change.value()),
                            false);
                    String json = asJsonScalar(change.value());
                    repo.findById(change.key())
                            .ifPresentOrElse(
                                    e -> e.setValue(json),
                                    () -> repo.save(new RahdarSettingEntity(change.key(), json)));
                    audit.succeed(event, 1);
                }
            }
            repo.flush();
            changed();
        });
    }

    /** The definition of {@code key}, for describing a change. */
    public SettingDef definition(String key) {
        return requireKnown(key);
    }

    /**
     * A hash of the effective values of {@code keys} and whether each is overridden, so an approved change set
     * runs only against the state it was approved for.
     */
    public String etag(List<String> keys) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> stored = overrides;
        keys.stream().sorted().forEach(key -> {
            String line = key + (stored.containsKey(key) ? "=" : "~") + value(key) + "\n";
            sha.update(line.getBytes(StandardCharsets.UTF_8));
        });
        return HexFormat.of().formatHex(sha.digest());
    }

    /**
     * Applies the write here at once, so the writer reads its own change, and tells every replica,
     * this one included, to do the same once it has committed (ADR-0152).
     */
    private void changed() {
        refreshOverrides();
        bus.publish(new ReplicaSignal("settings", ""));
        // That read saw this transaction's rows, which nobody else can until it commits. A refresh another
        // change's signal started meanwhile read the old rows and, taking the lock after this one, would put the
        // old values back until this change's own signal arrives. Reading again once committed is last.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshOverrides();
            }
        });
    }

    /**
     * Another replica wrote a setting: re-read the overrides and push them. First of the signal's listeners,
     * so those that react to a change (the job runner re-arming a schedule) read the new values.
     */
    @EventListener(condition = "#signal.kind() == 'settings'")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional(readOnly = true)
    public void onSignal(ReplicaSignal signal) {
        refreshOverrides();
    }

    /** The bus was down: a setting may have changed in the gap. */
    @EventListener
    @Transactional(readOnly = true)
    public void onBusResumed(BusResumed resumed) {
        refreshOverrides();
    }

    /**
     * Re-read the stored overrides and push the cached ones to their holders. Runs on
     * boot and after every change, on any replica. A stored value for a key no enabled module owns is
     * left in the table and ignored.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void applyRuntime() {
        refreshOverrides();
    }

    private synchronized void refreshOverrides() {
        Map<String, String> fresh = new LinkedHashMap<>();
        for (RahdarSettingEntity row : repo.findAll()) {
            if (registry.containsKey(row.getKey())) {
                fresh.put(row.getKey(), unquote(row.getValue()));
            }
        }
        Map<String, String> previous = overrides;
        overrides = Map.copyOf(fresh);
        boolean first = !pushed;
        pushed = true;
        // Only what changed is pushed: the writer and the bus echo both refresh, and pushing a value
        // can be costly for its holder (new broker timeouts replace every broker client).
        for (SettingDef spec : registry.values()) {
            if (spec.apply() != null
                    && (first || !Objects.equals(previous.get(spec.key()), overrides.get(spec.key())))) {
                spec.apply().accept(this);
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private SettingDef requireKnown(String key) {
        SettingDef spec = registry.get(key);
        if (spec != null) {
            return spec;
        }
        features.ownerOfSetting(key)
                .filter(owner -> !features.isEnabled(owner.id()))
                .ifPresent(owner -> {
                    throw new FeatureDisabledException(owner);
                });
        throw new IllegalArgumentException("Unknown setting key: " + key);
    }

    /** Rejects {@code value} for {@code key} exactly as {@link #put} would, without writing it. */
    public void check(String key, String value) {
        validate(requireKnown(key), unquote(value));
    }

    /** Rejects a value outside its kind's syntax or its bounds, naming the allowed range. */
    static void validate(SettingDef spec, String value) {
        switch (spec.kind()) {
            case DURATION -> validateDuration(spec, value);
            case DURATION_OR_OFF -> validateDurationOrOff(spec, value);
            case INT -> validateInt(spec, value);
            case CRON -> validateCron(spec.key(), value.trim());
            case BOOLEAN -> validateBoolean(spec.key(), value.trim());
        }
    }

    private static void validateDurationOrOff(SettingDef spec, String value) {
        if (Duration.parse(toIso(value)).isNegative()) {
            throw new IllegalArgumentException(spec.key() + " must be zero or a positive duration");
        }
    }

    private static void validateDuration(SettingDef spec, String value) {
        boolean foreverAllowed = SettingDef.FOREVER.equals(spec.max());
        if (SettingDef.FOREVER.equalsIgnoreCase(value.trim())) {
            if (!foreverAllowed) {
                throw outOfRange(spec);
            }
            return;
        }
        Duration d = Duration.parse(toIso(value));
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(spec.key() + " must be a positive duration");
        }
        boolean belowMin = spec.min() != null && d.compareTo(Duration.parse(toIso(spec.min()))) < 0;
        boolean aboveMax = spec.max() != null && !foreverAllowed && d.compareTo(Duration.parse(toIso(spec.max()))) > 0;
        if (belowMin || aboveMax) {
            throw outOfRange(spec);
        }
    }

    private static void validateInt(SettingDef spec, String value) {
        int n = Integer.parseInt(value.trim());
        int min = spec.min() == null ? 1 : Integer.parseInt(spec.min());
        if (n < min || spec.max() != null && n > Integer.parseInt(spec.max())) {
            if (spec.min() == null && spec.max() == null) {
                throw new IllegalArgumentException(spec.key() + " must be at least 1");
            }
            throw outOfRange(spec);
        }
    }

    private static void validateBoolean(String key, String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
    }

    private static IllegalArgumentException outOfRange(SettingDef spec) {
        String defaultMin = spec.kind() == SettingDef.Kind.INT ? "1" : "more than 0";
        String min = spec.min() != null ? spec.min() : defaultMin;
        String max = spec.max() != null ? spec.max() : "no limit";
        return new IllegalArgumentException(spec.key() + " must be between " + min + " and " + max);
    }

    /**
     * A cron that fires more often than once a minute is rejected rather than clamped.
     * These schedules drive bulk {@code DELETE}s and DDL; a typo in the seconds field
     * would otherwise turn a nightly trim into a permanent one.
     */
    private static void validateCron(String key, String value) {
        if (!CronExpression.isValidExpression(value)) {
            throw new IllegalArgumentException(key + " must be a six-field cron expression");
        }
        CronExpression cron = CronExpression.parse(value);
        ZonedDateTime from = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime first = cron.next(from);
        ZonedDateTime second = first == null ? null : cron.next(first);
        if (first != null && second != null && Duration.between(first, second).toSeconds() < 60) {
            throw new IllegalArgumentException(key + " must not fire more than once a minute");
        }
    }

    /** Accept both {@code "5s"} (Spring style) and {@code "PT5S"} (ISO-8601) duration strings. */
    public static String toIso(String value) {
        String v = value.trim();
        if (v.startsWith("P") || v.startsWith("p")) {
            return v.toUpperCase();
        }
        if (v.endsWith("ms")) {
            return "PT" + (Long.parseLong(v.substring(0, v.length() - 2).trim()) / 1000.0) + "S";
        }
        if (v.endsWith("s")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "S";
        }
        if (v.endsWith("m")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "M";
        }
        if (v.endsWith("h")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "H";
        }
        if (v.endsWith("d")) {
            return "P" + v.substring(0, v.length() - 1).trim() + "D";
        }
        return "PT" + v + "S";
    }

    /** Encode a bare value as a JSON scalar for the {@code jsonb} column: a number stays bare, anything else is quoted. */
    private static String asJsonScalar(String value) {
        String v = value.trim();
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            return v;
        }
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    /** Stored values are JSON scalars; strip the quotes from a JSON string. */
    private static String unquote(String jsonScalar) {
        String v = jsonScalar.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"");
        }
        return v;
    }
}
