package io.github.sudoitir.rahdar.feature.events.internal.persistence;

import io.github.sudoitir.rahdar.feature.events.BrokerEventWriter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code broker_event} (changeset 010). Read-only from JPA — rows are
 * written by {@link BrokerEventWriter}'s JDBC batch (ADR-0028), so this entity
 * exists only for the history read API and {@code ddl-auto=validate}.
 */
@Entity
@Table(name = "broker_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long seq;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "destination", updatable = false)
    private String destination;

    @Column(name = "subscription", updatable = false)
    private String subscription;

    @Column(name = "consumer", updatable = false)
    private String consumer;

    @Column(name = "channel", updatable = false)
    private String channel;

    @Column(name = "connection", updatable = false)
    private String connection;

    @Column(name = "remote_address", updatable = false)
    private String remoteAddress;

    @Column(name = "principal", updatable = false)
    private String principal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", updatable = false)
    private String data;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;
}
