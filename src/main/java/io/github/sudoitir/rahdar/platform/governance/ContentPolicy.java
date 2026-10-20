package io.github.sudoitir.rahdar.platform.governance;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The content policy (ADR-0075). Every path that serialises or stores message content passes it
 * through here first and handles only the {@link GovernedMessage} that comes back.
 */
@Component
@RequiredArgsConstructor
public class ContentPolicy {

    private final PolicyStore store;
    private final PermissionResolver permissions;
    /**
     * Resolved per call, not at construction: a settings contribution can itself depend on something that governs
     * content (request-reply's correlator does), and the settings service builds every contribution when it starts.
     */
    private final ObjectProvider<SettingsService> settings;

    private final FindingsRecorder findings;
    private final ObjectMapper mapper;

    /**
     * The caller's context for one queue and the address it is bound to: whether they hold
     * {@code message:clear} on that queue.
     */
    public GovernContext context(UUID clusterId, String address, String queueName) {
        return new GovernContext(
                clusterId,
                address,
                permissions.can(clusterId, ResourceRef.subscription(queueName), GovernancePermissions.MESSAGE_CLEAR));
    }

    /** Whether the caller holds {@code message:clear} on every queue the pattern can match. */
    public boolean mayClear(UUID clusterId, String queuePattern) {
        return permissions.canOnAll(
                clusterId, ResourceType.SUBSCRIPTION, queuePattern, GovernancePermissions.MESSAGE_CLEAR);
    }

    /** Govern a message for the caller described by {@code context}. */
    public GovernedMessage govern(GovernContext context, MessageContent content) {
        return engine().govern(context, content, false);
    }

    /** Govern a message for storage: masked for everyone, with sealable originals collected. */
    public GovernedMessage governForStorage(UUID clusterId, String address, MessageContent content) {
        return engine().govern(GovernContext.masked(clusterId, address), content, true);
    }

    /** Mask what the detectors find in free text. No rules, no clear access. */
    public String governText(String text) {
        return engine().governText(text);
    }

    /** Whether any masking rule names this header or property on some address. */
    public boolean classifies(Location location, String name) {
        return store.current().classifiesAnywhere(location, name);
    }

    public int version() {
        return store.current().version();
    }

    private PolicyEngine engine() {
        return new PolicyEngine(
                store.current(),
                settings.getObject().intValue(GovernanceSettings.SCAN_LIMIT),
                mapper,
                findings::recordFinding);
    }
}
