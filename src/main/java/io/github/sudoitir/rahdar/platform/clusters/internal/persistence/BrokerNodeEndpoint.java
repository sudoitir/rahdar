package io.github.sudoitir.rahdar.platform.clusters.internal.persistence;

import io.github.sudoitir.openbroker.Plane;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One row of {@code broker_node_endpoint}: where a node is reached on a plane, over which protocol, and why there. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class BrokerNodeEndpoint {

    /** Where an endpoint's address came from. */
    public enum Source {
        /** The operator gave it as a seed. */
        SEED,
        /** Built from the cluster's management URL pattern and proved by the broker's node id. */
        DERIVED,
        /** The broker advertised it in its topology. */
        DISCOVERED,
        /** The operator set it on the node. */
        MANUAL
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "plane", nullable = false)
    private Plane plane;

    @Column(name = "protocol", nullable = false)
    private String protocol;

    @Column(name = "url", nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source;
}
