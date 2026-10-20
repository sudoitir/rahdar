package io.github.sudoitir.rahdar.feature.flow;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;

/** Client connectivity and message flow. Module descriptor (ADR-0070). */
public final class FlowModule {

    /** The stream topic whose subscribers are the demand signal for sampling (ADR-0081). */
    public static final String TOPIC = "flow";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("flow")
            .title("Flow")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/flow")
            .settingKey(FlowSettings.SAMPLE_INTERVAL)
            .settingKey(FlowSettings.MAX_ROWS_PER_NODE)
            .settingKey(FlowSettings.DEMAND_LEASE)
            .streamTopic(TopicDef.signal(TOPIC, Permissions.SUBSCRIPTION_READ))
            .build();

    private FlowModule() {}
}
