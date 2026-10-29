package io.github.sudoitir.rahdar.kernel.lifecycle;

import io.github.sudoitir.rahdar.kernel.settings.SettingDef;
import io.github.sudoitir.rahdar.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.rahdar.kernel.settings.SettingsContribution;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The lifecycle's own schedule, and the three settings every store's policy is made of
 * (ADR-0134): {@code lifecycle.<store>.retention}, {@code .quota} and {@code .quota-warn-percent}.
 */
@Component
public class LifecycleSettings implements SettingsContribution {

    public static final String HOUSEKEEPING_CRON = "lifecycle.housekeeping-cron";
    public static final String STORAGE_SAMPLE_CRON = "lifecycle.storage-sample-cron";

    private static final String GROUP = "Data lifecycle";

    static final String RETENTION = "retention";
    static final String QUOTA = "quota";
    static final String QUOTA_WARN_PERCENT = "quota-warn-percent";

    @Override
    public String featureId() {
        return LifecycleModule.ID;
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        HOUSEKEEPING_CRON,
                        GROUP,
                        "Purge schedule",
                        "When every store is purged to its retention, in bounded batches. Six-field cron.",
                        Kind.CRON,
                        () -> "0 30 3 * * *",
                        null),
                new SettingDef(
                        STORAGE_SAMPLE_CRON,
                        GROUP,
                        "Storage sample schedule",
                        "When table sizes are sampled for growth and storage alerts are evaluated. Six-field cron.",
                        Kind.CRON,
                        () -> "0 0 * * * *",
                        null));
    }

    /** Where plugin stores' settings live, apart from the core stores' and the lifecycle's own. */
    static final String PLUGIN_NAMESPACE = "lifecycle.plugin.";

    /**
     * The namespace of one store's settings: {@code lifecycle.<store>} for a core store and
     * {@code lifecycle.plugin.<pluginId>.<store>} for a plugin's, so a plugin can never shadow or
     * remove a core store's policy, whatever its id.
     */
    static String namespace(RegisteredStore store) {
        return (store.plugin() == null ? "lifecycle." : PLUGIN_NAMESPACE) + store.id();
    }

    static String key(RegisteredStore store, String part) {
        return namespace(store) + "." + part;
    }

    /** The policy settings of one store. */
    static List<SettingDef> policy(RegisteredStore store) {
        StoreDef def = store.def();
        String unit = def.quotaUnit() == StoreDef.QuotaUnit.BYTES ? "MiB" : "thousand rows";
        return List.of(
                new SettingDef(
                        key(store, RETENTION),
                        GROUP,
                        def.label() + " retention",
                        "Older data is purged by the next housekeeping run.",
                        Kind.DURATION,
                        () -> def.defaultRetention() == null ? SettingDef.FOREVER : format(def.defaultRetention()),
                        null,
                        format(def.minRetention()),
                        def.maxRetention() == null ? SettingDef.FOREVER : format(def.maxRetention())),
                new SettingDef(
                        key(store, QUOTA),
                        GROUP,
                        def.label() + " quota (" + unit + ")",
                        "0 means no quota. Over it, housekeeping removes the oldest data first.",
                        Kind.INT,
                        () -> Integer.toString(store.store().defaultQuota()),
                        null,
                        "0",
                        null),
                new SettingDef(
                        key(store, QUOTA_WARN_PERCENT),
                        GROUP,
                        def.label() + " quota warning (%)",
                        "Usage at this share of the quota raises a storage alert.",
                        Kind.INT,
                        () -> "80",
                        null,
                        "1",
                        "100"));
    }

    /** A duration in the settings' own syntax, in the largest whole unit: {@code 7d}, {@code 72h}. */
    static String format(Duration d) {
        if (d.toSeconds() % 86_400 == 0) {
            return d.toDays() + "d";
        }
        if (d.toSeconds() % 3_600 == 0) {
            return d.toHours() + "h";
        }
        if (d.toSeconds() % 60 == 0) {
            return d.toMinutes() + "m";
        }
        return d.toSeconds() + "s";
    }
}
