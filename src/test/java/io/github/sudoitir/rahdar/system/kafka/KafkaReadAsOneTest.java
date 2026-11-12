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
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ClusterOutcome;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PreviewView;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.TopicApplyRequest;
import java.util.LinkedHashMap;
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
        UUID clusterId = UUID.randomUUID();
        Target orders = topic();
        assertThatThrownBy(() -> door.deleteDestination(clusterId, orders, false, false, " ", null))
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

        verify(topics).deleteTopic(clusterId, "orders", new TopicApplyRequest("etag-1", "fp-1", true));
        assertThat(outcome.dryRun()).isFalse();
        assertThat(outcome.nodes())
                .singleElement()
                .satisfies(node -> assertThat(node.status().name()).isEqualTo("APPLIED"));
    }

    @Test
    void aGroupDeleteWithoutAnEtagDoesNotRunAndNeverRemovesMembers() {
        UUID clusterId = UUID.randomUUID();
        Target billing = group();
        assertThatThrownBy(() -> door.deleteSubscription(clusterId, billing, false, null))
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

        verify(topics).deleteGroup(clusterId, "billing", new TopicApplyRequest("etag-1", null, false));
    }

    @Test
    void aCreateWithoutAnEtagDoesNotCreate() {
        CreateDestinationRequest request = new CreateDestinationRequest(
                "kafka.topic", "orders", Map.of(KafkaKinds.PARTITIONS, 6, KafkaKinds.REPLICATION_FACTOR, 3));
        UUID clusterId = UUID.randomUUID();

        assertThatThrownBy(() -> door.createDestination(clusterId, request, false))
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

        UUID clusterId = UUID.randomUUID();
        assertThatThrownBy(() -> door.createDestination(clusterId, request, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not assign replicas");
        verifyNoInteractions(topics);
    }

    @Test
    void theDoorOwnsATopicAndAGroup() {
        assertThat(door.supports(KafkaKinds.TOPIC.toString())).isTrue();
        assertThat(door.supports(KafkaKinds.GROUP.toString())).isTrue();
        assertThat(door.supports("artemis.address")).isFalse();
    }

    @Test
    void aRealCreateSendsTheEtagAndASettingNamedDirectly() {
        UUID clusterId = UUID.randomUUID();
        when(topics.create(any(), any())).thenReturn(new ClusterOutcome("whole cluster", "APPLIED"));
        when(settings.intValue(BrokerSettings.BULK_CAP)).thenReturn(1000);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("etag", "etag-1");
        attributes.put(KafkaKinds.PARTITIONS, "6");
        attributes.put(KafkaKinds.REPLICATION_FACTOR, " ");
        attributes.put("cleanup.policy", "delete");
        attributes.put("retention.ms", null);
        CreateDestinationRequest request = new CreateDestinationRequest("kafka.topic", "orders", attributes);

        LifecycleOutcome outcome = ok(door.createDestination(clusterId, request, false));

        verify(topics)
                .create(clusterId, new CreateRequest("orders", 6, null, Map.of("cleanup.policy", "delete"), "etag-1"));
        assertThat(outcome.dryRun()).isFalse();
    }

    @Test
    void aBlankFingerprintAndAnEmptyPreviewStayOffTheBroker() {
        UUID clusterId = UUID.randomUUID();
        Target orders = topic();
        assertThatThrownBy(() -> door.deleteDestination(clusterId, orders, false, false, "etag-1", " "))
                .isInstanceOf(KafkaStaleException.class);
        assertThatThrownBy(() -> door.deleteDestination(clusterId, orders, false, false, "etag-1", null))
                .isInstanceOf(KafkaStaleException.class);
        verify(topics, never()).deleteTopic(any(), any(), any());

        when(topics.previewDelete(any(), eq("orders"))).thenReturn(emptyPreview());
        LifecycleOutcome outcome = ok(door.deleteDestination(clusterId, orders, true, false, null, null));
        assertThat(outcome.nodes().getFirst().error())
                .contains("No group has offsets")
                .contains("The record count is unavailable.");

        when(topics.previewDeleteGroup(any(), eq("billing"))).thenReturn(preview());
        ok(door.deleteSubscription(clusterId, group(), true, null));
        verify(topics).previewDeleteGroup(any(), eq("billing"));
        verify(topics, never()).deleteGroup(any(), any(), any());
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

    private static PreviewView emptyPreview() {
        return new PreviewView(
                "orders",
                "etag-1",
                "fp-1",
                "Deleting this topic does not erase those groups' offsets.",
                List.of(),
                null,
                "unavailable",
                null,
                null,
                null,
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
