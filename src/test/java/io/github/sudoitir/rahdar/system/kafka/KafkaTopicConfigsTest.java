package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaAdminReads.Setting;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges.ConfigChangeParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigSource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TopicConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A sensitive setting's value, old or new, is in no answer, preview, approval row or audit row. */
class KafkaTopicConfigsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final String OLD = "old-secret-value";
    private static final String NEW = "hunter2-new-secret";
    private static final String DEFAULT = "default-secret-value";

    private final KafkaAdminReads admin = mock(KafkaAdminReads.class);
    private final KafkaTopicConfigs configs;

    KafkaTopicConfigsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        Resource topic = new Resource(
                ResourceId.of("kafka.topic", "orders"),
                Attributes.builder().putRaw(KafkaKinds.INTERNAL, false).build(),
                List.of(),
                List.of(),
                Instant.now());
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(topic), Optional.empty()), List.of()));
        when(admin.settings(CLUSTER, "orders"))
                .thenReturn(List.of(
                        new Setting("retention.ms", "1000", ConfigSource.TOPIC, "604800000", false, false),
                        new Setting("ssl.key.password", OLD, ConfigSource.TOPIC, DEFAULT, false, true)));
        configs = new KafkaTopicConfigs(admin, gateway, mock(ClusterAccessGuard.class));
    }

    @Test
    void readNeverReturnsASensitiveValueWhateverTheBrokerAnswered() {
        TopicConfig read = configs.read(CLUSTER, "orders");

        assertThat(read.toString()).doesNotContain(OLD).doesNotContain(DEFAULT);
        assertThat(read.entries())
                .filteredOn(e -> e.name().equals("ssl.key.password"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.value()).isNull();
                    assertThat(e.defaultValue()).isNull();
                    assertThat(e.sensitive()).isTrue();
                });
        assertThat(read.entries())
                .filteredOn(e -> e.name().equals("retention.ms"))
                .singleElement()
                .satisfies(e -> assertThat(e.value()).isEqualTo("1000"));
    }

    @Test
    void aPreviewMasksTheOldTheNewAndTheFallbackValueAndTheBrokersQuote() {
        when(admin.validate(eq(CLUSTER), eq("orders"), any())).thenReturn(Optional.of("Invalid value " + NEW));

        ConfigPreview set = configs.preview(
                CLUSTER, "orders", new ConfigChangeRequest(Map.of("ssl.key.password", NEW), null, null));
        ConfigPreview remove =
                configs.preview(CLUSTER, "orders", new ConfigChangeRequest(null, List.of("ssl.key.password"), null));

        for (ConfigPreview preview : List.of(set, remove)) {
            assertThat(preview.toString())
                    .doesNotContain(OLD)
                    .doesNotContain(NEW)
                    .doesNotContain(DEFAULT);
        }
        assertThat(set.changes().getFirst().after()).isEqualTo(KafkaTopicConfigs.MASKED);
        assertThat(set.valid()).isFalse();
    }

    @Test
    void theApprovalRowsAndTheAuditRowCarryNoSensitiveValue() {
        when(admin.validate(eq(CLUSTER), eq("orders"), any())).thenReturn(Optional.empty());
        AuditService audit = mock(AuditService.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(mock(AuditEvent.class));
        OperationGate gate = mock(OperationGate.class);
        BrokerGateway gateway = mock(BrokerGateway.class);
        KafkaTopicConfigChanges changes =
                new KafkaTopicConfigChanges(configs, gateway, gate, audit, mock(ActorResolver.class));
        ConfigChangeParams params = new ConfigChangeParams(
                CLUSTER, "orders", Map.of("ssl.key.password", NEW, "retention.ms", "5"), null, null);

        assertThat(changes.shown(params)).doesNotContain(NEW).contains("retention.ms=5");

        // The preview's etag is what a real run must carry; the audit row records the masked changes.
        ConfigPreview preview = configs.preview(CLUSTER, "orders", new ConfigChangeRequest(params.set(), null, null));
        ConfigChangeParams pinned = new ConfigChangeParams(CLUSTER, "orders", params.set(), null, preview.etag());
        try {
            changes.replay(pinned);
        } catch (RuntimeException brokerNotReachable) {
            // The gateway is a mock: the audit row was begun before the call that fails.
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> recorded = ArgumentCaptor.forClass(Map.class);
        verify(audit).begin(any(), any(), any(), any(), any(), any(), recorded.capture(), anyBoolean());
        assertThat(String.valueOf(recorded.getValue())).doesNotContain(NEW).doesNotContain(OLD);
    }
}
