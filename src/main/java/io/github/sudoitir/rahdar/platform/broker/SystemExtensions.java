package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Finds the {@link SystemExtension} that serves a cluster, by the system the cluster runs. */
@Component
public class SystemExtensions {

    private final NodeDirectory directory;
    private final List<SystemExtension> extensions;

    public SystemExtensions(NodeDirectory directory, List<SystemExtension> extensions) {
        this.directory = directory;
        this.extensions = extensions;
    }

    /** The cluster's system's implementation of this extension, or empty when it has none. */
    public <T extends SystemExtension> Optional<T> forCluster(UUID clusterId, Class<T> type) {
        String system = directory.system(clusterId);
        return extensions.stream()
                .filter(type::isInstance)
                .filter(e -> e.system().equals(system))
                .map(type::cast)
                .findFirst();
    }

    /**
     * The cluster's system's implementation of this extension.
     *
     * @throws ConflictException when the cluster's system does not offer it
     */
    public <T extends SystemExtension> T require(UUID clusterId, Class<T> type) {
        return forCluster(clusterId, type)
                .orElseThrow(() -> new ConflictException(
                        "unsupported-on-system",
                        "This cluster runs %s, which does not offer this.".formatted(directory.system(clusterId))));
    }
}
