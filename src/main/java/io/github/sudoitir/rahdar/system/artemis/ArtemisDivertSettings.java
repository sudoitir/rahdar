package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertSettings;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** An Artemis node's address settings and divert MBeans, read through its management agent. */
@Component
@RequiredArgsConstructor
class ArtemisDivertSettings implements DivertSettings {

    private final ArtemisNodes nodes;
    private final DivertOperations diverts;

    @Override
    public String system() {
        return ArtemisModule.SYSTEM;
    }

    @Override
    public boolean addressAvailable(BrokerTarget node, String address) {
        ArtemisNode client = nodes.on(node);
        return diverts.addressAvailable(client, client.resolveBrokerObjectName(), address);
    }

    @Override
    public Set<String> retroactiveDiverts(BrokerTarget node) {
        return diverts.listDiverts(nodes.on(node), null, null).stream()
                .filter(DivertRow::retroactiveResource)
                .map(DivertRow::uniqueName)
                .collect(Collectors.toSet());
    }
}
