package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.CancelParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.ElectParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.NamedPartition;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.ReassignParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaWriteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.DeleteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.GroupDeleteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PartitionsParams;
import java.util.List;
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

    @Test
    void anApproverReadsAPreferredElectionAReplicaMoveAndACancel() {
        KafkaReplication replication = mock(KafkaReplication.class);
        when(clusters.environmentOf(CLUSTER)).thenReturn(UUID.randomUUID());
        ElectParams elect = new ElectParams(CLUSTER, List.of(new NamedPartition("orders", 0)), "etag");
        ReassignParams move = new ReassignParams(CLUSTER, "orders", 0, List.of(1, 2), 1_000L, "etag");
        CancelParams cancel = new CancelParams(CLUSTER, List.of(new NamedPartition("orders", 0)), "etag");

        KafkaReplicationGatedOperations replicationBeans = new KafkaReplicationGatedOperations();
        GatedOperation<ElectParams> election = replicationBeans.electPreferred(replication, clusters);
        assertThat(election.summary(elect)).contains("1 partitions").contains("prod-eu");
        assertThat(election.display(elect)).extracting(DisplayRow::label).contains("Cluster", "Partitions");
        assertThat(election.scope(elect).clusterId()).isEqualTo(CLUSTER);
        assertThat(election.traits(elect)).isNotEmpty();

        GatedOperation<ReassignParams> reassign = replicationBeans.reassignReplicas(replication, clusters);
        assertThat(reassign.summary(move)).contains("orders-0").contains("prod-eu");
        assertThat(reassign.display(move)).extracting(DisplayRow::to).contains("[1, 2]", "1000");

        GatedOperation<CancelParams> stop = replicationBeans.cancelReassignment(replication, clusters);
        assertThat(stop.summary(cancel)).contains("1 replica").contains("prod-eu");
        assertThat(stop.display(cancel)).extracting(DisplayRow::label).contains("Partitions");
        stop.replay(cancel);
        verify(replication).replayCancel(cancel);
    }

    @Test
    void anApproverSeesWhetherASchemaDeleteIsPermanent() {
        List<DisplayRow> permanent = KafkaSchemaGatedOperations.schemaRows(
                clusters, new SchemaWriteParams(CLUSTER, "delete", "orders-value", null, null, null, true, "e"));
        List<DisplayRow> soft = KafkaSchemaGatedOperations.schemaRows(
                clusters, new SchemaWriteParams(CLUSTER, "delete", "orders-value", null, null, null, false, "e"));
        List<DisplayRow> level = KafkaSchemaGatedOperations.schemaRows(
                clusters,
                new SchemaWriteParams(CLUSTER, "compatibility", "orders-value", null, null, "BACKWARD", null, "e"));

        assertThat(permanent)
                .extracting(DisplayRow::label, DisplayRow::to)
                .contains(tuple("Permanent", "yes — the schemas cannot be restored"));
        assertThat(soft).extracting(DisplayRow::to).anyMatch(value -> value != null && value.startsWith("no"));
        assertThat(level).extracting(DisplayRow::label, DisplayRow::to).contains(tuple("Compatibility", "BACKWARD"));
    }

    @Test
    void anApproverReadsASchemaChangeAndABindingBeforeReleasingIt() {
        KafkaSchemaReviews reviews = mock(KafkaSchemaReviews.class);
        KafkaSchemaBindings bindings = mock(KafkaSchemaBindings.class);
        String schema = "x".repeat(200);
        SchemaWriteParams params = new SchemaWriteParams(CLUSTER, "register", null, "AVRO", schema, null, null, "e");
        SchemaWriteParams shortSchema =
                new SchemaWriteParams(CLUSTER, "register", "orders-value", "AVRO", "{}", null, null, "e");
        BindingParams bind = new BindingParams(CLUSTER, "orders", "value", "avro", "e");

        KafkaSchemaGatedOperations schemaBeans = new KafkaSchemaGatedOperations();
        GatedOperation<SchemaWriteParams> write = schemaBeans.schemaWrite(reviews, clusters);
        GatedOperation<BindingParams> binding = schemaBeans.schemaBinding(bindings, clusters);
        write.replay(params);
        binding.replay(bind);

        assertThat(write.summary(params)).isEqualTo("Change schema register on prod-eu");
        assertThat(write.display(params))
                .extracting(DisplayRow::label, DisplayRow::to)
                .contains(tuple("Subject", "default"), tuple("Schema", "x".repeat(117) + "..."));
        assertThat(write.display(shortSchema)).extracting(DisplayRow::to).contains("{}");
        assertThat(binding.summary(bind)).isEqualTo("Bind orders value to avro on prod-eu");
        assertThat(binding.display(bind)).extracting(DisplayRow::label).contains("Topic", "Side", "Serde");
        verify(reviews).replay(params);
        verify(bindings).replay(bind);
    }
}
