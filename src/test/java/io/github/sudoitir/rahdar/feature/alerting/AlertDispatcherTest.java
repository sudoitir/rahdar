package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.alerting.NotificationSender.Result;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertDispatcherTest {

    @Mock
    AlertDeliveryRepository deliveries;

    @Mock
    NotificationChannelRepository channels;

    @Mock
    SecretVault vault;

    @Mock
    NotificationSender slackSender;

    @Mock
    SettingsService settings;

    AlertDispatcher dispatcher;

    private final UUID channelId = UUID.randomUUID();
    private NotificationChannelEntity channel;

    @BeforeEach
    void setUp() {
        when(slackSender.kind()).thenReturn("SLACK");
        // The dispatcher reads these on every attempt rather than caching them, so a
        // change in Settings applies to the next retry and not the next restart.
        when(settings.intValue(AlertingSettings.MAX_ATTEMPTS)).thenReturn(3);
        when(settings.duration(AlertingSettings.INITIAL_BACKOFF)).thenReturn(Duration.ofSeconds(1));
        when(settings.duration(AlertingSettings.MAX_BACKOFF)).thenReturn(Duration.ofMinutes(1));
        dispatcher = new AlertDispatcher(
                deliveries,
                channels,
                List.of(slackSender),
                vault,
                settings,
                new ChannelConfigValidator(JsonMapper.builder().build(), new DestinationPolicy()));
        channel = new NotificationChannelEntity("ops-slack", "SLACK", "{}", new byte[] {1});
        when(channels.findById(channelId)).thenReturn(Optional.of(channel));
        when(vault.open(any(), any())).thenReturn("https://hooks.slack.com/services/x");
    }

    private AlertDeliveryEntity pending() {
        AlertDeliveryEntity d = new AlertDeliveryEntity(UUID.randomUUID(), channelId, "{}");
        d.setSeq(1L); // normally assigned by the DB; the sender needs a non-null delivery id
        return d;
    }

    @Test
    void serverErrorBacksOffAndRetries() {
        when(slackSender.send(any(Long.class), any(), any(), any())).thenReturn(Result.retryable("500"));
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        Mockito.verify(deliveries).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo("PENDING");
        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getNextAttemptAt())
                .isAfter(captor.getValue().getCreatedAt());
    }

    @Test
    void permanentFailureGoesDeadWithoutRetry() {
        when(slackSender.send(any(Long.class), any(), any(), any())).thenReturn(Result.permanent("revoked"));
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        Mockito.verify(deliveries).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo("DEAD");
        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    void rateLimitedResponseHonoursRetryAfter() {
        when(slackSender.send(any(Long.class), any(), any(), any()))
                .thenReturn(Result.retryable("429", Duration.ofSeconds(30)));
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        Mockito.verify(deliveries).save(captor.capture());
        Duration untilNext = Duration.between(
                captor.getValue().getCreatedAt(), captor.getValue().getNextAttemptAt());
        assertThat(untilNext).isGreaterThanOrEqualTo(Duration.ofSeconds(29));
    }

    @Test
    void aChannelPointedInsideTheNetworkIsRecordedDeadWithoutSending() {
        when(vault.open(any(), any())).thenReturn("https://127.0.0.1/services/x");
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        Mockito.verify(deliveries).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo("DEAD");
        assertThat(captor.getValue().getLastError()).contains("internal");
        Mockito.verify(slackSender, Mockito.never()).send(Mockito.anyLong(), any(), any(), any());
    }

    @Test
    void reachingMaxAttemptsEndsDead() {
        AlertDeliveryEntity delivery = pending();
        for (int i = 0; i < 2; i++) {
            delivery.recordFailure(Instant.now(), "err", Duration.ofMillis(1), 3);
        }
        when(slackSender.send(any(Long.class), any(), any(), any())).thenReturn(Result.retryable("still failing"));
        when(deliveries.claimDue(20)).thenReturn(List.of(delivery));

        dispatcher.dispatch();

        assertThat(delivery.getState()).isEqualTo("DEAD");
        assertThat(delivery.getAttempts()).isEqualTo(3);
    }
}
