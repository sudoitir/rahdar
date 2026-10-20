package io.github.sudoitir.rahdar.feature.routing.web;

import io.github.sudoitir.rahdar.feature.routing.CreateDivertRequest;
import io.github.sudoitir.rahdar.feature.routing.DivertLifecycleService;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.DivertMutationView;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerXmlSnippets;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews.LifecycleOutcomeView;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating and deleting diverts, which only Artemis has, so they live under its namespace.
 *
 * <p>Both need {@code divert:write} — a divert changes where traffic goes rather than what a queue
 * holds, so the queue authorities are the wrong ones — and fan out across every live node with the same
 * per-node outcomes as every other topology mutation.
 *
 * <p>There is no update route, on purpose: Artemis' {@code updateDivert} replaces the configuration, and
 * presenting a partially applied replacement as an atomic edit is what the routing spec forbids.
 * Changing a divert is a delete and a create.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/artemis/diverts")
@RequiredArgsConstructor
public class DivertController {

    private final DivertLifecycleService lifecycle;

    /**
     * Create a divert on every live node.
     *
     * <p>The response always carries the {@code broker.xml} that would make the
     * broker's own configuration match, built from the values just submitted. With
     * {@code ?dryRun=true} nothing is changed, so the form can show the blast radius
     * and that configuration together, before the creating control is armed.
     */
    @PostMapping
    public DivertMutationView createDivert(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateDivertRequest request) {
        return new DivertMutationView(
                respond(lifecycle.createDivert(clusterId, request, dryRun)),
                BrokerXmlSnippets.forDivert(
                        request.name(),
                        request.routingName(),
                        request.address(),
                        request.forwardingAddress(),
                        Boolean.TRUE.equals(request.exclusive()),
                        request.filter(),
                        request.routingType()));
    }

    /**
     * Delete a divert from every live node. Nothing else removes one: a divert
     * created over management outlives the broker process (ADR-0065), so this route
     * is the only thing that takes it away.
     */
    @DeleteMapping("/{name}")
    public LifecycleOutcomeView deleteDivert(
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.deleteDivert(clusterId, name, dryRun));
    }

    private static LifecycleOutcomeView respond(Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome =
                switch (attempt) {
                    case Attempt.Ok<LifecycleOutcome>(var value) -> value;
                    case Attempt.Failed<LifecycleOutcome>(var kind, var detail) ->
                        throw new BrokerConnectionException(kind, detail);
                };
        return LifecycleOutcomeView.of(outcome);
    }
}
