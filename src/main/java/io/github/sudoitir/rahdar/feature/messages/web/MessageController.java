package io.github.sudoitir.rahdar.feature.messages.web;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.messages.MessageAction;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Outcome;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.AffectedView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DryRunView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.InDoubtView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessageDetailView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessagePageView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.PartialView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.core.web.ApiExceptionHandler;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browse and act on one subscription's messages (ADR-0021, ADR-0022). Every mutation
 * takes {@code ?dryRun=true} and returns a point-in-time {@link DryRunView}
 * without touching the broker; over the {@code safety.bulk-cap} a real run is a
 * {@code 422 bulk-cap-exceeded} unless {@code ?override=true}. Message I/O is
 * Jolokia-only and gated in the UI by the {@code messageIo} capability
 * (non-negotiable #5); a broker that refuses surfaces the classified
 * {@code BrokerConnectionException} through {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/resources/{kind}")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messages;
    private final ResourceTargets targets;

    @GetMapping("/{path}/messages")
    public MessagePageView browse(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(required = false) UUID node,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ResourceQuery paging = ResourceQuery.ofPage(page, size);
        return messages.browse(
                clusterId, subscription(clusterId, kind, path), node, filter, paging.page(), paging.size());
    }

    @GetMapping("/{path}/messages/{position}")
    public MessageDetailView detail(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @PathVariable String position,
            @RequestParam(required = false) UUID node,
            @RequestParam(required = false) String filter) {
        return messages.detail(
                clusterId, subscription(clusterId, kind, path), MessageActionRequest.number(position), node, filter);
    }

    @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(anyOf = {AffectedView.class, DryRunView.class, PartialView.class})))
    @ApiResponse(
            responseCode = "202",
            description = "The message was sent, but the broker's confirmation never came back: it may or may not"
                    + " be on the broker.",
            content = @Content(schema = @Schema(implementation = InDoubtView.class)))
    @PostMapping("/{path}/messages")
    public ResponseEntity<Object> send(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody SendMessageRequest request) {
        return respond(messages.send(clusterId, subscription(clusterId, kind, path), node, request, dryRun));
    }

    @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(anyOf = {AffectedView.class, DryRunView.class, PartialView.class})))
    @HeldResponse
    @PostMapping("/{path}/messages:{action}")
    public ResponseEntity<Object> action(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @PathVariable String action,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestBody MessageActionRequest request) {
        return respond(messages.execute(
                clusterId,
                subscription(clusterId, kind, path),
                node,
                MessageAction.fromPath(action),
                request,
                dryRun,
                override));
    }

    @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(anyOf = {AffectedView.class, DryRunView.class, PartialView.class})))
    @HeldResponse
    @PostMapping("/{path}:purge")
    public ResponseEntity<Object> purge(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override) {
        return respond(messages.purge(clusterId, subscription(clusterId, kind, path), node, dryRun, override));
    }

    /** The name of the subscription the route names, which the message calls are keyed by. */
    private String subscription(UUID clusterId, String kind, String path) {
        return targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION)
                .name();
    }

    private static ResponseEntity<Object> respond(Attempt<Outcome> attempt) {
        Outcome outcome = unwrap(attempt);
        Object body =
                switch (outcome) {
                    case Outcome.Affected(var count, var node) -> new AffectedView(count, false, node);
                    case Outcome.DryRun(var count, var cap, var overCap, var node) ->
                        new DryRunView(count, cap, overCap, node);
                    case Outcome.Partial(var count, var notDone, var error, var node) ->
                        new PartialView(
                                count, notDone.stream().map(String::valueOf).toList(), error, true, node);
                    case Outcome.InDoubt(var detail, var node) -> new InDoubtView(true, detail, node);
                };
        return outcome instanceof Outcome.InDoubt ? ResponseEntity.accepted().body(body) : ResponseEntity.ok(body);
    }

    private static <T> T unwrap(Attempt<T> attempt) {
        return switch (attempt) {
            case Attempt.Ok<T>(var value) -> value;
            case Attempt.Failed<T>(var kind, var detail) -> throw new BrokerConnectionException(kind, detail);
        };
    }
}
