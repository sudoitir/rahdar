package io.github.sudoitir.rahdar.feature.resources.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateResourceRequest;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.feature.resources.ReadAsOneResource;
import io.github.sudoitir.rahdar.feature.resources.SubscriptionConfigurationReader;
import io.github.sudoitir.rahdar.feature.resources.TruncateService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews.LifecycleOutcomeView;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews.NodeOutcomeView;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Kafka topic or group goes through the read-as-one handler, including from the shared routes, and a group delete
 * still has no way to remove members.
 */
class ResourceLifecycleReadAsOneTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final ResourceTargets targets = mock(ResourceTargets.class);
    private final ReadAsOneResource handler = mock(ReadAsOneResource.class);
    private final QueueLifecycleService queues = mock(QueueLifecycleService.class);
    private final ClusterKinds kinds = mock(ClusterKinds.class);
    private final ResourceLifecycleController controller = new ResourceLifecycleController(
            queues,
            mock(SubscriptionConfigurationReader.class),
            targets,
            mock(TruncateService.class),
            kinds,
            List.of(handler));

    private final Target topic =
            new Target(new ResourceId(KafkaKinds.TOPIC, ResourcePath.of("orders")), ResourceClass.DESTINATION);
    private final Target group =
            new Target(new ResourceId(KafkaKinds.GROUP, ResourcePath.of("billing")), ResourceClass.SUBSCRIPTION);

    @BeforeEach
    void theHandlerOwnsKafka() {
        when(handler.supports("kafka.topic")).thenReturn(true);
        when(handler.supports("kafka.group")).thenReturn(true);
        when(targets.require(any(), any(), any(), any(ResourceClass[].class))).thenReturn(topic);
    }

    @Test
    void creatingAKafkaDestinationUsesTheHandler() {
        CreateDestinationRequest request = new CreateDestinationRequest("kafka.topic", "orders", Map.of("etag", "e"));
        when(handler.createDestination(CLUSTER, request, false)).thenReturn(applied());

        LifecycleOutcomeView view = controller.createDestination(CLUSTER, false, request);

        assertThat(view.dryRun()).isFalse();
        assertThat(view.nodes())
                .singleElement()
                .extracting(NodeOutcomeView::nodeName)
                .isEqualTo("whole cluster");
        verify(queues, never()).createAddress(any(), any(), anyBoolean());
    }

    @Test
    void deletingUsesTheHandlerAndNeverForwardsMemberRemoval() {
        when(kinds.systemId(CLUSTER)).thenReturn("kafka");
        when(targets.require(any(), eq("kafka.topic"), eq("orders"), any(ResourceClass[].class)))
                .thenReturn(topic);
        when(handler.deleteDestination(CLUSTER, topic, false, true, "etag", "fp"))
                .thenReturn(applied());
        when(targets.require(any(), eq("kafka.group"), eq("billing"), any(ResourceClass[].class)))
                .thenReturn(group);
        when(handler.deleteSubscription(CLUSTER, group, false, "etag")).thenReturn(applied());

        controller.delete(CLUSTER, "kafka.topic", "orders", false, true, true, "etag", "fp");
        controller.delete(CLUSTER, "kafka.group", "billing", false, false, true, "etag", null);

        verify(handler).deleteDestination(CLUSTER, topic, false, true, "etag", "fp");
        verify(handler).deleteSubscription(CLUSTER, group, false, "etag");
        verify(queues, never()).deleteQueue(any(), any(Target.class), anyBoolean(), anyBoolean(), anyBoolean());
        verify(queues, never()).deleteAddress(any(), any(Target.class), anyBoolean());
    }

    @Test
    void aKafkaSubscriptionCreateDoesNotCallCreateQueue() {
        when(kinds.systemId(CLUSTER)).thenReturn("kafka");
        CreateSubscriptionRequest request =
                new CreateSubscriptionRequest("kafka.group-topic", "billing", "kafka.topic/orders", Map.of());

        assertThatThrownBy(() -> controller.createSubscription(CLUSTER, false, request))
                .isInstanceOf(ConflictException.class)
                .satisfies(
                        error -> assertThat(((ConflictException) error).slug()).isEqualTo("unsupported-on-kind"));

        verify(queues, never()).createQueue(any(), any(), anyBoolean());
    }

    @Test
    void deletingPausingAndUpdatingAKafkaSubscriptionDoesNotCallTheQueueLifecycle() {
        when(kinds.systemId(CLUSTER)).thenReturn("kafka");
        Target row = new Target(
                new ResourceId(KafkaKinds.GROUP_TOPIC, ResourcePath.of("billing", "orders")),
                ResourceClass.SUBSCRIPTION);
        when(targets.require(any(), eq("kafka.group-topic"), eq("billing/orders"), any(ResourceClass[].class)))
                .thenReturn(row);

        assertThatThrownBy(() -> controller.delete(
                        CLUSTER, "kafka.group-topic", "billing/orders", false, false, true, null, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(
                        error -> assertThat(((ConflictException) error).slug()).isEqualTo("unsupported-on-kind"));
        assertThatThrownBy(() -> controller.pause(CLUSTER, "kafka.group-topic", "billing/orders", false))
                .isInstanceOf(ConflictException.class)
                .satisfies(
                        error -> assertThat(((ConflictException) error).slug()).isEqualTo("unsupported-on-kind"));
        assertThatThrownBy(() -> controller.update(
                        CLUSTER, "kafka.group-topic", "billing/orders", false, new UpdateResourceRequest(Map.of())))
                .isInstanceOf(ConflictException.class)
                .satisfies(
                        error -> assertThat(((ConflictException) error).slug()).isEqualTo("unsupported-on-kind"));
        assertThatThrownBy(() -> controller.resume(CLUSTER, "kafka.group-topic", "billing/orders", false))
                .isInstanceOf(ConflictException.class)
                .satisfies(
                        error -> assertThat(((ConflictException) error).slug()).isEqualTo("unsupported-on-kind"));
        assertThatThrownBy(() -> controller.resetCounters(CLUSTER, "kafka.group-topic", "billing/orders", false))
                .isInstanceOf(ConflictException.class)
                .satisfies(
                        error -> assertThat(((ConflictException) error).slug()).isEqualTo("unsupported-on-kind"));

        verify(queues, never()).deleteQueue(any(), any(Target.class), anyBoolean(), anyBoolean(), anyBoolean());
        verify(queues, never()).setPaused(any(), any(), anyBoolean(), anyBoolean());
        verify(queues, never()).resetCounter(any(), any(), anyBoolean());
        verify(queues, never()).updateQueue(any(), any(), any(), anyBoolean());
        verify(handler, never()).deleteSubscription(any(), any(), anyBoolean(), any());
    }

    @Test
    void aKindThatIsNeitherATopicNorAGroupIsRefused() {
        Target other = new Target(new ResourceId(KafkaKinds.TOPIC, ResourcePath.of("orders")), ResourceClass.NAMESPACE);
        when(targets.require(any(), any(), any(), any(ResourceClass[].class))).thenReturn(other);

        assertThatThrownBy(() -> controller.delete(CLUSTER, "kafka.topic", "orders", false, false, false, "e", "fp"))
                .isInstanceOf(ConflictException.class);
        verify(handler, never()).deleteDestination(any(), any(), anyBoolean(), anyBoolean(), any(), any());
        verify(handler, never()).deleteSubscription(any(), any(), anyBoolean(), any());
    }

    private static Attempt<LifecycleOutcome> applied() {
        return new Attempt.Ok<>(new LifecycleOutcome(
                false,
                1000,
                false,
                List.of(new NodeOutcome(null, "whole cluster", NodeStatus.APPLIED, null, null)),
                IdentityBasis.CONFIGURATION));
    }
}
