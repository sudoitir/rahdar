package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * What a plugin wants delivered (ADR-0111, ADR-0112).
 *
 * @param key the plugin's own name for the registration, 1 to 200 characters of letters, digits,
 *     {@code . _ : -}; registering the same key again replaces the registration
 * @param clusterId a registered cluster
 * @param source the subscription: on every serving node of the cluster when its kind keeps messages per
 *     node (Artemis queues), once for the whole cluster when its kind is a partitioned log (Kafka groups)
 * @param mode tap or consume
 * @param concurrency how many messages the plugin handles at once on each serving node: 1 to 32 for
 *     {@link RegistrationMode#CONSUME}, exactly 1 for {@link RegistrationMode#TAP}. Rahdar opens that
 *     many consumers per node, and each holds at most one unacknowledged message, so a slow plugin
 *     is handed fewer messages and the rest wait on the subscription. Messages that share a group id
 *     ({@code JMSXGroupID}) stay in order at any concurrency, because the broker hands a group to
 *     one consumer (ADR-0112)
 * @param actingUserId the Rahdar user the registration acts for, whose permissions on the subscription (through a
 *     role, a team or a share) it needs now and on every later pass
 */
@PluginApi
public record RegistrationSpec(
        String key, UUID clusterId, SubscriptionId source, RegistrationMode mode, int concurrency, UUID actingUserId) {}
