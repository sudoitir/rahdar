package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/** The capture dry run resolves what a subscription would do, and does none of it. */
class MessageIndexServicePreviewTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private MessageIndexSubscriptionRepository subscriptions;
    private AuditService audit;
    private CaptureAddresses addresses;
    private CaptureReconciler reconciler;

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        audit = mock(AuditService.class);
        addresses = mock(CaptureAddresses.class);
        reconciler = mock(CaptureReconciler.class);
        ClusterNode primary = mock(ClusterNode.class);
        when(primary.getName()).thenReturn("primary");
        when(reconciler.servingNodes(CLUSTER)).thenReturn(List.of(primary));
    }

    private static LifecycleRegistry lifecycle() {
        LifecycleRegistry lifecycle = mock(LifecycleRegistry.class);
        when(lifecycle.retention(MessageIndexStore.ID)).thenReturn(Optional.of(Duration.ofDays(7)));
        return lifecycle;
    }

    private MessageIndexService service(String brokerRole) {
        CaptureProperties properties = new CaptureProperties(
                brokerRole,
                Duration.ofSeconds(30),
                Duration.ofHours(24),
                DataSize.ofMegabytes(64),
                Duration.ofSeconds(1));
        RahdarInstance instance = mock(RahdarInstance.class);
        when(instance.id()).thenReturn("abc12345");
        ClusterAccessGuard access = mock(ClusterAccessGuard.class);
        when(access.mayOnAll(any(), any(), any(), any())).thenReturn(true);
        return new MessageIndexService(
                subscriptions,
                mock(QueueSnapshots.class),
                mock(MessageIndexCapture.class),
                mock(MessageCaptureNodeRepository.class),
                mock(ClusterDirectory.class),
                access,
                mock(ActorResolver.class),
                audit,
                mock(JdbcTemplate.class),
                addresses,
                mock(CaptureConsumer.class),
                reconciler,
                new CaptureTap(extensions(), properties),
                properties,
                instance,
                lifecycle(),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private static MessageIndexService.Spec spec(String pattern, Long ringSize) {
        return new MessageIndexService.Spec(
                pattern, null, 7, null, CaptureMode.CAPTURE, ringSize, null, null, null, null);
    }

    @Test
    void itStatesWhatWouldBeCreatedWhereAndHowBigWithoutSavingOrAuditingAnything() {
        when(addresses.of(any(), any())).thenReturn(Set.of("ORDER.IN"));

        MessageIndexService.Preview preview = service("rahdar").preview(CLUSTER, spec("ORDER.IN", 50_000L));

        assertThat(preview.destinations()).containsExactly("ORDER.IN");
        assertThat(preview.nodes()).containsExactly("primary");
        assertThat(preview.ringMessages()).isEqualTo(50_000L);
        assertThat(preview.ringBytes()).isEqualTo(DataSize.ofMegabytes(64).toBytes());
        assertThat(preview.brokerObjects())
                .anySatisfy(o -> assertThat(o).startsWith("divert rahdar.capture.abc12345.ORDER.IN."))
                .contains("address-setting rahdar.capture.abc12345.#");
        assertThat(preview.brokerXml()).contains("<max-size-bytes>");
        assertThat(preview.refusal()).isNull();
        verifyNoInteractions(subscriptions, audit);
    }

    @Test
    void aMissingBrokerRoleIsStatedBeforeAnythingIsArmed() {
        when(addresses.of(any(), any())).thenReturn(Set.of("ORDER.IN"));

        MessageIndexService.Preview preview = service(null).preview(CLUSTER, spec("ORDER.IN", null));

        assertThat(preview.refusal()).contains("rahdar.capture.broker-role");
    }

    @Test
    void aPatternThatMatchesNothingSaysSo() {
        when(addresses.of(any(), any())).thenReturn(Set.of());

        MessageIndexService.Preview preview = service("rahdar").preview(CLUSTER, spec("NOTHING.*", null));

        assertThat(preview.destinations()).isEmpty();
        assertThat(preview.refusal()).contains("matches no address");
    }

    @Test
    void aRetentionLongerThanTheStoresIsRefusedNamingTheMaximum() {
        MessageIndexService.Spec spec = new MessageIndexService.Spec(
                "ORDER.IN", null, 8, null, CaptureMode.CAPTURE, null, null, null, null, null);

        MessageIndexService service = service("rahdar");

        assertThatThrownBy(() -> service.preview(CLUSTER, spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 7")
                .hasMessageContaining("got 8");
    }

    @Test
    void anOutOfRangeBoundIsRefusedNotClamped() {
        MessageIndexService service = service("rahdar");
        MessageIndexService.Spec spec = spec("ORDER.IN", 50_000_000L);

        assertThatThrownBy(() -> service.preview(CLUSTER, spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ringSize must be between 100 and 1000000");
    }

    @Test
    void deletingACaptureStopsRecordingBeforeItsRowsAreDeletedAndSweepsItsTaps() {
        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setClusterId(CLUSTER);
        entity.setSubscriptionPattern("ORDER.IN");
        entity.setMode(CaptureMode.CAPTURE);
        entity.setRetentionDays(7);
        when(subscriptions.findById(id)).thenReturn(Optional.of(entity));
        when(addresses.of(any(), any())).thenReturn(Set.of("ORDER.IN"));
        AuditEvent auditEvent = mock(AuditEvent.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(auditEvent);
        CaptureConsumer consumers = mock(CaptureConsumer.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new MessageIndexService.Footprint(3, 30, null));
        CaptureProperties properties = new CaptureProperties(
                "rahdar",
                Duration.ofSeconds(30),
                Duration.ofHours(24),
                DataSize.ofMegabytes(64),
                Duration.ofSeconds(1));
        ClusterAccessGuard access = mock(ClusterAccessGuard.class);
        when(access.mayOnAll(any(), any(), any(), any())).thenReturn(true);
        MessageIndexService service = new MessageIndexService(
                subscriptions,
                mock(QueueSnapshots.class),
                mock(MessageIndexCapture.class),
                mock(MessageCaptureNodeRepository.class),
                mock(ClusterDirectory.class),
                access,
                mock(ActorResolver.class),
                audit,
                jdbc,
                addresses,
                consumers,
                reconciler,
                new CaptureTap(extensions(), properties),
                properties,
                mock(RahdarInstance.class),
                lifecycle(),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));

        service.delete(CLUSTER, id);

        InOrder order = Mockito.inOrder(consumers, jdbc, reconciler);
        order.verify(consumers).stopSubscription(id);
        order.verify(jdbc).update(ArgumentMatchers.startsWith("DELETE FROM message_index"), any(Object[].class));
        // No transaction is active in this test, so the after-commit sweep runs straight away.
        order.verify(reconciler).reconcileNow(CLUSTER);
    }

    @Test
    void turningCaptureOffSweepsItsTapsOnceTheChangeCommits() {
        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setClusterId(CLUSTER);
        entity.setSubscriptionPattern("ORDER.IN");
        entity.setMode(CaptureMode.CAPTURE);
        entity.setEnabled(true);
        entity.setRetentionDays(7);
        when(subscriptions.findById(id)).thenReturn(Optional.of(entity));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(addresses.of(any(), any())).thenReturn(Set.of());
        AuditEvent auditEvent = mock(AuditEvent.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(auditEvent);

        service("rahdar")
                .update(
                        CLUSTER,
                        id,
                        new MessageIndexService.Spec(null, null, null, false, null, null, null, null, null, null));

        // The divert keeps copying every message into its capture queue until it is removed, and
        // with the drain stopped nothing empties that queue. No transaction is active in this test,
        // so the after-commit sweep runs straight away.
        verify(reconciler).reconcileNow(CLUSTER);
    }

    @Test
    void rahdarsOwnCaptureAddressesCannotBeCaptured() {
        MessageIndexService service = service("rahdar");
        MessageIndexService.Spec spec = spec("rahdar.capture.#", null);

        assertThatThrownBy(() -> service.preview(CLUSTER, spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be captured");
    }

    /** A system whose tap would add the bounds to the broker's configuration. */
    private static SystemExtensions extensions() {
        MessageTaps taps = mock(MessageTaps.class);
        when(taps.settingsSnippet(any()))
                .thenReturn("<address-setting><max-size-bytes>1</max-size-bytes></address-setting>");
        SystemExtensions extensions = mock(SystemExtensions.class);
        when(extensions.require(any(), eq(MessageTaps.class))).thenReturn(taps);
        return extensions;
    }
}
