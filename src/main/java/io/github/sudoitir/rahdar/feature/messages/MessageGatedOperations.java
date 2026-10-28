package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.LogPurgeParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageDeleteParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageExpireParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageMoveParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageRetryParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.QueuePurgeParams;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Reach;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated message operations: {@code subscription.purge} and {@code message.move/retry/delete/expire} (ADR-0179). Each is
 * destructive and runs as the requester once approved. Their estimates read the same counts a dry run does, without its
 * audit row, and pin the queue's identity so a queue recreated under the same name is refused when it would run.
 */
@Configuration(proxyBeanMethods = false)
class MessageGatedOperations {

    @Bean
    GatedOperation<QueuePurgeParams> queuePurgeOperation(MessageService messages, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "subscription.purge";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<QueuePurgeParams> paramsType() {
                return QueuePurgeParams.class;
            }

            @Override
            public Set<Trait> traits(QueuePurgeParams p) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(QueuePurgeParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(QueuePurgeParams p) {
                return "Purge queue " + p.queue() + " on " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(QueuePurgeParams p) {
                return List.of(
                        DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Queue", p.queue()),
                        DisplayRow.of("Removes", "every message"));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(QueuePurgeParams p) {
                Reach reach = messages.reachOfPurge(p.clusterId(), p.queue(), p.nodeId());
                return new Effect(reach.count(), "messages", reach.identity(), null);
            }

            @Override
            public void replay(QueuePurgeParams p) {
                succeeded(messages.purge(p.clusterId(), p.queue(), p.nodeId(), false, p.override()));
            }
        };
    }

    @Bean
    GatedOperation<LogPurgeParams> logPurgeOperation(MessageService messages, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "subscription.purge-log";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<LogPurgeParams> paramsType() {
                return LogPurgeParams.class;
            }

            @Override
            public Set<Trait> traits(LogPurgeParams p) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(LogPurgeParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(LogPurgeParams p) {
                return "Purge " + p.subscription() + " on " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(LogPurgeParams p) {
                return List.of(
                        DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Subscription", p.subscription()),
                        DisplayRow.of("Skips", "every record it has not read"));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(LogPurgeParams p) {
                Reach reach = messages.reachOfLogPurge(p);
                return new Effect(reach.count(), "records", reach.identity(), null);
            }

            @Override
            public void replay(LogPurgeParams p) {
                succeeded(messages.purgeLog(p.clusterId(), ResourceId.parse(p.subscription()), false));
            }
        };
    }

    @Bean
    GatedOperation<MessageMoveParams> messageMoveOperation(MessageService messages, ClusterEnvironmentIndex clusters) {
        return new Action<>("message.move", MessageAction.MOVE, MessageMoveParams.class, "Move", messages, clusters);
    }

    @Bean
    GatedOperation<MessageRetryParams> messageRetryOperation(
            MessageService messages, ClusterEnvironmentIndex clusters) {
        return new Action<>(
                "message.retry", MessageAction.RETRY, MessageRetryParams.class, "Retry", messages, clusters);
    }

    @Bean
    GatedOperation<MessageDeleteParams> messageDeleteOperation(
            MessageService messages, ClusterEnvironmentIndex clusters) {
        return new Action<>(
                "message.delete", MessageAction.DELETE, MessageDeleteParams.class, "Delete", messages, clusters);
    }

    @Bean
    GatedOperation<MessageExpireParams> messageExpireOperation(
            MessageService messages, ClusterEnvironmentIndex clusters) {
        return new Action<>(
                "message.expire", MessageAction.EXPIRE, MessageExpireParams.class, "Expire", messages, clusters);
    }

    /** A replay that the broker could not carry out is a failure of the request, not a success. */
    private static void succeeded(Attempt<?> attempt) {
        if (attempt instanceof Attempt.Failed<?>(var kind, var detail)) {
            throw new BrokerConnectionException(kind, detail);
        }
    }

    /** The four selected-message actions, which differ only in their name, verb and parameter class. */
    private record Action<P extends Record & MessageActionParams>(
            String type,
            MessageAction action,
            Class<P> paramsType,
            String verb,
            MessageService messages,
            ClusterEnvironmentIndex clusters)
            implements GatedOperation<P> {

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Set<Trait> traits(P p) {
            return Set.of(Trait.DESTRUCTIVE);
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.ON_APPROVAL;
        }

        @Override
        public OperationScope scope(P p) {
            return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
        }

        @Override
        public String summary(P p) {
            return verb + " " + selection(p.request()) + " on queue " + p.queue() + " on "
                    + clusters.labelOf(p.clusterId());
        }

        @Override
        public List<DisplayRow> display(P p) {
            List<DisplayRow> rows = new ArrayList<>();
            rows.add(DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())));
            rows.add(DisplayRow.of("Queue", p.queue()));
            rows.add(DisplayRow.of(verb, selection(p.request())));
            if (p.request().byFilter()) {
                rows.add(DisplayRow.of("Filter", p.request().filter()));
            }
            if (p.request().targetQueue() != null) {
                rows.add(DisplayRow.of("Into queue", p.request().targetQueue()));
            }
            return rows;
        }

        @Override
        public Set<String> redactedPaths() {
            return Set.of();
        }

        @Override
        public Effect estimate(P p) {
            MessageActionRequest request = p.request();
            Reach reach = messages.reach(p.clusterId(), p.queue(), p.nodeId(), action, request);
            String etag = request.byFilter() ? reach.identity() + "|" + request.filter() : reach.identity();
            return new Effect(reach.count(), "messages", etag, null);
        }

        @Override
        public void replay(P p) {
            succeeded(messages.execute(p.clusterId(), p.queue(), p.nodeId(), action, p.request(), false, p.override()));
        }

        private static String selection(MessageActionRequest request) {
            if (request.byFilter()) {
                return "the messages matching a filter";
            }
            return request.ids().isEmpty() ? "all messages" : request.ids().size() + " messages";
        }
    }
}
