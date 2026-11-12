package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ApplyRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ClusterOutcome;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PreviewView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The shared create and delete routes call the Kafka lifecycle and cannot skip its etag. */
@ExtendWith(MockitoExtension.class)
class KafkaReadAsOneTest {

    @Mock
    private KafkaTopicLifecycle topics;

    @Mock
    private SettingsService settings;

    private KafkaReadAsOne door;

    @BeforeEach
    void setUp() {
        door = new KafkaReadAsOne(topics, settings);
    }

    @Test
    void aRealDeleteWithoutAnEtagDoesNotPreviewOrDelete() {
        assertThatThrownBy(() -> door.deleteDestination(UUID.randomUUID(), topic(), false, false, " ", null))
                .isInstanceOf(KafkaStaleException.class);
        verifyNoInteractions(topics);
    }

    @Test
    void aDryRunDeleteNamesTheGroupsAndDoesNotDelete() {
        when(topics.previewDelete(any(), eq("orders"))).thenReturn(preview());

        LifecycleOutcome outcome = ok(door.deleteDestination(UUID.randomUUID(), topic(), true, false, null, null));

        verify(topics).previewDelete(any(), eq("orders"));
        verify(topics, never()).deleteTopic(any(), any(), any());
        assertThat(outcome.dryRun()).isTrue();
        assertThat(outcome.nodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeName()).isEqualTo("whole cluster");
            assertThat(node.error())
                    .contains("billing")
                    .contains("does not erase")
                    .contains("About 4 records");
        });
    }

    @Test
    void aRealDeleteUsesTheConfirmedEtagAndOverride() {
        UUID clusterId = UUID.randomUUID();
        when(topics.deleteTopic(eq(clusterId), eq("orders"), any()))
                .thenReturn(new ClusterOutcome("whole cluster", "APPLIED"));
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(1000);

        LifecycleOutcome outcome = ok(door.deleteDestination(clusterId, topic(), false, true, "etag-1", "fp-1"));

        verify(topics).deleteTopic(eq(clusterId), eq("orders"), eq(new ApplyRequest("etag-1", "fp-1", true)));
        assertThat(outcome.dryRun()).isFalse();
        assertThat(outcome.nodes())
                .singleElement()
                .satisfies(node -> assertThat(node.status().name()).isEqualTo("APPLIED"));
    }

    @Test
    void aGroupDeleteWithoutAnEtagDoesNotRunAndNeverRemovesMembers() {
        assertThatThrownBy(() -> door.deleteSubscription(UUID.randomUUID(), group(), false, null))
                .isInstanceOf(KafkaStaleException.class);
        verifyNoInteractions(topics);
    }

    @Test
    void aGroupDeleteUsesTheSameService() {
        UUID clusterId = UUID.randomUUID();
        when(topics.deleteGroup(eq(clusterId), eq("billing"), any()))
                .thenReturn(new ClusterOutcome("whole cluster", "APPLIED"));
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(1000);

        ok(door.deleteSubscription(clusterId, group(), false, "etag-1"));

        verify(topics).deleteGroup(eq(clusterId), eq("billing"), eq(new ApplyRequest("etag-1", null, false)));
    }

    @Test
    void aCreateWithoutAnEtagDoesNotCreate() {
        CreateDestinationRequest request = new CreateDestinationRequest(
                "kafka.topic", "orders", Map.of(KafkaKinds.PARTITIONS, 6, KafkaKinds.REPLICATION_FACTOR, 3));

        assertThatThrownBy(() -> door.createDestination(UUID.randomUUID(), request, false))
                .isInstanceOf(KafkaStaleException.class);
        verifyNoInteractions(topics);
    }

    @Test
    void aCreateDryRunValidatesAndDoesNotCreate() {
        when(topics.previewCreate(any(), any())).thenReturn(preview());
        CreateDestinationRequest request = new CreateDestinationRequest(
                "kafka.topic",
                "orders",
                Map.of(KafkaKinds.PARTITIONS, 6, KafkaKinds.CONFIG_PREFIX + "cleanup.policy", "delete"));

        LifecycleOutcome outcome = ok(door.createDestination(UUID.randomUUID(), request, true));

        verify(topics)
                .previewCreate(
                        any(), eq(new CreateRequest("orders", 6, null, Map.of("cleanup.policy", "delete"), null)));
        verify(topics, never()).create(any(), any());
        assertThat(outcome.dryRun()).isTrue();
    }

    @Test
    void aReplicaAssignmentIsRefusedBeforeTheBroker() {
        CreateDestinationRequest request = new CreateDestinationRequest(
                "kafka.topic", "orders", Map.of(KafkaKinds.REPLICAS, List.of(1), "etag", "e"));

        assertThatThrownBy(() -> door.createDestination(UUID.randomUUID(), request, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not assign replicas");
        verifyNoInteractions(topics);
    }

    private static PreviewView preview() {
        return new PreviewView(
                "orders",
                "etag-1",
                "fp-1",
                "Deleting this topic does not erase those groups' offsets.",
                List.of("billing"),
                4L,
                "estimate",
                null,
                null,
                6,
                1000,
                false);
    }

    private static Target topic() {
        return new Target(new ResourceId(KafkaKinds.TOPIC, ResourcePath.of("orders")), ResourceClass.DESTINATION);
    }

    private static Target group() {
        return new Target(new ResourceId(KafkaKinds.GROUP, ResourcePath.of("billing")), ResourceClass.SUBSCRIPTION);
    }

    private static LifecycleOutcome ok(Attempt<LifecycleOutcome> attempt) {
        return ((Attempt.Ok<LifecycleOutcome>) attempt).value();
    }
}
