package io.github.sudoitir.rahdar.feature.sql.internal.persistence;

import io.github.sudoitir.rahdar.feature.sql.CaptureMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One operator's decision to capture a queue's messages (changeset 021, ADR-0059).
 * This is estate configuration, not cache: it is the record of a deliberate act, it
 * is audited, and deleting it destroys everything it captured.
 *
 * <p>{@code mode} is what the subscription does rather than a flag on how it does it
 * (ADR-0062). {@code SAMPLE} is ADR-0060's browse poller, unchanged; {@code CAPTURE}
 * installs a divert-fed tap on every live node and drains it. The bounds below apply
 * to {@code CAPTURE} only and are the reason an abandoned tap cannot grow without
 * limit — on the broker, in Postgres, or in Rahdar's own throughput.
 *
 * <p>{@code captureFrom} is the moment capture began. A query reaching before it is
 * outside the index's coverage and gets told so, rather than being answered with a
 * short list.
 */
@Entity
@Table(name = "message_index_subscription")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MessageIndexSubscriptionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    /** An Artemis wildcard pattern, matched the same way a FROM target is. */
    @Column(name = "subscription_pattern", nullable = false)
    private String subscriptionPattern;

    @Column(name = "interval_ms", nullable = false)
    private long intervalMs;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "capture_from", nullable = false)
    private Instant captureFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private CaptureMode mode = CaptureMode.SAMPLE;

    /** Bounds the capture queue on the broker. Oldest is dropped, and the drop is counted. */
    @Column(name = "ring_size", nullable = false)
    private long ringSize = 10_000L;

    /** An Artemis filter expression applied by the divert, narrowing load and exposure. */
    @Column(name = "filter_string")
    private String filterString;

    /** Bytes of payload this subscription may hold in Postgres before it degrades. */
    @Column(name = "max_bytes", nullable = false)
    private long maxBytes = 5L * 1024 * 1024 * 1024;

    /** Messages per second this subscription may ingest, so one firehose cannot starve the rest. */
    @Column(name = "max_rate", nullable = false)
    private int maxRate = 500;

    /** Bytes of body stored per message; a larger body is stored truncated and flagged. */
    @Column(name = "body_cap_bytes", nullable = false)
    private int bodyCapBytes = 256 * 1024;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
