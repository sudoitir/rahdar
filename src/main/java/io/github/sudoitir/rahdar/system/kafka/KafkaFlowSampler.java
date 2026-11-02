package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What flow reads from a Kafka cluster: the consumer groups and the topics each one is subscribed to, in one
 * batched read of the group subscriptions however many groups there are. Kafka's admin API does not list
 * producers, so there are none to show, and flow says so. Kafka has no routing objects beyond the topic, so
 * there are no routes. The read answers for the whole cluster, so flow asks it once, through one broker.
 */
@Component
@RequiredArgsConstructor
class KafkaFlowSampler implements FlowSampler {

    static final String PRODUCERS_NOTE =
            "Kafka does not list producers, so none are shown: only the consumer groups" + " reading each topic are.";

    private static final String PROTOCOL = "kafka";

    private final BrokerGateway gateway;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public boolean clusterWide() {
        return true;
    }

    @Override
    public boolean memberCounters() {
        return false;
    }

    @Override
    public String producersNote() {
        return PRODUCERS_NOTE;
    }

    @Override
    public FlowSample sample(UUID clusterId, UUID nodeId, int rowCap) {
        ClusterConnection connection = gateway.cluster(clusterId);
        List<KindId> kinds = subscriptionKinds(connection);
        List<ResourceQuery> queries = kinds.stream()
                .map(kind -> ResourceQuery.of(kind).limit(rowCap + 1))
                .toList();
        List<ResourcePage> pages =
                KafkaFailures.call(() -> connection.resources().read(queries).orThrow());
        List<FlowMember> consumers = new ArrayList<>();
        boolean more = false;
        for (ResourcePage page : pages) {
            for (Resource subscription : page.items()) {
                if (consumers.size() == rowCap) {
                    more = true;
                    break;
                }
                member(subscription, consumers);
            }
            more |= page.continueToken().isPresent();
        }
        long total = consumers.size() + (more ? 1 : 0);
        return new FlowSample(List.of(), 0, consumers, total, List.of(), null, null);
    }

    /** A group with no running member is a subscription, not a consumer: it is left to the queue it names. */
    private static void member(Resource subscription, List<FlowMember> out) {
        List<String> segments = subscription.id().path().segments();
        long members = subscription
                .attributes()
                .get(MetricKeys.CONSUMER_COUNT)
                .map(MetricValue::asLong)
                .orElse(1L);
        if (segments.size() < 2 || members == 0) {
            return;
        }
        String group = segments.get(0);
        String topic = segments.get(1);
        String path = subscription
                .id()
                .format()
                .substring(subscription.id().kind().value().length() + 1);
        out.add(new FlowMember(path, group, null, null, PROTOCOL, topic, path, 0, 0));
    }

    /** The kinds a group's subscription to a topic comes in on this cluster: classic and consumer groups, and share groups where run. */
    private static List<KindId> subscriptionKinds(ClusterConnection connection) {
        var declared = KafkaGroups.kindsOf(connection);
        List<KindId> kinds = new ArrayList<>(List.of(KafkaKinds.GROUP_TOPIC));
        if (declared.contains(KafkaKinds.SHARE_GROUP_TOPIC)) {
            kinds.add(KafkaKinds.SHARE_GROUP_TOPIC);
        }
        return kinds;
    }
}
