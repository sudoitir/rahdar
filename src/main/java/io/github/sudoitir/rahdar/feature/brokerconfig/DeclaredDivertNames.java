package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.resources.DeclaredDiverts;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The declaration's divert names, for the queue delete preview (ADR-0084 D2). */
@Component
@RequiredArgsConstructor
class DeclaredDivertNames implements DeclaredDiverts {

    private final BrokerConfigService config;

    @Override
    public Set<String> names(UUID clusterId) {
        return config.current(clusterId)
                .map(c -> c.document().diverts().stream()
                        .map(BrokerConfigDocument.DivertDecl::name)
                        .collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }
}
