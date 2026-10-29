package io.github.sudoitir.rahdar.platform.clusters.web;

import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerAccount;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterConnectionView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterSummary;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.TopologyView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 1 cluster API (base {@code /api/v1}). Realtime is SSE only (ADR-0003);
 * every command here is an ordinary POST/PATCH/DELETE. Destructive operations take
 * {@code ?dryRun=true} and typed confirmation is enforced in the UI
 * (non-negotiable #2).
 */
@RestController
@RequestMapping("/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterService service;
    private final HeavyRequests heavy;
    private final ActorResolver actors;

    /** The topology is assembled from the nodes' stored state; a few at once per user, the database's 30 s. */
    private static final HeavyRequests.Endpoint TOPOLOGY =
            new HeavyRequests.Endpoint("topology reads", 4, Duration.ofSeconds(30), Integer.MAX_VALUE);

    /** Register from a list of seed URLs (ADR-0013). {@code ?dryRun=true} probes and returns without persisting. */
    @ApiResponse(
            responseCode = "200",
            description = "dryRun=true: connection probe result, nothing persisted",
            content = @Content(schema = @Schema(implementation = ConnectionCheck.class)))
    @ApiResponse(
            responseCode = "201",
            description = "cluster registered",
            content = @Content(schema = @Schema(implementation = ClusterDetail.class)))
    @HeldResponse
    @PostMapping
    public ResponseEntity<Object> register(
            @Valid @RequestBody RegisterClusterRequest request, @RequestParam(defaultValue = "false") boolean dryRun) {
        if (dryRun) {
            return ResponseEntity.ok(service.checkConnection(request));
        }
        ClusterDetail detail = unwrap(service.register(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @GetMapping
    public PagedView<ClusterSummary> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(service.list(), null);
    }

    @GetMapping("/{clusterId}")
    public ClusterDetail get(@PathVariable UUID clusterId) {
        return service.get(clusterId);
    }

    @HeldResponse
    @DeleteMapping("/{clusterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID clusterId) {
        service.delete(clusterId);
    }

    @GetMapping("/{clusterId}/capabilities")
    public CapabilitiesView capabilities(@PathVariable UUID clusterId) {
        return service.capabilities(clusterId);
    }

    @GetMapping("/{clusterId}/topology")
    public TopologyView topology(@PathVariable UUID clusterId) {
        return heavy.run(TOPOLOGY, actors.resolve().displayName(), _ -> service.topology(clusterId));
    }

    @GetMapping("/{clusterId}/health")
    public HealthView health(@PathVariable UUID clusterId) {
        return service.health(clusterId);
    }

    /**
     * Edit the cluster's connection: name, description, seeds, management URL pattern, TLS bundle
     * and both accounts, in one request. {@code ?dryRun=true} checks the edited connection node by node and saves
     * nothing.
     */
    @ApiResponse(
            responseCode = "200",
            description = "dryRun=true: per-node probe result, nothing saved; otherwise the saved cluster",
            content = @Content(schema = @Schema(oneOf = {ConnectionCheck.class, ClusterConnectionView.class})))
    @HeldResponse
    @PatchMapping("/{clusterId}")
    public Object update(
            @PathVariable UUID clusterId,
            @Valid @RequestBody UpdateClusterRequest request,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return dryRun ? service.checkUpdate(clusterId, request) : service.updateConnection(clusterId, request);
    }

    @HeldResponse
    @PatchMapping("/{clusterId}/nodes/{nodeId}")
    public NodeEndpointView overrideNode(
            @PathVariable UUID clusterId, @PathVariable UUID nodeId, @Valid @RequestBody NodeOverrideRequest request) {
        return unwrap(service.overrideNodeUrl(clusterId, nodeId, request));
    }

    /**
     * A {@link Attempt.Failed} becomes a classified {@link BrokerConnectionException} for the advice to render.
     * Only management calls fail an attempt here (Core results are reported per node, never as a failure), so a
     * rejected credential is the management account.
     */
    private static <T> T unwrap(Attempt<T> attempt) {
        return switch (attempt) {
            case Attempt.Ok<T>(var value) -> value;
            case Attempt.Failed<T>(var kind, var detail) ->
                throw kind == BrokerConnectionException.Kind.CREDENTIALS_REJECTED
                        ? BrokerConnectionException.credentialsRejected(BrokerAccount.MANAGEMENT, detail, null)
                        : new BrokerConnectionException(kind, detail);
        };
    }
}
