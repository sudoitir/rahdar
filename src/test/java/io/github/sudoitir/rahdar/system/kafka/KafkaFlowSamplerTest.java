package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowMember;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Flow on Kafka reads the group subscriptions in one batched read, lists no producers and no routes. */
class KafkaFlowSamplerTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final KafkaFlowSampler sampler;

    KafkaFlowSamplerTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        sampler = new KafkaFlowSampler(gateway);
    }

    private static Resource subscription(String group, String topic, long consumers) {
        return new Resource(
                ResourceId.of("kafka.group-topic", group, topic),
                Attributes.builder()
                        .put(MetricKeys.CONSUMER_COUNT, MetricValue.exact(consumers))
                        .build(),
                List.of(),
                List.of(),
                Instant.now());
    }

    private void answer(Optional<String> more, Resource... found) {
        when(resources.read(anyList()))
                .thenReturn(Partial.complete(List.of(new ResourcePage(List.of(found), more)), List.of()));
    }

    @Test
    void everyGroupOnATopicIsOneConsumerReadInOneCall() {
        answer(
                Optional.empty(),
                subscription("billing", "orders", 2),
                subscription("billing", "payments", 2),
                subscription("audit", "orders", 1));

        FlowSample sample = sampler.sample(CLUSTER, UUID.randomUUID(), 100);

        verify(resources, times(1)).read(anyList());
        assertThat(sample.producers()).isEmpty();
        assertThat(sample.routes()).isEmpty();
        assertThat(sample.error()).isNull();
        assertThat(sample.consumersTotal()).isEqualTo(3);
        assertThat(sample.consumers())
                .extracting(FlowMember::clientId, FlowMember::destination, FlowMember::subscription)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("billing", "orders", "billing/orders"),
                        org.assertj.core.groups.Tuple.tuple("billing", "payments", "billing/payments"),
                        org.assertj.core.groups.Tuple.tuple("audit", "orders", "audit/orders"));
    }

    @Test
    void aGroupWithNoRunningMemberIsNotAConsumer() {
        answer(Optional.empty(), subscription("idle", "orders", 0), subscription("billing", "orders", 1));

        FlowSample sample = sampler.sample(CLUSTER, UUID.randomUUID(), 100);

        assertThat(sample.consumers()).extracting(FlowMember::clientId).containsExactly("billing");
    }

    @Test
    void theRowCapCutsTheConsumersAndTheTotalSaysThereWereMore() {
        answer(Optional.empty(), subscription("a", "t", 1), subscription("b", "t", 1), subscription("c", "t", 1));

        FlowSample sample = sampler.sample(CLUSTER, UUID.randomUUID(), 2);

        assertThat(sample.consumers()).hasSize(2);
        assertThat(sample.consumersTotal()).isGreaterThan(2);
    }

    @Test
    void theSystemReadsOnceForTheClusterAndSaysProducersAreNotListed() {
        assertThat(sampler.clusterWide()).isTrue();
        assertThat(sampler.memberCounters()).isFalse();
        assertThat(sampler.producersNote()).contains("does not list producers");
        assertThat(KafkaKinds.GROUP_TOPIC.value()).isEqualTo("kafka.group-topic");
    }
}
