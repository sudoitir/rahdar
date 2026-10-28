package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaErrors;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.SystemExtension;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigSource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupMember;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConfigsOptions;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConfigEntry.ConfigSynonym;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.ShareMemberDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.stereotype.Component;

/**
 * What the broker SDK's neutral model does not carry, read from the connection's own admin client (ADR-0208): a group
 * member's partitions, a setting's source, default and sensitivity, and the broker's validation of a change. The
 * client belongs to the connection; it is never closed here. This is the one class that names a Kafka client type.
 */
@Component
@RequiredArgsConstructor
class KafkaAdminReads implements SystemExtension {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** One setting as the broker describes it. */
    record Setting(
            String name, String value, ConfigSource source, String defaultValue, boolean readOnly, boolean sensitive) {}

    private final BrokerGateway gateway;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    /** The running members of a group and the partitions each holds. */
    List<GroupMember> members(UUID clusterId, String group, boolean share) {
        Admin admin = admin(clusterId);
        List<GroupMember> out = new ArrayList<>();
        if (share) {
            await(admin.describeShareGroups(List.of(group)).describedGroups().get(group))
                    .members()
                    .forEach(m -> out.add(member(m)));
        } else {
            await(admin.describeConsumerGroups(List.of(group)).describedGroups().get(group))
                    .members()
                    .forEach(m -> out.add(member(m)));
        }
        return out;
    }

    /** A topic's settings, each with where its value comes from. */
    List<Setting> settings(UUID clusterId, String topic) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = await(admin(clusterId)
                .describeConfigs(
                        List.of(resource),
                        new org.apache.kafka.clients.admin.DescribeConfigsOptions().includeSynonyms(true))
                .values()
                .get(resource));
        return config.entries().stream().map(KafkaAdminReads::setting).toList();
    }

    /** The broker's refusal of one change, or empty when it would accept it. Nothing is applied. */
    Optional<String> validate(UUID clusterId, String topic, AlterConfigOp op) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        try {
            await(admin(clusterId)
                    .incrementalAlterConfigs(
                            Map.of(resource, (Collection<AlterConfigOp>) List.of(op)),
                            new AlterConfigsOptions().validateOnly(true))
                    .values()
                    .get(resource));
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    private Admin admin(UUID clusterId) {
        return gateway.cluster(clusterId)
                .unwrap(Admin.class)
                .orElseThrow(() -> new IllegalStateException("This cluster offers no Kafka admin client."));
    }

    private static GroupMember member(MemberDescription m) {
        return new GroupMember(
                m.consumerId(),
                m.clientId(),
                m.host(),
                partitions(m.assignment().topicPartitions()));
    }

    private static GroupMember member(ShareMemberDescription m) {
        return new GroupMember(
                m.consumerId(),
                m.clientId(),
                m.host(),
                partitions(m.assignment().topicPartitions()));
    }

    private static List<String> partitions(Collection<TopicPartition> assigned) {
        return assigned.stream().map(TopicPartition::toString).sorted().toList();
    }

    private static Setting setting(ConfigEntry e) {
        String fallback = e.synonyms().stream()
                .filter(s -> s.source() != ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .map(ConfigSynonym::value)
                .findFirst()
                .orElse(null);
        ConfigSource source =
                switch (e.source()) {
                    case DYNAMIC_TOPIC_CONFIG -> ConfigSource.TOPIC;
                    case DYNAMIC_BROKER_CONFIG, DYNAMIC_DEFAULT_BROKER_CONFIG -> ConfigSource.BROKER_DEFAULT;
                    case STATIC_BROKER_CONFIG -> ConfigSource.STATIC;
                    default -> ConfigSource.DEFAULT;
                };
        return new Setting(
                e.name(),
                e.isSensitive() ? null : e.value(),
                source,
                e.isSensitive() ? null : fallback,
                e.isReadOnly(),
                e.isSensitive());
    }

    private static <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw KafkaFailures.translate(KafkaErrors.of(e.getCause(), null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the cluster.", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("The cluster did not answer in time.", e);
        }
    }
}
