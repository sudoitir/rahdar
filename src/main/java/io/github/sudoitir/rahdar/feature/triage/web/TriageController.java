package io.github.sudoitir.rahdar.feature.triage.web;

import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealthService;
import io.github.sudoitir.rahdar.feature.triage.web.TriageViews.ConsumerHealthView;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts.Of;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumer-health reads (consumer-health spec, ADR-0089). Read-only; no broker call. */
@RestController
@RequestMapping("/clusters/{clusterId}/consumer-health")
@RequiredArgsConstructor
public class TriageController {

    private final ConsumerHealthService consumerHealth;
    private final ResourceIdTexts ids;

    /**
     * The cluster's queues with their verdicts, worst first unless {@code sort} says
     * otherwise. {@code subscription}, an id, narrows to one — the drawer panel's read.
     *
     * <p>Access is enforced inside the service, before it reads anything, so an
     * unauthorised caller cannot tell a rejected query from a cluster they may not see.
     */
    @GetMapping
    public PagedView<ConsumerHealthView> consumerHealth(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String subscription,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Of idTexts = ids.of(clusterId);
        if (subscription != null && !subscription.isBlank()) {
            ConsumerHealthView one = consumerHealth
                    .forSubscription(clusterId, ResourceIdTexts.name(subscription.trim()))
                    .map(h -> ConsumerHealthView.of(idTexts, h))
                    .orElseThrow(() -> new NotFoundException("subscription", subscription));
            return new PagedView<>(List.of(one), 1, 1, 1L, false);
        }
        PagedView<ConsumerHealth> result = consumerHealth.page(clusterId, ResourceQuery.of(q, page, size, sort));
        return result.map(h -> ConsumerHealthView.of(idTexts, h));
    }
}
