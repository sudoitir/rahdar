package io.github.sudoitir.rahdar.kernel.security.web;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.internal.MyAccess;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.AccessSummary;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.MyResourceAccess;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the signed-in caller holds (authorization spec). Any signed-in caller may ask about themselves. */
@RestController
@RequiredArgsConstructor
public class AccessController {

    private final MyAccess access;

    /** The caller's permissions globally, or on one cluster, and their teams. */
    @GetMapping("/me/access")
    public AccessSummary mine(@RequestParam(required = false) UUID clusterId) {
        return access.of(clusterId);
    }

    /** What the caller may do with one queue or address of a cluster, for gating a control on that row. */
    @GetMapping("/me/access/resource")
    public MyResourceAccess resource(
            @RequestParam UUID clusterId, @RequestParam ResourceType type, @RequestParam String name) {
        return access.onResource(clusterId, type, name);
    }
}
