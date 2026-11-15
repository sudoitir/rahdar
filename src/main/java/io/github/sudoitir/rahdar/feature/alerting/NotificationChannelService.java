package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.AlertStateMachine.TransitionKind;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleChannelRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertDeliveryView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.ChannelHealthView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.ChannelTestRequest;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.ChannelTestResultView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.NotificationChannelRequest;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.NotificationChannelView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Notification channel CRUD, testing, and the delivery log (ADR-0036, ADR-0105). A channel's
 * secret (a Slack or Teams webhook URL, a webhook signing secret, an SMTP password, a PagerDuty
 * routing key) is AES-GCM at rest via {@link SecretVault} and never
 * returned in plaintext.
 */
@Service
@RequiredArgsConstructor
public class NotificationChannelService {

    private static final String AUDIT_TARGET = "NOTIFICATION_CHANNEL";

    /** The delivery log's page size ceiling. */
    static final int LOG_MAX = 100;

    private final NotificationChannelRepository channels;
    private final AlertDeliveryRepository deliveries;
    private final AlertRuleChannelRepository ruleChannels;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final AlertViewMapper mapper;
    private final SecretVault vault;
    private final List<NotificationSender> senders;
    private final ChannelConfigValidator validator;
    private final AlertPayloads payloads;
    private final ObjectMapper json;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_READ)")
    @Transactional(readOnly = true)
    public List<NotificationChannelView> list() {
        Map<UUID, Long> bound = new HashMap<>();
        ruleChannels.boundRuleCounts().forEach(b -> bound.put(b.getChannelId(), b.getRules()));
        Map<UUID, AlertDeliveryEntity> latest = new HashMap<>();
        deliveries.latestPerChannel().forEach(d -> latest.put(d.getChannelId(), d));
        Map<UUID, AlertDeliveryRepository.ChannelDeliveryCounts> counts = new HashMap<>();
        deliveries.countsSince(Instant.now().minus(Duration.ofHours(24))).forEach(c -> counts.put(c.getChannelId(), c));

        return channels.findAllByOrderByName().stream()
                .map(c -> mapper.channel(
                        c, bound.getOrDefault(c.getId(), 0L), health(latest.get(c.getId()), counts.get(c.getId()))))
                .toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_WRITE)")
    @Transactional
    public NotificationChannelView create(NotificationChannelRequest request) {
        validator.validate(request.kind(), request.config(), request.secret(), false);
        NotificationChannelEntity channel = new NotificationChannelEntity(
                request.name(), request.kind(), request.config() != null ? request.config() : "{}", null);
        channel.setEnabled(request.enabled());
        channels.save(channel); // need the generated id before sealing the AAD
        if (request.secret() != null && !request.secret().isBlank()) {
            seal(channel, request.secret().trim());
        }

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "CREATE_NOTIFICATION_CHANNEL",
                AUDIT_TARGET,
                channel.getName(),
                null,
                null,
                Map.of("kind", channel.getKind()),
                false);
        audit.succeed(event, 1);
        return view(channel);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_WRITE)")
    @Transactional
    public NotificationChannelView update(UUID channelId, NotificationChannelRequest request) {
        NotificationChannelEntity channel = requireChannel(channelId);
        if (!channel.getKind().equals(request.kind())) {
            throw new IllegalArgumentException("kind: a channel's kind cannot be changed; create a new channel");
        }
        validator.validate(request.kind(), request.config(), request.secret(), channel.getSealed() != null);

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "UPDATE_NOTIFICATION_CHANNEL",
                AUDIT_TARGET,
                channel.getName(),
                null,
                null,
                Map.of("kind", request.kind(), "enabled", request.enabled()),
                false);

        channel.setName(request.name());
        channel.setConfig(request.config() != null ? request.config() : "{}");
        channel.setEnabled(request.enabled());
        if (request.secret() != null && !request.secret().isBlank()) {
            seal(channel, request.secret().trim());
        }
        channels.save(channel);

        audit.succeed(event, 1);
        return view(channel);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_WRITE)")
    @Transactional
    public void delete(UUID channelId) {
        NotificationChannelEntity channel = requireChannel(channelId);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "DELETE_NOTIFICATION_CHANNEL",
                AUDIT_TARGET,
                channel.getName(),
                null,
                null,
                Map.of("boundRules", ruleChannels.countByChannelId(channelId)),
                false);
        channels.delete(channel); // cascades alert_rule_channel
        audit.succeed(event, 1);
    }

    /** Sends one test notification through a saved channel, synchronously — not queued. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_WRITE)")
    @Transactional
    public ChannelTestResultView test(UUID channelId) {
        NotificationChannelEntity channel = requireChannel(channelId);
        return send(channel.getName(), channel.getKind(), channel.getConfig(), storedSecret(channel));
    }

    /**
     * Tests a configuration that need not be saved. A blank secret with a channel id uses that
     * channel's stored secret — only for the same kind, since the secret is sealed to it.
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_WRITE)")
    @Transactional
    public ChannelTestResultView test(ChannelTestRequest request) {
        boolean hasSecret = request.secret() != null && !request.secret().isBlank();
        NotificationChannelEntity stored = request.channelId() == null ? null : requireChannel(request.channelId());
        if (stored != null && !stored.getKind().equals(request.kind())) {
            throw new IllegalArgumentException("kind: a channel's kind cannot be changed; create a new channel");
        }
        validator.validate(
                request.kind(), request.config(), request.secret(), stored != null && stored.getSealed() != null);
        String secret = hasSecret ? request.secret().trim() : storedSecretOrEmpty(stored);
        String name =
                stored != null ? stored.getName() : "(unsaved " + request.kind().toLowerCase() + " channel)";
        return send(name, request.kind(), request.config() != null ? request.config() : "{}", secret);
    }

    private String storedSecretOrEmpty(NotificationChannelEntity stored) {
        return stored != null ? storedSecret(stored) : "";
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_READ)")
    @Transactional(readOnly = true)
    public PagedView<AlertDeliveryView> deliveries(UUID channelId, ResourceQuery query) {
        requireChannel(channelId);
        return PagedView.of(
                deliveries.findByChannelIdOrderBySeqDesc(channelId, PageRequest.of(query.page() - 1, query.size())),
                this::deliveryView);
    }

    /** Returns a dead delivery to the queue with a fresh attempt budget. 409 for any other state. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_WRITE)")
    @Transactional
    public AlertDeliveryView retry(UUID channelId, long seq) {
        NotificationChannelEntity channel = requireChannel(channelId);
        AlertDeliveryEntity delivery = deliveries
                .findById(seq)
                .filter(d -> d.getChannelId().equals(channelId))
                .orElseThrow(() -> new NotFoundException("AlertDelivery", seq));
        if (!"DEAD".equals(delivery.getState())) {
            throw new ConflictException(
                    "delivery-not-dead",
                    "Delivery " + seq + " is " + delivery.getState()
                            + "; only a permanently failed delivery can be retried.");
        }
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "RETRY_NOTIFICATION_DELIVERY",
                AUDIT_TARGET,
                channel.getName(),
                null,
                null,
                Map.of("delivery", seq),
                false);
        delivery.requeue(Instant.now());
        deliveries.save(delivery);
        audit.succeed(event, 1);
        return deliveryView(delivery);
    }

    private ChannelTestResultView send(String name, String kind, String config, String secret) {
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "TEST_NOTIFICATION_CHANNEL",
                AUDIT_TARGET,
                name,
                null,
                null,
                Map.of("kind", kind),
                false);
        NotificationSender sender = senders.stream()
                .filter(s -> s.kind().equals(kind))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No sender for channel kind " + kind));
        // The server is about to fetch this channel's destination, so a channel stored before the rule existed is
        // checked here too (ADR-0227): the test button must not become a request to an address of the caller's
        // choosing.
        try {
            validator.requireDestination(kind, config, secret);
        } catch (IllegalArgumentException e) {
            audit.fail(event, e.getMessage());
            return new ChannelTestResultView(false, true, e.getMessage(), 0);
        }
        // The stored destination is checked again: a channel saved before the rule existed has never been.
        validator.requireDestination(kind, config, secret);
        // A PagerDuty test opens an incident and resolves it at once, so no test leaves a page open.
        List<AlertPayloads.Item> items = "PAGERDUTY".equals(kind)
                ? List.of(
                        new AlertPayloads.Item("test", TransitionKind.FIRED, null),
                        new AlertPayloads.Item("test", TransitionKind.RESOLVED, null))
                : List.of(new AlertPayloads.Item("test", TransitionKind.FIRED, null));
        String payload =
                payloads.build(UUID.randomUUID(), "Test notification from Rahdar", "INFO", null, items, Instant.now());

        long started = System.nanoTime();
        NotificationSender.Result result = sender.send(0L, config, secret, payload);
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (result.success()) {
            audit.succeed(event, 1);
        } else {
            audit.fail(event, result.error());
        }
        return new ChannelTestResultView(result.success(), result.permanent(), result.error(), millis);
    }

    private NotificationChannelView view(NotificationChannelEntity channel) {
        return mapper.channel(channel, ruleChannels.countByChannelId(channel.getId()), null);
    }

    private static ChannelHealthView health(
            AlertDeliveryEntity latest, AlertDeliveryRepository.ChannelDeliveryCounts counts) {
        if (latest == null) {
            return null;
        }
        return new ChannelHealthView(
                latest.getState(),
                latest.getCreatedAt(),
                latest.getDeliveredAt(),
                latest.getLastError(),
                counts == null ? 0 : counts.getPending(),
                counts == null ? 0 : counts.getFailed(),
                counts == null ? 0 : counts.getSent());
    }

    private AlertDeliveryView deliveryView(AlertDeliveryEntity d) {
        String summary;
        try {
            NoticePayload notice = NoticePayload.parseOrNull(d.getPayload(), json);
            summary = notice != null
                    ? NoticeFormatter.headline(notice)
                    : AlertMessageFormatter.title(AlertMessage.parse(d.getPayload(), json));
        } catch (RuntimeException _) {
            summary = "(unreadable payload)";
        }
        return new AlertDeliveryView(
                d.getSeq(),
                d.getRuleId(),
                d.getKind(),
                d.getSource(),
                summary,
                d.getState(),
                d.getAttempts(),
                d.getLastError(),
                d.getCreatedAt(),
                d.getNextAttemptAt(),
                d.getDeliveredAt());
    }

    private String storedSecret(NotificationChannelEntity channel) {
        return channel.getSealed() == null
                ? ""
                : vault.open(channel.getId() + "|" + channel.getKind(), channel.getSealed());
    }

    private void seal(NotificationChannelEntity channel, String plaintext) {
        channel.replaceSecret(vault.seal(channel.getId() + "|" + channel.getKind(), plaintext));
        channels.save(channel);
    }

    private NotificationChannelEntity requireChannel(UUID channelId) {
        return channels.findById(channelId).orElseThrow(() -> new NotFoundException("NotificationChannel", channelId));
    }
}
