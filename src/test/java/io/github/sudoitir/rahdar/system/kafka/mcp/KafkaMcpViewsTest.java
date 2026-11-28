package io.github.sudoitir.rahdar.system.kafka.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerQuorum;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterProblems;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterTotals;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigEntry;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigSource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupDetail;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupMember;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPartitionLag;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupTopic;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TopicConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The MCP answers stay small at thousands of partitions and say what they left out. */
class KafkaMcpViewsTest {

    private static GroupDetail group(int partitions) {
        List<GroupPartitionLag> lags = IntStream.range(0, partitions)
                .mapToObj(p ->
                        new GroupPartitionLag(p, 10L, 10L + p, p == 5 ? null : (long) p, p == 5 ? "unknown" : null))
                .toList();
        List<String> assigned =
                IntStream.range(0, partitions).mapToObj(p -> "orders-" + p).toList();
        return new GroupDetail(
                "g",
                "consumer",
                "stable",
                null,
                List.of(new GroupTopic("orders", false, lags)),
                List.of(new GroupMember("m1", "client", "host", assigned)),
                Instant.EPOCH);
    }

    @Test
    void describingAGroupOfThreeThousandPartitionsGivesTotalsAndTheTopSlowest() {
        var summary = KafkaMcpViews.group(group(3000), null, null, null);

        var topic = summary.topics().getFirst();
        assertThat(topic.partitions()).isEqualTo(3000);
        assertThat(topic.lag()).isEqualTo((2999L * 3000 / 2) - 5);
        assertThat(topic.lagUnknown()).isEqualTo(1);
        assertThat(topic.slowest()).hasSize(KafkaMcpViews.TOP);
        assertThat(summary.truncated()).isTrue();
        assertThat(summary.note()).contains("topic");
        assertThat(summary.members().getFirst().assigned()).isEqualTo(3000);
        assertThat(summary.members().getFirst().first()).hasSize(KafkaMcpViews.TOP);
    }

    @Test
    void aTopicIsPagedWithACursorAndAGroupThatFitsIsNotTruncated() {
        var first = KafkaMcpViews.group(group(250), "orders", null, null);
        var topic = first.topics().getFirst();
        assertThat(topic.slowest()).hasSize(KafkaMcpViews.PAGE);
        assertThat(topic.nextCursor()).isEqualTo("100");

        var last = KafkaMcpViews.group(group(250), "orders", "200", null);
        assertThat(last.topics().getFirst().slowest()).hasSize(50);
        assertThat(last.topics().getFirst().nextCursor()).isNull();

        assertThat(KafkaMcpViews.group(group(10), null, null, null).truncated()).isFalse();
        GroupDetail ten = group(10);
        assertThatThrownBy(() -> KafkaMcpViews.group(ten, "orders", "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOmittedTopicListIsEmptyAndAListNamesEachTopic() {
        assertThat(KafkaMcpReadTools.topicList(null)).isEmpty();
        assertThat(KafkaMcpReadTools.topicList("  ")).isEmpty();
        assertThat(KafkaMcpReadTools.topicList("orders, payments , ,")).containsExactly("orders", "payments");
    }

    @Test
    void aResetPreviewGivesPerTopicTotalsAndTheBiggestMovesOnly() {
        List<OffsetRow> rows = IntStream.range(0, 3000)
                .mapToObj(p -> new OffsetRow("orders", p, 10L, 0L, p, 0, null, p == 7 ? "out of range" : null))
                .toList();
        var summary = KafkaMcpViews.reset(new OffsetPreview(rows, List.of(), null, "etag"), null, null, null);

        var topic = summary.topics().getFirst();
        assertThat(topic.partitions()).isEqualTo(3000);
        assertThat(topic.refused()).isEqualTo(1);
        assertThat(topic.top()).hasSize(KafkaMcpViews.TOP);
        assertThat(topic.top().getFirst().partition()).isEqualTo(2999);
        assertThat(summary.etag()).isEqualTo("etag");
        assertThat(summary.truncated()).isTrue();
    }

    @Test
    void aTopicConfigIsOnlyWhatIsOverriddenUnlessAskedOtherwise() {
        TopicConfig config = new TopicConfig(
                false,
                null,
                List.of(
                        new ConfigEntry("retention.ms", "1", ConfigSource.TOPIC, "2", false, false, "ms"),
                        new ConfigEntry("segment.bytes", "9", ConfigSource.DEFAULT, "9", false, false, "bytes"),
                        new ConfigEntry(
                                "cleanup.policy", "delete", ConfigSource.DEFAULT, "delete", false, false, null)));

        var overridden = KafkaMcpViews.config(config, null, null);
        assertThat(overridden.entries()).extracting(ConfigEntry::name).containsExactly("retention.ms");
        assertThat(overridden.omitted()).isEqualTo(2);
        assertThat(overridden.note()).contains("onlyOverridden=false");

        assertThat(KafkaMcpViews.config(config, false, null).entries()).hasSize(3);
        assertThat(KafkaMcpViews.config(config, null, "segment.bytes, cleanup.policy")
                        .entries())
                .extracting(ConfigEntry::name)
                .containsExactly("segment.bytes", "cleanup.policy");
        assertThat(KafkaMcpViews.config(config, null, "segment.bytes ,cleanup.policy,")
                        .entries())
                .extracting(ConfigEntry::name)
                .containsExactly("segment.bytes", "cleanup.policy");
    }

    @Test
    void aClusterAnswerNotesTheTopicNameCapAndKeepsTheServicesView() {
        ClusterView few = cluster(List.of("orders"), null, 0);
        var open = KafkaMcpViews.cluster(few);
        assertThat(open.cluster()).isSameAs(few);
        assertThat(open.truncated()).isFalse();
        assertThat(open.note()).isNull();

        List<String> names =
                IntStream.range(0, KafkaMcpViews.TOP).mapToObj(i -> "t" + i).toList();
        var exact = KafkaMcpViews.cluster(cluster(names, null, 0));
        assertThat(exact.truncated()).isFalse();

        var closed = KafkaMcpViews.cluster(cluster(names, null, 380));
        assertThat(closed.truncated()).isTrue();
        assertThat(closed.note()).contains("380");

        var withheld = KafkaMcpViews.cluster(cluster(List.of(), "Topic names need destination:read.", 12));
        assertThat(withheld.truncated()).isFalse();
        assertThat(withheld.cluster().problems().topicsWithheld()).isNotNull();
    }

    private static ClusterView cluster(List<String> topics, String withheld, long omitted) {
        return new ClusterView(
                "KRaft",
                true,
                null,
                List.of(),
                List.of(),
                new BrokerQuorum(true, null, "1", List.of(), true),
                List.of(),
                new ClusterTotals(3, 7, 1, 2L, 6L, 13L, 0L, 1L, false),
                new ClusterProblems(0L, 13L, 1L, topics, withheld, omitted),
                List.of());
    }
}
