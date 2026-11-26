package io.github.sudoitir.rahdar.system.kafka.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Maps the {@code kafka_connection} table: how a Kafka cluster is reached beyond its brokers. */
@Entity
@Table(name = "kafka_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KafkaConnectionEntity {

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    /** {@code host:port} entries separated by commas, in the order the operator gave them. */
    @Column(name = "bootstrap_servers", nullable = false)
    private String bootstrapServers;

    @Column(name = "security_protocol", nullable = false)
    private String securityProtocol;

    @Column(name = "sasl_mechanism")
    private String saslMechanism;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "registry_kind", nullable = false)
    private String registryKind;

    @Column(name = "registry_url")
    private String registryUrl;

    /** The vault envelope. Never copied onto a DTO, an audit map or a log. */
    @Column(name = "registry_secret")
    private byte[] registrySecret;

    public KafkaConnectionEntity(
            UUID clusterId, String bootstrapServers, String securityProtocol, String saslMechanism, String clientId) {
        this(clusterId, bootstrapServers, securityProtocol, saslMechanism, clientId, "none", null, null);
    }

    public KafkaConnectionEntity(
            UUID clusterId,
            String bootstrapServers,
            String securityProtocol,
            String saslMechanism,
            String clientId,
            String registryKind,
            String registryUrl,
            byte[] registrySecret) {
        this.clusterId = clusterId;
        change(bootstrapServers, securityProtocol, saslMechanism, clientId, registryKind, registryUrl, registrySecret);
    }

    public void change(
            String bootstrapServers,
            String securityProtocol,
            String saslMechanism,
            String clientId,
            String registryKind,
            String registryUrl,
            byte[] registrySecret) {
        this.bootstrapServers = bootstrapServers;
        this.securityProtocol = securityProtocol;
        this.saslMechanism = saslMechanism;
        this.clientId = clientId;
        this.registryKind = registryKind;
        this.registryUrl = registryUrl;
        this.registrySecret = registrySecret;
    }
}
