package io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Maps the {@code rabbitmq_connection} table: how a RabbitMQ cluster is reached beyond its nodes. */
@Entity
@Table(name = "rabbitmq_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RabbitMqConnectionEntity {

    /** The account signs in with a user name and password. */
    public static final String PASSWORD = "PASSWORD";

    /** The account signs in with a bearer token. */
    public static final String TOKEN = "TOKEN";

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "watch_interval_seconds", nullable = false)
    private int watchIntervalSeconds;

    /** Management base URLs separated by commas, in the order the operator gave them; never an account. */
    @Column(name = "management_urls", nullable = false)
    private String managementUrls;

    /** {@code amqp(s)://host:port} entries separated by commas, or null when the cluster has no data plane. */
    @Column(name = "amqp_endpoints")
    private String amqpEndpoints;

    @Column(name = "auth", nullable = false)
    private String auth;

    @Column(name = "event_vhost", nullable = false)
    private String eventVhost;

    public RabbitMqConnectionEntity(
            UUID clusterId,
            int watchIntervalSeconds,
            String managementUrls,
            String amqpEndpoints,
            String auth,
            String eventVhost) {
        this.clusterId = clusterId;
        change(watchIntervalSeconds, managementUrls, amqpEndpoints, auth, eventVhost);
    }

    public void change(
            int watchIntervalSeconds, String managementUrls, String amqpEndpoints, String auth, String eventVhost) {
        this.watchIntervalSeconds = watchIntervalSeconds;
        this.managementUrls = managementUrls;
        this.amqpEndpoints = amqpEndpoints;
        this.auth = auth;
        this.eventVhost = eventVhost;
    }
}
