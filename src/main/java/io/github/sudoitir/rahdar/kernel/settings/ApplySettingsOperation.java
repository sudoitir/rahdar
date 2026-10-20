package io.github.sudoitir.rahdar.kernel.settings;

import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code settings.apply}: a change set of settings. It is a settings change, and also one that could weaken the gate
 * itself when it touches the approval module's own settings or those of the armed approval provider's plugin, which
 * a provider should always hold.
 */
@Component
class ApplySettingsOperation implements GatedOperation<SettingsChangeSet> {

    static final String TYPE = "settings.apply";

    /** The module whose settings bound the gate, so changing them could switch it off. */
    private static final String GATE_FEATURE = "approvals";

    private final SettingsService settings;
    private final FeatureRegistry features;
    private final ApprovalProviderRegistry providers;

    ApplySettingsOperation(SettingsService settings, FeatureRegistry features, ApprovalProviderRegistry providers) {
        this.settings = settings;
        this.features = features;
        this.providers = providers;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public Class<SettingsChangeSet> paramsType() {
        return SettingsChangeSet.class;
    }

    @Override
    public Set<Trait> traits(SettingsChangeSet params) {
        Set<Trait> traits = EnumSet.of(Trait.SETTINGS);
        String armed = providers.armedProviderId().orElse(null);
        if (params.changes().stream().anyMatch(change -> gateSensitive(change.key(), armed))) {
            traits.add(Trait.GATE_INTEGRITY);
        }
        return traits;
    }

    private boolean gateSensitive(String key, String armedProviderId) {
        return features.ownerOfSetting(key)
                        .map(owner -> GATE_FEATURE.equals(owner.id()))
                        .orElse(false)
                || armedProviderId != null && key.startsWith(armedProviderId + ".");
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.ON_APPROVAL;
    }

    @Override
    public OperationScope scope(SettingsChangeSet params) {
        return OperationScope.GLOBAL;
    }

    @Override
    public String summary(SettingsChangeSet params) {
        List<SettingChange> changes = params.changes();
        String labels = changes.stream()
                .map(change -> settings.definition(change.key()).label())
                .collect(Collectors.joining(", "));
        if (changes.size() == 1) {
            return (changes.getFirst().isReset() ? "Reset setting " : "Change setting ") + labels;
        }
        return "Change " + changes.size() + " settings: " + labels;
    }

    @Override
    public List<DisplayRow> display(SettingsChangeSet params) {
        return params.changes().stream()
                .map(change -> {
                    SettingDef def = settings.definition(change.key());
                    String to =
                            change.isReset() ? "default (" + def.defaultValue().get() + ")" : change.value();
                    return new DisplayRow(def.label(), settings.value(change.key()), to);
                })
                .toList();
    }

    @Override
    public Set<String> redactedPaths() {
        return Set.of();
    }

    @Override
    public Effect estimate(SettingsChangeSet params) {
        List<String> keys = params.changes().stream().map(SettingChange::key).toList();
        return new Effect(keys.size(), "settings", settings.etag(keys), null);
    }

    @Override
    public void replay(SettingsChangeSet params) {
        settings.apply(params.changes());
    }
}
