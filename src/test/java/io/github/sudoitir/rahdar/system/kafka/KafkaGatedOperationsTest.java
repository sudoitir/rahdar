package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.DeleteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.GroupDeleteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PartitionsParams;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What an approver reads for a topic or group command before releasing it. */
class KafkaGatedOperationsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final ClusterEnvironmentIndex clusters = mock(ClusterEnvironmentIndex.class);
    private final KafkaTopicLifecycle lifecycle = mock(KafkaTopicLifecycle.class);
    private final KafkaGatedOperations beans = new KafkaGatedOperations();

    KafkaGatedOperationsTest() {
        when(clusters.labelOf(CLUSTER)).thenReturn("prod-eu");
    }

    @Test
    void anApproverReadsTheTopicTheCountsAndWhetherTheCapWasOverridden() {
        GatedOperation<CreateParams> create = beans.topicCreate(lifecycle, clusters);
        CreateParams created = new CreateParams(CLUSTER, "orders", 6, 3, Map.of(), "etag");
        assertThat(create.summary(created)).isEqualTo("Create topic orders on prod-eu");
        assertThat(create.display(created))
                .extracting(DisplayRow::label, DisplayRow::to)
                .contains(tuple("Topic", "orders"), tuple("Partitions", "6"));

        GatedOperation<DeleteParams> delete = beans.topicDelete(lifecycle, clusters);
        DeleteParams overridden = new DeleteParams(CLUSTER, "orders", "etag", "fp", true);
        DeleteParams withinCap = new DeleteParams(CLUSTER, "orders", "etag", "fp", false);
        assertThat(delete.summary(withinCap)).isEqualTo("Delete topic orders on prod-eu");
        assertThat(delete.display(overridden)).extracting(DisplayRow::to).contains("yes");
        assertThat(delete.display(withinCap)).extracting(DisplayRow::to).contains("no");

        GatedOperation<PartitionsParams> partitions = beans.increasePartitions(lifecycle, clusters);
        PartitionsParams raised = new PartitionsParams(CLUSTER, "orders", 12, "etag");
        assertThat(partitions.summary(raised)).isEqualTo("Raise partitions of orders to 12 on prod-eu");
        assertThat(partitions.display(raised)).extracting(DisplayRow::label).contains("Topic", "Partitions");

        GatedOperation<GroupDeleteParams> group = beans.deleteGroup(lifecycle, clusters);
        GroupDeleteParams params = new GroupDeleteParams(CLUSTER, "billing", "etag");
        assertThat(group.summary(params)).isEqualTo("Delete group billing on prod-eu");
        assertThat(group.display(params)).extracting(DisplayRow::label).containsExactly("Cluster", "Group");
    }
}
