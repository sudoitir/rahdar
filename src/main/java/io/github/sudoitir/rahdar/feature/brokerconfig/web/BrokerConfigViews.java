package io.github.sudoitir.rahdar.feature.brokerconfig.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.feature.brokerconfig.AddressSettingKey;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDriftService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerXmlCodec;
import io.github.sudoitir.rahdar.feature.brokerconfig.PermissionType;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan;
import io.github.sudoitir.rahdar.feature.brokerconfig.Violation;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyEntity;
import io.github.sudoitir.rahdar.platform.clusters.ClusterSecrets;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The declared broker configuration API (ADR-0067). {@code @Schema} on every
 * component so the generated OpenAPI document declares requiredness and
 * nullability honestly (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class BrokerConfigViews {

    private BrokerConfigViews() {}

    // ---- the document ----------------------------------------------------

    @Schema(
            name = "ConfigDocumentView",
            description = "A cluster's declared configuration: the sections the management API can apply")
    public record DocumentView(
            @Schema(requiredMode = REQUIRED) int version,
            @Schema(requiredMode = REQUIRED) List<AddressView> addresses,
            @Schema(requiredMode = REQUIRED) List<AddressSettingView> addressSettings,
            @Schema(requiredMode = REQUIRED) List<SecuritySettingView> securitySettings,
            @Schema(requiredMode = REQUIRED) List<DivertView> diverts,
            @Schema(requiredMode = REQUIRED) List<BridgeView> bridges) {

        public DocumentView {
            addresses = addresses == null ? List.of() : addresses;
            addressSettings = addressSettings == null ? List.of() : addressSettings;
            securitySettings = securitySettings == null ? List.of() : securitySettings;
            diverts = diverts == null ? List.of() : diverts;
            bridges = bridges == null ? List.of() : bridges;
        }

        public static DocumentView of(BrokerConfigDocument d) {
            return new DocumentView(
                    d.version(),
                    d.addresses().stream().map(AddressView::of).toList(),
                    d.addressSettings().stream().map(AddressSettingView::of).toList(),
                    d.securitySettings().stream().map(SecuritySettingView::of).toList(),
                    d.diverts().stream().map(DivertView::of).toList(),
                    d.bridges().stream().map(BridgeView::of).toList());
        }

        public BrokerConfigDocument toDocument() {
            return new BrokerConfigDocument(
                    version == 0 ? BrokerConfigDocument.CURRENT_VERSION : version,
                    addresses.stream().map(AddressView::toDecl).toList(),
                    addressSettings.stream().map(AddressSettingView::toDecl).toList(),
                    securitySettings.stream().map(SecuritySettingView::toDecl).toList(),
                    diverts.stream().map(DivertView::toDecl).toList(),
                    bridges.stream().map(BridgeView::toDecl).toList());
        }
    }

    @Schema(name = "ConfigAddressView", description = "An address and the queues bound to it")
    public record AddressView(
            @Schema(requiredMode = REQUIRED) String name,

            @ArraySchema(
                    schema = @Schema(allowableValues = {"ANYCAST", "MULTICAST"}),
                    arraySchema = @Schema(requiredMode = REQUIRED))
            List<String> routingTypes,

            @Schema(requiredMode = REQUIRED) List<QueueView> queues) {
        static AddressView of(AddressDecl a) {
            return new AddressView(
                    a.name(),
                    new ArrayList<>(new TreeSet<>(a.routingTypes())),
                    a.queues().stream().map(QueueView::of).toList());
        }

        AddressDecl toDecl() {
            return new AddressDecl(
                    name,
                    routingTypes == null ? Set.of() : Set.copyOf(routingTypes),
                    queues == null
                            ? List.of()
                            : queues.stream().map(QueueView::toDecl).toList());
        }
    }

    @Schema(
            name = "ConfigQueueView",
            description = "A queue as the create action accepts it; a null field is not declared")
    public record QueueView(
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"ANYCAST", "MULTICAST"})
            String routingType,

            @Schema(nullable = true) String filter,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(nullable = true) Integer maxConsumers,
            @Schema(nullable = true) Boolean purgeOnNoConsumers,
            @Schema(nullable = true) Boolean exclusive,
            @Schema(nullable = true) Boolean nonDestructive,
            @Schema(nullable = true) Long ringSize) {
        static QueueView of(QueueDecl q) {
            return new QueueView(
                    q.name(),
                    q.routingType(),
                    q.filter(),
                    q.durable(),
                    q.maxConsumers(),
                    q.purgeOnNoConsumers(),
                    q.exclusive(),
                    q.nonDestructive(),
                    q.ringSize());
        }

        QueueDecl toDecl() {
            return new QueueDecl(
                    name,
                    routingType,
                    filter,
                    durable,
                    maxConsumers,
                    purgeOnNoConsumers,
                    exclusive,
                    nonDestructive,
                    ringSize);
        }
    }

    @Schema(
            name = "ConfigAddressSettingView",
            description =
                    "One address-setting match; values are keyed by the catalogue's JSON names and hold only declared keys")
    public record AddressSettingView(
            @Schema(requiredMode = REQUIRED) String match,
            @Schema(requiredMode = REQUIRED) Map<String, Object> values) {
        static AddressSettingView of(AddressSettingDecl s) {
            return new AddressSettingView(s.match(), s.values());
        }

        AddressSettingDecl toDecl() {
            return new AddressSettingDecl(match, values);
        }
    }

    @Schema(
            name = "ConfigSecuritySettingView",
            description = "One security-setting match: permission type (send, consume, …) to the roles holding it")
    public record SecuritySettingView(
            @Schema(requiredMode = REQUIRED) String match,
            @Schema(requiredMode = REQUIRED) Map<String, List<String>> permissions) {
        static SecuritySettingView of(SecuritySettingDecl s) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (PermissionType t : PermissionType.values()) {
                Set<String> roles = s.roles(t);
                if (!roles.isEmpty()) {
                    out.put(t.xmlName(), new ArrayList<>(roles));
                }
            }
            return new SecuritySettingView(s.match(), out);
        }

        SecuritySettingDecl toDecl() {
            Map<PermissionType, Set<String>> out = new EnumMap<>(PermissionType.class);
            if (permissions != null) {
                permissions.forEach((type, roles) -> PermissionType.byXmlName(type)
                        .ifPresent(t -> out.put(t, roles == null ? Set.of() : Set.copyOf(roles))));
            }
            return new SecuritySettingDecl(match, out);
        }
    }

    @Schema(name = "ConfigDivertView", description = "One divert")
    public record DivertView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) String forwardingAddress,
            @Schema(nullable = true) String filter,
            @Schema(requiredMode = REQUIRED) boolean exclusive,

            @Schema(
                    nullable = true,
                    allowableValues = {"STRIP", "PASS", "ANYCAST", "MULTICAST"})
            String routingType,

            @Schema(nullable = true) String transformerClassName,
            @Schema(requiredMode = REQUIRED) Map<String, String> transformerProperties) {
        static DivertView of(DivertDecl d) {
            return new DivertView(
                    d.name(),
                    d.address(),
                    d.forwardingAddress(),
                    d.filter(),
                    d.exclusive(),
                    d.routingType(),
                    d.transformerClassName(),
                    d.transformerProperties());
        }

        DivertDecl toDecl() {
            return new DivertDecl(
                    name,
                    address,
                    forwardingAddress,
                    filter,
                    exclusive,
                    routingType,
                    transformerClassName,
                    transformerProperties);
        }
    }

    @Schema(
            name = "ConfigTransformerView",
            description = "A transformer: the class the broker loads and its properties")
    public record TransformerView(
            @Schema(requiredMode = REQUIRED) String className,
            @Schema(requiredMode = REQUIRED) Map<String, String> properties) {
        static TransformerView of(BrokerConfigDocument.TransformerDecl t) {
            return t == null ? null : new TransformerView(t.className(), t.properties());
        }

        static BrokerConfigDocument.TransformerDecl toDecl(TransformerView v) {
            return v == null ? null : BrokerConfigDocument.TransformerDecl.of(v.className(), v.properties());
        }
    }

    @Schema(
            name = "ConfigBridgeView",
            description = "One bridge; a null field is not declared and keeps the broker's default."
                    + " The credential is a reference into Rahdar's vault and never a value")
    public record BridgeView(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String queueName,
            @Schema(requiredMode = REQUIRED) String forwardingAddress,
            @Schema(nullable = true) String filter,
            @Schema(nullable = true) TransformerView transformer,
            @Schema(requiredMode = REQUIRED) List<String> staticConnectors,
            @Schema(nullable = true) String discoveryGroupName,
            @Schema(nullable = true) Boolean ha,
            @Schema(nullable = true) Boolean useDuplicateDetection,
            @Schema(nullable = true) Long retryInterval,
            @Schema(nullable = true) Double retryIntervalMultiplier,
            @Schema(nullable = true) Long maxRetryInterval,
            @Schema(nullable = true) Integer initialConnectAttempts,
            @Schema(nullable = true) Integer reconnectAttempts,
            @Schema(nullable = true) Integer confirmationWindowSize,
            @Schema(nullable = true) Integer producerWindowSize,
            @Schema(nullable = true) Integer minLargeMessageSize,
            @Schema(nullable = true) Long checkPeriod,
            @Schema(nullable = true) Long connectionTtl,

            @Schema(
                    nullable = true,
                    allowableValues = {"STRIP", "PASS", "ANYCAST", "MULTICAST", "OFFSET"})
            String routingType,

            @Schema(nullable = true) Integer concurrency,
            @Schema(nullable = true) String clientId,

            @Schema(nullable = true, description = "Names a credential held in Rahdar's vault; never a password")
            String credentialRef) {

        static BridgeView of(BrokerConfigDocument.BridgeDecl b) {
            return new BridgeView(
                    b.name(),
                    b.queueName(),
                    b.forwardingAddress(),
                    b.filter(),
                    TransformerView.of(b.transformer()),
                    b.staticConnectors(),
                    b.discoveryGroupName(),
                    b.ha(),
                    b.useDuplicateDetection(),
                    b.retryInterval(),
                    b.retryIntervalMultiplier(),
                    b.maxRetryInterval(),
                    b.initialConnectAttempts(),
                    b.reconnectAttempts(),
                    b.confirmationWindowSize(),
                    b.producerWindowSize(),
                    b.minLargeMessageSize(),
                    b.checkPeriod(),
                    b.connectionTtl(),
                    b.routingType(),
                    b.concurrency(),
                    b.clientId(),
                    b.credentialRef());
        }

        BrokerConfigDocument.BridgeDecl toDecl() {
            return new BrokerConfigDocument.BridgeDecl(
                    name,
                    queueName,
                    forwardingAddress,
                    filter,
                    TransformerView.toDecl(transformer),
                    staticConnectors,
                    discoveryGroupName,
                    ha,
                    useDuplicateDetection,
                    retryInterval,
                    retryIntervalMultiplier,
                    maxRetryInterval,
                    initialConnectAttempts,
                    reconnectAttempts,
                    confirmationWindowSize,
                    producerWindowSize,
                    minLargeMessageSize,
                    checkPeriod,
                    connectionTtl,
                    routingType,
                    concurrency,
                    clientId,
                    credentialRef);
        }
    }

    @Schema(
            name = "ConfigNodeConnectorsView",
            description = "One node's connector names, for a bridge to reference."
                    + " known=false means Rahdar could not read them, not that there are none")
    public record NodeConnectorsView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) List<String> names,
            @Schema(requiredMode = REQUIRED) boolean known,
            @Schema(nullable = true) String reason) {
        public static NodeConnectorsView of(BrokerConfigService.NodeConnectors c) {
            return new NodeConnectorsView(c.nodeId(), c.nodeName(), c.names(), c.known(), c.reason());
        }
    }

    @Schema(
            name = "ConfigBridgeCredentialView",
            description = "A credential a bridge can reference. The password is never returned")
    public record BridgeCredentialView(
            @Schema(requiredMode = REQUIRED) String ref,
            @Schema(nullable = true) String username) {
        public static BridgeCredentialView of(ClusterSecrets.Credential c) {
            return new BridgeCredentialView(c.ref(), c.username());
        }
    }

    // ---- the declaration -------------------------------------------------

    @Schema(
            name = "ConfigDeclarationView",
            description =
                    "A cluster's declaration: the current revision, how it is applied, and each node's last evaluation")
    public record DeclarationView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) String clusterName,

            @Schema(requiredMode = REQUIRED, description = "False until the first revision is saved")
            boolean declared,

            @Schema(requiredMode = REQUIRED, description = "0 while undeclared")
            int revision,

            @Schema(requiredMode = REQUIRED) DocumentView document,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"RAHDAR_MANAGED", "CONFIG_MANAGED"})
            String applyMode,

            @Schema(requiredMode = REQUIRED) boolean reportUndeclared,
            @Schema(requiredMode = REQUIRED) List<String> undeclaredExclusions,
            @Schema(nullable = true) Instant updatedAt,
            @Schema(nullable = true) String updatedBy,

            @Schema(
                    nullable = true,
                    allowableValues = {"EDIT", "IMPORT_XML", "ADOPT", "MCP", "RECOMMENDED"})
            String source,

            @Schema(nullable = true) String note,
            @Schema(requiredMode = REQUIRED) List<NodeStateView> nodes,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "How often the scheduled pass evaluates this cluster, in seconds"
                            + " (config.drift-interval)")
            long driftIntervalSeconds) {
        public static DeclarationView of(BrokerConfigService.Declaration d) {
            return new DeclarationView(
                    d.clusterId(),
                    d.clusterName(),
                    d.declared(),
                    d.revision(),
                    DocumentView.of(d.document()),
                    d.applyMode().name(),
                    d.reportUndeclared(),
                    d.undeclaredExclusions(),
                    d.updatedAt(),
                    d.updatedBy(),
                    d.source() == null ? null : d.source().name(),
                    d.note(),
                    d.nodes().stream().map(NodeStateView::of).toList(),
                    d.driftIntervalSeconds());
        }
    }

    @Schema(name = "ConfigNodeStateView", description = "One node's last drift evaluation")
    public record NodeStateView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean live,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"IN_SYNC", "DRIFTED", "NOT_EVALUATED", "UNREACHABLE"})
            String state,

            @Schema(nullable = true) String detail,
            @Schema(nullable = true) Integer verifiedRevision,
            @Schema(nullable = true) Instant evaluatedAt,
            @Schema(requiredMode = REQUIRED) List<DriftFindingView> findings,

            @Schema(
                    nullable = true,
                    description = "Why an IN_SYNC node agrees: Rahdar applied and read it back, the"
                            + " declaration was adopted from this cluster, or an evaluation simply found"
                            + " them equal. Null unless the node is IN_SYNC",
                    allowableValues = {"VERIFIED_APPLY", "ADOPTED", "OBSERVED_MATCH"})
            String basis,

            @Schema(nullable = true, description = "The apply id or revision number the basis points at")
            Long basisRef) {
        static NodeStateView of(BrokerConfigService.NodeState s) {
            return new NodeStateView(
                    s.nodeId(),
                    s.nodeName(),
                    s.live(),
                    s.state().name(),
                    s.detail(),
                    s.verifiedRevision(),
                    s.evaluatedAt(),
                    s.findings().stream().map(DriftFindingView::of).toList(),
                    s.basis() == null ? null : s.basis().name(),
                    s.basisRef());
        }

        static NodeStateView of(BrokerConfigDriftService.NodeReport r, Instant at, int revision) {
            return new NodeStateView(
                    r.nodeId(),
                    r.nodeName(),
                    r.live(),
                    r.state().name(),
                    r.detail(),
                    r.state()
                                            == io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence
                                                    .BrokerConfigNodeStateEntity.State.IN_SYNC
                                    || r.state()
                                            == io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence
                                                    .BrokerConfigNodeStateEntity.State.DRIFTED
                            ? revision
                            : null,
                    at,
                    r.findings().stream().map(DriftFindingView::of).toList(),
                    r.basis() == null ? null : r.basis().name(),
                    r.basisRef());
        }
    }

    @Schema(
            name = "ConfigDriftFindingView",
            description = "One way a node differs from the declaration; declared beside observed")
    public record DriftFindingView(
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(nullable = true) String section,
            @Schema(nullable = true) String key,
            @Schema(requiredMode = REQUIRED) String detail,
            @Schema(requiredMode = REQUIRED) Map<String, Object> declared,
            @Schema(requiredMode = REQUIRED) Map<String, Object> observed) {
        static DriftFindingView of(BrokerConfigDriftService.DriftFinding f) {
            return new DriftFindingView(
                    f.kind().name(),
                    f.section() == null ? null : f.section().name(),
                    f.key(),
                    f.detail(),
                    f.declared() == null ? Map.of() : f.declared(),
                    f.observed() == null ? Map.of() : f.observed());
        }
    }

    @Schema(
            name = "ConfigDriftReportView",
            description = "A drift evaluation of every node against the current revision")
    public record DriftReportView(
            @Schema(requiredMode = REQUIRED) int revision,

            @Schema(nullable = true, description = "Null until an evaluation has run")
            Instant evaluatedAt,

            @Schema(requiredMode = REQUIRED) List<NodeStateView> nodes) {
        /** The last stored evaluation; {@code evaluatedAt} is null until one has run. */
        public static DriftReportView of(BrokerConfigService.Declaration d) {
            Instant latest = d.nodes().stream()
                    .map(BrokerConfigService.NodeState::evaluatedAt)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);
            return new DriftReportView(
                    d.revision(),
                    latest,
                    d.nodes().stream().map(NodeStateView::of).toList());
        }

        public static DriftReportView of(BrokerConfigDriftService.Report r) {
            return new DriftReportView(
                    r.revision(),
                    r.evaluatedAt(),
                    r.nodes().stream()
                            .map(n -> NodeStateView.of(n, r.evaluatedAt(), r.revision()))
                            .toList());
        }
    }

    @Schema(name = "ConfigRevisionView", description = "One saved revision")
    public record RevisionView(
            @Schema(requiredMode = REQUIRED) int revision,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) String createdBy,
            @Schema(requiredMode = REQUIRED) String source,
            @Schema(nullable = true) String note,
            @Schema(requiredMode = REQUIRED) DocumentView document) {
        public static RevisionView of(BrokerConfigService.Revision r) {
            return new RevisionView(
                    r.revision(),
                    r.createdAt(),
                    r.createdBy(),
                    r.source().name(),
                    r.note(),
                    DocumentView.of(r.document()));
        }
    }

    // ---- import / adopt ---------------------------------------------------

    @Schema(
            name = "ConfigImportResultView",
            description = "What a pasted broker.xml produced: the document, what could not be carried, and errors")
    public record ImportResultView(
            @Schema(requiredMode = REQUIRED) DocumentView document,
            @Schema(requiredMode = REQUIRED) List<UnsupportedView> unsupported,
            @Schema(requiredMode = REQUIRED) List<FieldErrorView> errors) {
        public static ImportResultView of(BrokerXmlCodec.ParseResult r) {
            return new ImportResultView(
                    DocumentView.of(r.document()),
                    r.unsupported().stream()
                            .map(u -> new UnsupportedView(u.path(), u.reason()))
                            .toList(),
                    r.errors().stream().map(FieldErrorView::of).toList());
        }
    }

    @Schema(name = "ConfigUnsupportedView", description = "An element the import saw and did not carry, by path")
    public record UnsupportedView(
            @Schema(requiredMode = REQUIRED) String path,
            @Schema(requiredMode = REQUIRED) String reason) {}

    @Schema(name = "ConfigFieldErrorView", description = "A problem with one field of the declaration")
    public record FieldErrorView(
            @Schema(requiredMode = REQUIRED) String field,
            @Schema(requiredMode = REQUIRED) String message) {
        static FieldErrorView of(Violation v) {
            return new FieldErrorView(v.path(), v.message());
        }
    }

    @Schema(
            name = "ConfigAdoptionView",
            description = "A declaration built from what the live nodes are running, for review before saving")
    public record AdoptionView(
            @Schema(requiredMode = REQUIRED) DocumentView document,
            @Schema(requiredMode = REQUIRED) List<String> notes,
            @Schema(requiredMode = REQUIRED) List<String> disagreements,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Drift findings this adoption would close without writing to any broker."
                            + " Non-empty means the save needs the cluster's name as confirmation")
            List<ClosedFindingView> closes) {
        public static AdoptionView of(BrokerConfigService.Adoption a) {
            return new AdoptionView(
                    DocumentView.of(a.document()),
                    a.notes(),
                    a.disagreements(),
                    a.closes().stream().map(ClosedFindingView::of).toList());
        }
    }

    @Schema(
            name = "ConfigClosedFindingView",
            description = "A drift finding an adoption would erase, and the node that reported it")
    public record ClosedFindingView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) DriftFindingView finding) {
        static ClosedFindingView of(BrokerConfigService.ClosedFinding c) {
            return new ClosedFindingView(c.nodeId(), c.nodeName(), DriftFindingView.of(c.finding()));
        }
    }

    // ---- plan and apply ----------------------------------------------------

    @Schema(
            name = "ConfigPlanView",
            description =
                    "What an apply would do: ordered steps per node, hazards, findings, and the hash a real run must name")
    public record PlanView(
            @Schema(requiredMode = REQUIRED) List<NodePlanView> nodes,
            @Schema(requiredMode = REQUIRED) List<HazardView> hazards,
            @Schema(requiredMode = REQUIRED) List<ConfigFindingView> findings,
            @Schema(requiredMode = REQUIRED) String planHash,
            @Schema(requiredMode = REQUIRED) int stepCount,
            @Schema(nullable = true) UUID canaryNodeId) {
        public static PlanView of(Plan p) {
            return new PlanView(
                    p.nodes().stream().map(NodePlanView::of).toList(),
                    p.hazards().stream().map(HazardView::of).toList(),
                    p.findings().stream().map(ConfigFindingView::of).toList(),
                    p.planHash(),
                    p.stepCount(),
                    p.canaryNodeId());
        }
    }

    @Schema(name = "ConfigNodePlanView", description = "One node's ordered steps")
    public record NodePlanView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean live,
            @Schema(nullable = true) String unavailableReason,
            @Schema(requiredMode = REQUIRED) List<StepView> steps) {
        static NodePlanView of(Plan.NodePlan n) {
            return new NodePlanView(
                    n.nodeId(),
                    n.nodeName(),
                    n.live(),
                    n.unavailableReason(),
                    n.steps().stream().map(StepView::of).toList());
        }
    }

    @Schema(name = "ConfigStepView", description = "One management write; before and after as the broker reports them")
    public record StepView(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"ADD", "REPLACE", "REMOVE"})
            String op,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"ADDRESS", "QUEUE", "ADDRESS_SETTING", "SECURITY_SETTING", "DIVERT"})
            String section,

            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) Map<String, Object> before,
            @Schema(requiredMode = REQUIRED) Map<String, Object> after,

            @Schema(requiredMode = REQUIRED, description = "True when the node already matches and nothing is written")
            boolean already,

            @Schema(requiredMode = REQUIRED) String description) {
        static StepView of(Plan.Step s) {
            return new StepView(
                    s.id(),
                    s.op().name(),
                    s.section().name(),
                    s.key(),
                    s.before(),
                    s.after(),
                    s.already(),
                    s.description());
        }
    }

    @Schema(
            name = "ConfigHazardView",
            description = "A consequence stated before any write; High ones must be acknowledged by id")
    public record HazardView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String kind,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"LOW", "MEDIUM", "HIGH"})
            String hazardClass,

            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) String section,
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String message) {
        static HazardView of(Plan.Hazard h) {
            return new HazardView(
                    h.id(),
                    h.kind().name(),
                    h.hazardClass().name(),
                    h.nodeId(),
                    h.nodeName(),
                    h.section().name(),
                    h.key(),
                    h.message());
        }
    }

    @Schema(name = "ConfigFindingView", description = "Something the plan noticed and will not act on")
    public record ConfigFindingView(
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String section,
            @Schema(nullable = true) String key,
            @Schema(requiredMode = REQUIRED) String detail) {
        static ConfigFindingView of(Plan.Finding f) {
            return new ConfigFindingView(
                    f.kind().name(),
                    f.nodeId(),
                    f.nodeName(),
                    f.section() == null ? null : f.section().name(),
                    f.key(),
                    f.detail());
        }
    }

    @Schema(
            name = "ConfigApplyOutcomeView",
            description = "The outcome of an apply, dry or real; the same shape for preview and result")
    public record ApplyOutcomeView(
            @Schema(nullable = true) Long applyId,
            @Schema(requiredMode = REQUIRED) boolean dryRun,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"DRY_RUN", "APPLIED", "HALTED", "FAILED"})
            String outcome,

            @Schema(requiredMode = REQUIRED) int revision,
            @Schema(requiredMode = REQUIRED) PlanView plan,
            @Schema(requiredMode = REQUIRED) List<NodeApplyView> nodes,
            @Schema(requiredMode = REQUIRED) int stepCap,
            @Schema(requiredMode = REQUIRED) boolean overCap,
            @Schema(requiredMode = REQUIRED) String summary,
            @Schema(nullable = true) Long auditEventId) {
        public static ApplyOutcomeView of(BrokerConfigApplyOutcome o) {
            return new ApplyOutcomeView(
                    o.applyId(),
                    o.dryRun(),
                    o.outcome().name(),
                    o.revision(),
                    PlanView.of(o.plan()),
                    o.nodes().stream().map(NodeApplyView::of).toList(),
                    o.stepCap(),
                    o.overCap(),
                    o.summary(),
                    o.auditEventId());
        }
    }

    @Schema(name = "ConfigNodeApplyView", description = "One node's steps and what happened to each")
    public record NodeApplyView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean live,
            @Schema(requiredMode = REQUIRED) boolean canary,
            @Schema(nullable = true) String unavailableReason,
            @Schema(requiredMode = REQUIRED) List<StepApplyView> steps,
            @Schema(nullable = true) String note) {
        public static NodeApplyView of(BrokerConfigApplyOutcome.NodeApply n) {
            return new NodeApplyView(
                    n.nodeId(),
                    n.nodeName(),
                    n.live(),
                    n.canary(),
                    n.unavailableReason(),
                    n.steps().stream().map(StepApplyView::of).toList(),
                    n.note());
        }
    }

    @Schema(name = "ConfigStepApplyView", description = "One step's outcome on one node, in words")
    public record StepApplyView(
            @Schema(requiredMode = REQUIRED) String stepId,
            @Schema(requiredMode = REQUIRED) String section,
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String op,
            @Schema(requiredMode = REQUIRED) String description,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {
                        "WOULD_APPLY",
                        "APPLIED",
                        "ALREADY",
                        "FAILED",
                        "NOT_ATTEMPTED",
                        "SKIPPED_NOT_LIVE"
                    })
            String status,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"NOT_VERIFIED", "VERIFIED", "UNVERIFIABLE", "MISMATCH"})
            String verified,

            @Schema(nullable = true) String error) {
        public static StepApplyView of(BrokerConfigApplyOutcome.StepApply s) {
            return new StepApplyView(
                    s.stepId(),
                    s.section().name(),
                    s.key(),
                    s.op().name(),
                    s.description(),
                    s.status().name(),
                    s.verified().name(),
                    s.error());
        }
    }

    @Schema(name = "ConfigApplyHistoryView", description = "One past apply")
    public record ApplyHistoryView(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) Instant startedAt,
            @Schema(nullable = true) Instant finishedAt,
            @Schema(requiredMode = REQUIRED) long revisionId,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"DRY_RUN", "APPLIED", "HALTED", "FAILED"})
            String outcome,

            @Schema(nullable = true) String summary,
            @Schema(requiredMode = REQUIRED) String actor,
            @Schema(nullable = true) UUID canaryNodeId,
            @Schema(requiredMode = REQUIRED) boolean dryRun,
            @Schema(nullable = true) Long auditEventId) {
        public static ApplyHistoryView of(BrokerConfigApplyEntity a) {
            return new ApplyHistoryView(
                    a.getId(),
                    a.getStartedAt(),
                    a.getFinishedAt(),
                    a.getRevisionId(),
                    a.getOutcome(),
                    a.getSummary(),
                    a.getActor(),
                    a.getCanaryNodeId(),
                    a.isDryRun(),
                    a.getAuditEventId());
        }
    }

    @Schema(
            name = "ConfigApplyDetailView",
            description = "One past apply with the plan that was shown and the per-node outcome")
    public record ApplyDetailView(
            @Schema(requiredMode = REQUIRED) ApplyHistoryView apply,
            @Schema(requiredMode = REQUIRED) PlanView plan,
            @Schema(requiredMode = REQUIRED) List<NodeApplyView> nodes) {}

    // ---- the catalogue -----------------------------------------------------

    @Schema(
            name = "ConfigCatalogueView",
            description = "What the form needs to know about every address-setting key and permission type")
    public record CatalogueView(
            @Schema(requiredMode = REQUIRED) List<AddressSettingKeyView> addressSettingKeys,
            @Schema(requiredMode = REQUIRED) List<String> permissionTypes) {
        public static CatalogueView current() {
            List<AddressSettingKeyView> keys = Arrays.stream(AddressSettingKey.values())
                    .map(k -> new AddressSettingKeyView(
                            k.jsonName(),
                            k.xmlName(),
                            k.type().name(),
                            k.allowedValues(),
                            k.hazardClass().name(),
                            k.applicable()))
                    .toList();
            List<String> types = Arrays.stream(PermissionType.values())
                    .map(PermissionType::xmlName)
                    .toList();
            return new CatalogueView(keys, types);
        }
    }

    @Schema(
            name = "ConfigAddressSettingKeyView",
            description = "One address-setting key: its two names, type, allowed values and hazard class")
    public record AddressSettingKeyView(
            @Schema(requiredMode = REQUIRED) String jsonName,
            @Schema(requiredMode = REQUIRED) String xmlName,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"BOOLEAN", "INT", "LONG", "DOUBLE", "STRING", "ENUM"})
            String type,

            @Schema(requiredMode = REQUIRED) List<String> allowedValues,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"LOW", "MEDIUM", "HIGH"})
            String hazardClass,

            @Schema(requiredMode = REQUIRED, description = "False for keys a runtime write may not carry")
            boolean applicable) {}

    // ---- recommendations (ADR-0068) ---------------------------------------

    @Schema(
            name = "ConfigRecommendationsView",
            description = "What the capability probe suggests declaring, and what still needs a broker.xml edit")
    public record RecommendationsView(
            @Schema(
                    nullable = true,
                    description = "The node the current values were read from; null when none could be read")
            String seededFrom,

            @Schema(requiredMode = REQUIRED) List<RecommendationView> recommendations) {

        public static RecommendationsView of(BrokerConfigRecommendations.Recommendations r) {
            return new RecommendationsView(
                    r.seededFrom(),
                    r.recommendations().stream().map(RecommendationView::of).toList());
        }
    }

    @Schema(name = "ConfigRecommendationView", description = "One capability gap and what would close it")
    public record RecommendationView(
            @Schema(requiredMode = REQUIRED) String capability,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) String rationale,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether Rahdar can write this over the management API, or the"
                            + " operator must edit broker.xml and restart")
            boolean appliable,

            @Schema(
                    nullable = true,
                    allowableValues = {"ADDRESS_SETTING", "SECURITY_SETTING"})
            String section,

            @Schema(nullable = true) String match,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The whole entry that would be written, the node's current keys"
                            + " included — a runtime write replaces the entry rather than merging")
            Map<String, Object> values,

            @Schema(requiredMode = REQUIRED, description = "Permission type to role names, prefilled from the broker")
            Map<String, List<String>> roles,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "For a security setting: the roles of the account Rahdar connects to Core with,"
                            + " as the broker's user management reports them; empty when it could not")
            List<String> accountRoles,

            @Schema(
                    nullable = true,
                    description = "For a security setting: where the prefilled roles came from. Null for any other"
                            + " recommendation")
            RolesSourceView accountRolesSource,

            @Schema(requiredMode = REQUIRED, description = "The keys this recommendation itself sets")
            List<String> keys,

            @Schema(nullable = true) String manualSnippet) {

        public static RecommendationView of(BrokerConfigRecommendations.Recommendation r) {
            Map<String, List<String>> roles = new LinkedHashMap<>();
            r.roles()
                    .forEach((type, names) ->
                            roles.put(type.xmlName(), names.stream().sorted().toList()));
            return new RecommendationView(
                    r.capability(),
                    r.title(),
                    r.rationale(),
                    r.appliable(),
                    r.section() == null ? null : r.section().name(),
                    r.match(),
                    r.values(),
                    roles,
                    r.accountRoles(),
                    r.accountRolesSource() == null ? null : RolesSourceView.of(r.accountRolesSource()),
                    r.keys(),
                    r.manualSnippet());
        }
    }

    @Schema(name = "ConfigRolesSource", description = "Where a recommended security setting's roles were read from")
    public record RolesSourceView(
            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"BROKER_ACCOUNT", "SECURITY_SETTINGS", "NONE"})
            String kind,

            @Schema(nullable = true, description = "Why the account's roles were not used; null when they were")
            String reason) {

        public static RolesSourceView of(BrokerConfigRecommendations.RolesSource source) {
            return new RolesSourceView(source.kind().name(), source.reason());
        }
    }
}
