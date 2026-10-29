package io.github.sudoitir.rahdar.platform.broker;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.NodeSpec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * How a registered cluster of one system is reached (ADR-0208): the endpoints and driver options
 * beyond its nodes, the credentials its driver asks for and the SSL bundle it trusts. The gateway
 * builds every connection from the cluster's system's implementation, so no system is assumed.
 */
public interface ConnectionOptions extends SystemExtension {

    /** What a client asks the operator for to register a cluster of this system. */
    Entry entry();

    /** The cluster's endpoints and driver options laid around these nodes. */
    ClusterSpec spec(UUID clusterId, List<NodeSpec> nodes);

    /** The cluster's credentials, asked on every connect so a rotated secret applies at once. */
    CredentialSource credentials(UUID clusterId);

    /** The SSL bundle the cluster is reached with, or empty for the JVM's defaults. */
    Optional<TlsBundle> tls(UUID clusterId);

    /** How a cluster of the system is addressed when it is registered. */
    enum Entry {
        JOLOKIA_SEEDS("jolokia-seeds"),
        BOOTSTRAP("bootstrap");

        private final String wire;

        Entry(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** A Spring SSL bundle by name, and whether the host name is verified against it. */
    record TlsBundle(String name, boolean verifyHostname) {}
}
