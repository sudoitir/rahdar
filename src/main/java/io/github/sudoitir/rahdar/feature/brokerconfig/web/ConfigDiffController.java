package io.github.sudoitir.rahdar.feature.brokerconfig.web;

import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigDiffService;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews.ConfigDiffView;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Broker configuration compared across every node of one cluster (ADR-0043, ADR-0178).
 * Read-only introspection at the same permission tier as the topology read, and
 * deliberately unaudited — only mutating calls write an audit event.
 *
 * <p>Every node is compared against the others; {@code nodes} narrows the comparison to at
 * least two chosen nodes.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/artemis/config-diff")
@RequiredArgsConstructor
public class ConfigDiffController {

    private final ConfigDiffService configDiff;

    @GetMapping
    public ConfigDiffView compare(@PathVariable UUID clusterId, @RequestParam(required = false) Set<UUID> nodes) {
        return configDiff.compare(clusterId, nodes);
    }
}
