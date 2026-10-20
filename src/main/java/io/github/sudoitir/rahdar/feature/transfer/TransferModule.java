package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;

/** Cross-broker message transfer. Module descriptor (ADR-0070, ADR-0097). */
public final class TransferModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("transfer")
            .title("Message transfer")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("messages")
            .settingKey(TransferSettings.BATCH_SIZE)
            .settingKey(TransferSettings.MESSAGES_PER_SECOND)
            .settingKey(TransferSettings.MAX_CONCURRENT_RUNS)
            .settingKey(TransferSettings.CAPACITY_THRESHOLD_PERCENT)
            .settingKey(TransferSettings.CAPACITY_WAIT)
            .apiPrefix("/api/v1/clusters/{clusterId}/transfers")
            .streamTopic(TopicDef.data(TransferRunner.TOPIC, Permissions.SUBSCRIPTION_READ))
            .build();

    private TransferModule() {}
}
