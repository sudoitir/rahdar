package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Estimate;
import io.github.sudoitir.openbroker.operation.EtagBasis;
import io.github.sudoitir.openbroker.operation.Operation.Update;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.openbroker.operation.PreconditionEnforcement;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaBrokerConfigChanges.BrokerConfigParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerConfig;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerConfigSource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A broker's configuration read, previewed and applied through the cluster's resources, with the driver's refusals. */
class KafkaBrokerConfigsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String RESTART = "node.id is read-only while a broker runs. Restart the broker.";

    private final Operations operations = mock(Operations.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaBrokerConfigs configs;
    private final KafkaBrokerConfigChanges changes;

    KafkaBrokerConfigsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(connection.operations()).thenReturn(operations);
        Resource broker = new Resource(
                ResourceId.of("kafka.broker-config", "1"),
                Attributes.builder()
                        .putRaw("kafka.config.message.max.bytes", "1048588")
                        .putRaw("kafka.config.node.id", "1")
                        .putRaw("kafka.config_read_only", "node.id")
                        .putRaw("kafka.config_sources", "message.max.bytes=cluster,node.id=static")
                        .build(),
                List.of(),
                List.of(),
                Instant.now());
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(broker), Optional.empty()), List.of()));
        configs = new KafkaBrokerConfigs(gateway, mock(ClusterAccessGuard.class));
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        changes = new KafkaBrokerConfigChanges(
                configs, gateway, mock(OperationGate.class), audit, mock(ActorResolver.class));
    }

    private static Preview preview(String key, Object before, Object after) {
        return new Preview(
                List.of(new AttributeChange(key, Optional.ofNullable(before), Optional.ofNullable(after))),
                Set.of(),
                new Estimate(Count.exact(1), "{configuration}"),
                "etag-1",
                EtagBasis.CONFIGURATION,
                PreconditionEnforcement.CHECKED_BEFORE);
    }

    private static BrokerException restartRequired() {
        return new BrokerException(
                Status.FAILED_PRECONDITION,
                ErrorInfo.of(ErrorReasons.IMMUTABLE_FIELD),
                Dispatch.NOT_SENT,
                null,
                RESTART);
    }

    @Test
    void readListsEachSettingWithItsSourceAndMarksTheOnesARestartChanges() {
        BrokerConfig read = configs.read(CLUSTER, "1");

        assertThat(read.clusterDefault()).isFalse();
        assertThat(read.entries()).hasSize(2);
        assertThat(read.entries())
                .filteredOn(e -> e.name().equals("message.max.bytes"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.source()).isEqualTo(BrokerConfigSource.CLUSTER);
                    assertThat(e.readOnly()).isFalse();
                    assertThat(e.unit()).isEqualTo("bytes");
                });
        assertThat(read.entries())
                .filteredOn(e -> e.name().equals("node.id"))
                .singleElement()
                .satisfies(e -> assertThat(e.readOnly()).isTrue());
    }

    @Test
    void aBrokerIsNamedByItsNumberOrDefault() {
        assertThatThrownBy(() -> configs.read(CLUSTER, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(KafkaBrokerConfigs.driverName("default")).isEqualTo("*");
        assertThat(KafkaBrokerConfigs.driverName("3")).isEqualTo("3");
    }

    @Test
    void aPreviewReportsTheDiffAndTheEtagAndAnEmptyValueIsARemoval() {
        when(operations.preview(any(), any(), eq(Scope.whole())))
                .thenReturn(preview("kafka.config.message.max.bytes", "1048588", ""));

        ConfigPreview preview =
                configs.preview(CLUSTER, "default", new ConfigChangeRequest(null, List.of("message.max.bytes"), null));

        assertThat(preview.etag()).isEqualTo("etag-1");
        assertThat(preview.changes()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("message.max.bytes");
            assertThat(row.before()).isEqualTo("1048588");
            assertThat(row.after()).isNull();
        });
        verify(operations, never()).execute(any(), any(), any(), any());
    }

    @Test
    void aSettingOnlyARestartChangesIsRefusedAsAConflictWithTheDriversWords() {
        when(operations.preview(any(), any(), any())).thenThrow(restartRequired());

        ConfigChangeRequest request = new ConfigChangeRequest(Map.of("node.id", "2"), null, null);

        assertThatThrownBy(() -> configs.preview(CLUSTER, "1", request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("node.id is read-only")
                .hasCauseInstanceOf(BrokerException.class);
    }

    @Test
    void anApplyWritesAfterAuditingAndPinsThePreviewEtag() {
        when(operations.preview(any(), any(), any())).thenReturn(preview("kafka.config.message.max.bytes", "1", "2"));
        when(operations.execute(any(ResourceId.class), any(Update.class), any(), any()))
                .thenReturn(Partial.complete(OperationResult.done(), List.of()));
        BrokerConfigParams params =
                new BrokerConfigParams(CLUSTER, "default", Map.of("message.max.bytes", "2"), null, "etag-1");

        changes.replay(params);

        var order = inOrder(audit, operations);
        order.verify(audit)
                .begin(
                        any(),
                        eq("UPDATE_BROKER_CONFIG"),
                        eq("NODE"),
                        eq("default"),
                        eq(CLUSTER),
                        any(),
                        any(),
                        eq(false));
        order.verify(operations).execute(any(ResourceId.class), any(Update.class), any(), any());
        order.verify(audit).finish(any(), eq(false), eq(1L), any(), any());
    }

    @Test
    void aFailedApplyClosesItsAuditRowAndAStaleEtagWritesNothing() {
        when(operations.preview(any(), any(), any())).thenReturn(preview("kafka.config.message.max.bytes", "1", "2"));
        when(operations.execute(any(ResourceId.class), any(Update.class), any(), any()))
                .thenThrow(restartRequired());
        BrokerConfigParams params =
                new BrokerConfigParams(CLUSTER, "default", Map.of("message.max.bytes", "2"), null, "etag-1");

        assertThatThrownBy(() -> changes.replay(params)).isInstanceOf(ConflictException.class);
        verify(audit).fail(any(), any());

        BrokerConfigParams other =
                new BrokerConfigParams(CLUSTER, "default", Map.of("message.max.bytes", "2"), null, "other");
        assertThatThrownBy(() -> changes.replay(other)).isInstanceOf(KafkaStaleException.class);
        verify(operations, times(1)).execute(any(ResourceId.class), any(Update.class), any(), any());
    }
}
