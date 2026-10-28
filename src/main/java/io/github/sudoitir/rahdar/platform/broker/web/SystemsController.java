package io.github.sudoitir.rahdar.platform.broker.web;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions.Entry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The broker systems this Rahdar loads a driver for, which registration offers as its first choice (ADR-0213). */
@RestController
@RequiredArgsConstructor
public class SystemsController {

    private final BrokerDrivers drivers;
    private final List<ConnectionOptions> connections;

    /**
     * @param id the broker SDK's id of the system, such as {@code kafka}
     * @param supportedVersions the version range the driver is tested against, in Maven range syntax
     * @param connection whether a cluster is registered from Jolokia seeds or from bootstrap servers
     */
    public record SupportedSystemView(String id, String name, String supportedVersions, Entry connection) {}

    @GetMapping("/systems")
    public PagedView<SupportedSystemView> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        List<SupportedSystemView> systems = drivers.systems().stream()
                .flatMap(system -> connections.stream()
                        .filter(c -> c.system().equals(system.id()))
                        .map(c -> new SupportedSystemView(
                                system.id(), system.name(), system.supportedVersions(), c.entry())))
                .toList();
        return ResourceQuery.ofPage(page, size).paginate(systems, null);
    }
}
