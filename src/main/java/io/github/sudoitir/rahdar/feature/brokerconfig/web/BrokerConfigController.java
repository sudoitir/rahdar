package io.github.sudoitir.rahdar.feature.brokerconfig.web;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDriftService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendationService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Source;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigRequests.ApplyRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigRequests.BridgeCredentialRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigRequests.ConfigureRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigRequests.DeclareRecommendedRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigRequests.SaveDeclarationRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.AdoptionView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.ApplyDetailView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.ApplyHistoryView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.ApplyOutcomeView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.BridgeCredentialView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.CatalogueView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.DeclarationView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.DriftReportView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.ImportResultView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.NodeApplyView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.NodeConnectorsView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.PlanView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.RecommendationsView;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.RevisionView;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A cluster's declared configuration: what it should be, what each node is, and
 * the apply that closes the gap (ADR-0067).
 *
 * <p>Reads need {@code cluster:read}. Editing the declaration — saving a revision,
 * importing XML, adopting, changing the mode — needs {@code config:write}. Writing
 * to a broker needs {@code config:apply}; it is a separate authority because a
 * declaration is harmless until something applies it.
 *
 * <p>Every apply takes {@code ?dryRun=true} and returns the plan without touching a
 * broker; the real run must echo that plan's hash and acknowledge its High hazards,
 * so what the operator confirmed is what runs.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/artemis/config")
@RequiredArgsConstructor
public class BrokerConfigController {

    private final BrokerConfigService config;
    private final BrokerConfigDriftService drift;
    private final BrokerConfigApplyService apply;
    private final BrokerConfigRecommendationService recommendations;
    private final ClusterAccessGuard clusterAccess;

    // ---- declaration ------------------------------------------------------

    @GetMapping
    public DeclarationView get(@PathVariable UUID clusterId) {
        return DeclarationView.of(config.get(clusterId));
    }

    /** Save a new revision; {@code 409 stale-revision} when someone saved first. */
    @PutMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationView save(@PathVariable UUID clusterId, @Valid @RequestBody SaveDeclarationRequest request) {
        return DeclarationView.of(config.save(
                clusterId,
                request.document().toDocument(),
                request.expectedRevision(),
                request.note(),
                request.source() == null ? Source.EDIT : Source.valueOf(request.source()),
                request.confirm()));
    }

    @PatchMapping("/mode")
    public DeclarationView configure(@PathVariable UUID clusterId, @Valid @RequestBody ConfigureRequest request) {
        return DeclarationView.of(config.configure(
                clusterId,
                ApplyMode.valueOf(request.applyMode()),
                request.reportUndeclared(),
                request.undeclaredExclusions()));
    }

    @GetMapping("/revisions")
    public PagedView<RevisionView> revisions(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        config.revisions(clusterId).stream()
                                .map(RevisionView::of)
                                .toList(),
                        null);
    }

    @GetMapping("/revisions/{number}")
    public RevisionView revision(@PathVariable UUID clusterId, @PathVariable int number) {
        return RevisionView.of(config.revision(clusterId, number));
    }

    /** Parse a {@code broker.xml} or fragment into a declaration. Nothing is saved. */
    @PostMapping(
            value = "/import-xml",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ImportResultView importXml(@PathVariable UUID clusterId, @RequestBody String xml) {
        return ImportResultView.of(config.importXml(clusterId, xml));
    }

    /**
     * The declaration as a {@code <core>} fragment, for a config-managed cluster. It is an
     * attachment, so a browser that is sent here saves the file and never renders what is in it
     * (ADR-0168).
     */
    @GetMapping(value = "/export-xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> exportXml(
            @PathVariable UUID clusterId, @RequestParam(required = false) Integer revision) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("broker-config.xml")
                                .build()
                                .toString())
                .body(config.exportXml(clusterId, revision));
    }

    /** Build a declaration from what the live nodes are running. Nothing is saved. */
    @PostMapping("/adopt")
    public AdoptionView adopt(@PathVariable UUID clusterId) {
        return AdoptionView.of(config.adopt(clusterId));
    }

    // ---- recommendations (ADR-0068) ---------------------------------------

    /** What the capability probe suggests declaring, and what still needs broker.xml. */
    @GetMapping("/recommendations")
    public RecommendationsView recommendations(@PathVariable UUID clusterId) {
        return RecommendationsView.of(recommendations.recommend(clusterId));
    }

    /**
     * Declare the appliable recommendations as a new revision. Nothing is written to
     * a broker: the caller opens the plan and applies it through the ordinary gates.
     */
    @PostMapping("/recommendations/declare")
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationView declareRecommended(
            @PathVariable UUID clusterId, @RequestBody(required = false) DeclareRecommendedRequest request) {
        DeclareRecommendedRequest body = request == null ? new DeclareRecommendedRequest(null, null) : request;
        return DeclarationView.of(recommendations.declare(clusterId, body.capabilities(), body.roles()));
    }

    /** Static, but cluster-addressed: an ungranted caller must not learn the cluster exists. */
    @GetMapping("/catalogue")
    public CatalogueView catalogue(@PathVariable UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return CatalogueView.current();
    }

    // ---- drift ------------------------------------------------------------

    @GetMapping("/drift")
    public DriftReportView drift(@PathVariable UUID clusterId) {
        return DriftReportView.of(config.get(clusterId));
    }

    /** Evaluate every live node now. A read of the brokers; nothing is changed. */
    @PostMapping("/drift/evaluate")
    public DriftReportView evaluate(@PathVariable UUID clusterId) {
        return DriftReportView.of(drift.evaluate(clusterId));
    }

    // ---- apply ------------------------------------------------------------

    /**
     * Apply the declaration, canary first. With {@code ?dryRun=true} the plan is
     * returned with its hazards and hash and no broker is written; the real run
     * refuses when the plan changed since the preview, when a High hazard is not
     * acknowledged, or when another apply holds the cluster.
     */
    @PostMapping("/apply")
    public ApplyOutcomeView apply(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestBody(required = false) ApplyRequest request) {
        ApplyRequest body = request == null ? new ApplyRequest(null, null, null, null, null, null, null) : request;
        BrokerConfigApplyRequest command = new BrokerConfigApplyRequest(
                body.revision(),
                body.nodeIds(),
                body.canaryNodeId(),
                Boolean.TRUE.equals(body.removeUndeclared()),
                body.acknowledgedHazards(),
                body.expectedPlanHash(),
                override,
                body.stepIds());
        return ApplyOutcomeView.of(dryRun ? apply.plan(clusterId, command) : apply.apply(clusterId, command));
    }

    // ---- bridges: connector names and credentials -------------------------

    /**
     * The connector names each serving node declares, so a bridge can reference one
     * that exists. One extra batched read per node, on this screen only — a name the
     * broker does not know is accepted and silently ignored, which is exactly the
     * failure this read prevents.
     */
    @GetMapping("/connectors")
    public PagedView<NodeConnectorsView> connectors(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        config.connectors(clusterId).stream()
                                .map(NodeConnectorsView::of)
                                .toList(),
                        null);
    }

    /** The credentials a bridge can reference, with their usernames and never a password (ADR-0092). */
    @GetMapping("/bridge-credentials")
    public PagedView<BridgeCredentialView> bridgeCredentials(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        config.bridgeCredentials(clusterId).stream()
                                .map(BridgeCredentialView::of)
                                .toList(),
                        null);
    }

    /** Store or replace one. The declaration carries only {@code ref}; the secret goes into the vault. */
    @PutMapping("/bridge-credentials/{ref}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setBridgeCredential(
            @PathVariable UUID clusterId,
            @PathVariable String ref,
            @Valid @RequestBody BridgeCredentialRequest request) {
        config.setBridgeCredential(clusterId, ref, request.username(), request.password());
    }

    @DeleteMapping("/bridge-credentials/{ref}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgetBridgeCredential(@PathVariable UUID clusterId, @PathVariable String ref) {
        config.forgetBridgeCredential(clusterId, ref);
    }

    @GetMapping("/applies")
    public PagedView<ApplyHistoryView> applies(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return PagedView.of(apply.history(clusterId, ResourceQuery.ofPage(page, size)), ApplyHistoryView::of);
    }

    @GetMapping("/applies/{id}")
    public ApplyDetailView applyDetail(@PathVariable UUID clusterId, @PathVariable long id) {
        BrokerConfigApplyService.Detail d =
                apply.detail(clusterId, id).orElseThrow(() -> new NotFoundException("apply", id));
        return new ApplyDetailView(
                ApplyHistoryView.of(d.apply()),
                PlanView.of(d.plan()),
                d.nodes().stream().map(NodeApplyView::of).toList());
    }
}
