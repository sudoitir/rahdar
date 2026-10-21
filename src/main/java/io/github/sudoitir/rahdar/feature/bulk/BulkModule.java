package io.github.sudoitir.rahdar.feature.bulk;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;

/** Bulk queue operations. Module descriptor (ADR-0070, ADR-0093). */
public final class BulkModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("bulk")
            .title("Bulk operations")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("resources")
            .require("messages")
            .apiPrefix("/api/v1/clusters/{clusterId}/bulk")
            .streamTopic(TopicDef.data(BulkRunner.TOPIC, Permissions.SUBSCRIPTION_READ))
            .build();

    private BulkModule() {}
}
