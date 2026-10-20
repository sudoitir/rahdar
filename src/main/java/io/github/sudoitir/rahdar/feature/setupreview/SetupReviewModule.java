package io.github.sudoitir.rahdar.feature.setupreview;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import java.util.List;

/** Cluster setup review. Module descriptor (ADR-0070, ADR-0106). */
public final class SetupReviewModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("setupreview")
            .title("Setup review")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/artemis/setup-review")
            .settingKey(SetupReviewSettings.INTERVAL)
            .settingKey(SetupReviewSettings.MIN_INTERVAL)
            .streamTopic(TopicDef.signal(SetupReviewService.TOPIC))
            .mcpTool(new McpToolDef(
                    "setup_review",
                    McpToolDef.Posture.READ,
                    "A cluster's configuration review: HA, clustering and durability mistakes with the fix.",
                    List.of()))
            .build();

    private SetupReviewModule() {}
}
