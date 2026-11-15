package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims due {@code alert_delivery} rows and sends them (ADR-0036, design.md
 * decision 5). {@code claimDue}'s {@code FOR UPDATE SKIP LOCKED} makes this
 * safe under a concurrent instance with zero extra code — the multi-instance
 * seam ADR-0015 already left open.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertDispatcher {

    private static final int BATCH_SIZE = 20;

    private final AlertDeliveryRepository deliveries;
    private final NotificationChannelRepository channels;
    private final List<NotificationSender> senders;
    private final SecretVault vault;
    private final SettingsService settings;
    private final ChannelConfigValidator validator;

    /** Scheduled by {@code JobRunner} on {@code alerting.dispatch-interval}. */
    @Transactional
    public void dispatch() {
        for (AlertDeliveryEntity delivery : deliveries.claimDue(BATCH_SIZE)) {
            attempt(delivery);
        }
    }

    private void attempt(AlertDeliveryEntity delivery) {
        NotificationChannelEntity channel =
                channels.findById(delivery.getChannelId()).orElse(null);
        if (channel == null || !channel.isEnabled()) {
            delivery.recordDead("Channel no longer exists or is disabled");
            deliveries.save(delivery);
            return;
        }
        NotificationSender sender = senderFor(channel.getKind());
        if (sender == null) {
            delivery.recordDead("No sender registered for channel kind " + channel.getKind());
            deliveries.save(delivery);
            return;
        }

        String secret;
        try {
            secret = channel.getSealed() == null
                    ? ""
                    : vault.open(channel.getId() + "|" + channel.getKind(), channel.getSealed());
        } catch (RuntimeException e) {
            delivery.recordDead("Failed to decrypt channel secret: " + e.getMessage());
            deliveries.save(delivery);
            return;
        }

        // A channel saved before ADR-0227 carries a destination nobody has checked; the delivery path refuses it
        // rather than letting the server fetch an internal address.
        try {
            validator.requireDestination(channel.getKind(), channel.getConfig(), secret);
        } catch (IllegalArgumentException e) {
            delivery.recordDead(e.getMessage());
            deliveries.save(delivery);
            return;
        }

        // A channel stored before the destination rule existed is refused here too, so no delivery ever reaches it
        // (ADR-0227). It is a permanent failure: the operator has to point the channel somewhere public.
        try {
            validator.requireDestination(channel.getKind(), channel.getConfig(), secret);
        } catch (IllegalArgumentException e) {
            delivery.recordDead("The channel's destination is refused: " + e.getMessage());
            deliveries.save(delivery);
            return;
        }

        NotificationSender.Result result =
                sender.send(delivery.getSeq(), channel.getConfig(), secret, delivery.getPayload());
        Instant now = Instant.now();
        if (result.success()) {
            delivery.recordSuccess(now);
        } else if (result.permanent()) {
            delivery.recordDead(result.error());
        } else {
            Duration delay = result.retryAfter() != null
                    ? result.retryAfter()
                    : AlertBackoff.delayFor(
                            delivery.getAttempts() + 1,
                            settings.duration(AlertingSettings.INITIAL_BACKOFF),
                            settings.duration(AlertingSettings.MAX_BACKOFF));
            delivery.recordFailure(now, result.error(), delay, settings.intValue(AlertingSettings.MAX_ATTEMPTS));
            log.warn(
                    "Notification delivery {} to channel {} failed (attempt {}): {}",
                    delivery.getSeq(),
                    channel.getName(),
                    delivery.getAttempts(),
                    result.error());
        }
        deliveries.save(delivery);
    }

    private NotificationSender senderFor(String kind) {
        return senders.stream().filter(s -> s.kind().equals(kind)).findFirst().orElse(null);
    }
}
