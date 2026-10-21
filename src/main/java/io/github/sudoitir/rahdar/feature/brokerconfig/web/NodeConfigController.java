package io.github.sudoitir.rahdar.feature.brokerconfig.web;

import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigDiffService;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.NodeConfigView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NodeConfigController {

    private final ConfigDiffService configDiff;

    /**
     * One node's effective broker configuration, read live (ADR-0043, ADR-0049).
     *
     * <p>What the node is actually running with, as the broker resolves it — never
     * the {@code broker.xml} on disk, which Rahdar neither reads nor writes. The
     * config-diff endpoint answers "do these two nodes agree"; this answers "what is
     * this node set to", which is the question asked first.
     *
     * <p>Read-only introspection at the same permission tier as the topology read,
     * and deliberately unaudited: only mutating calls write an audit event.
     */
    @GetMapping("/clusters/{clusterId}/artemis/nodes/{nodeId}/config")
    public NodeConfigView nodeConfig(@PathVariable UUID clusterId, @PathVariable UUID nodeId) {
        return configDiff.nodeConfig(clusterId, nodeId);
    }
}
