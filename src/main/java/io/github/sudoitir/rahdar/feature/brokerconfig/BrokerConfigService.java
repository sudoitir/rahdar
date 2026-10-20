package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigRevisionEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigRevisionRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterSecrets;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.platform.clusters.RegistrationAdoption;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.BrokerConnectors;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A cluster's declared configuration: read, save as a new revision, configure how
 * it is applied, import from and export to {@code broker.xml}, and adopt from what
 * the cluster is running (ADR-0067 D1, D2, D12). Nothing here contacts a broker to
 * write; adoption reads.
 */
@Service
@RequiredArgsConstructor
public class BrokerConfigService {

    private static final String CLUSTER = "cluster";
    static final String AUDIT_EDIT = "EDIT_BROKER_CONFIG";
    static final String AUDIT_CONFIGURE = "CONFIGURE_BROKER_CONFIG";
    static final String AUDIT_CREDENTIAL = "SET_BRIDGE_CREDENTIAL";

    public enum Source {
        EDIT,
        IMPORT_XML,
        ADOPT,
        MCP,
        /** Derived from the capability probe's recommendations and saved on the operator's word (ADR-0068). */
        RECOMMENDED
    }

    private final BrokerConfigDeclarationRepository declarations;
    private final BrokerConfigRevisionRepository revisions;
    private final BrokerConfigNodeStateRepository nodeStates;
    private final ClusterDirectory clusters;
    private final BrokerConnectors brokerConnectors;
    private final ClusterSecrets secrets;
    private final QueueSnapshots queueSnapshots;
    private final BrokerConfigReads reads;
    private final BrokerConfigOperations ops;
    private final ClusterLock lock;
    private final ClusterAccessGuard clusterAccess;
    private final SettingsService settings;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ObjectMapper mapper;
    private final ArtemisNodes artemisNodes;

    /** What the API returns for a cluster's declaration. */
    public record Declaration(
            UUID clusterId,
            String clusterName,
            boolean declared,
            int revision,
            long revisionId,
            BrokerConfigDocument document,
            ApplyMode applyMode,
            boolean reportUndeclared,
            List<String> undeclaredExclusions,
            Instant updatedAt,
            String updatedBy,
            Source source,
            String note,
            List<NodeState> nodes,
            /**
             * How often the scheduled pass evaluates this cluster, in seconds. On the
             * declaration because the screen showing "last evaluated" is the one that has
             * to say when the next one is due — a relative age with no cadence beside it
             * cannot be read as either fresh or stalled.
             */
            long driftIntervalSeconds) {}

    /** The latest evaluation of one node, as stored. */
    public record NodeState(
            UUID nodeId,
            String nodeName,
            boolean live,
            BrokerConfigNodeStateEntity.State state,
            String detail,
            Integer verifiedRevision,
            Instant evaluatedAt,
            List<BrokerConfigDriftService.DriftFinding> findings,
            BrokerConfigNodeStateEntity.Basis basis,
            Long basisRef) {}

    public record Revision(
            int revision,
            long id,
            Instant createdAt,
            String createdBy,
            Source source,
            String note,
            BrokerConfigDocument document) {}

    /**
     * What adoption produced, for review before it is saved.
     *
     * <p>{@code closes} is the part an operator must see before confirming: adoption
     * declares what the cluster is already running, so every open drift finding
     * disappears on the next evaluation — not because a broker was written, but
     * because the declaration moved to meet it. A preview that shows only the
     * document reads exactly like a successful apply.
     */
    public record Adoption(
            BrokerConfigDocument document,
            List<String> notes,
            List<String> disagreements,
            List<ClosedFinding> closes) {}

    /** One drift finding an adoption would erase, and the node that reported it. */
    public record ClosedFinding(UUID nodeId, String nodeName, BrokerConfigDriftService.DriftFinding finding) {}

    // ---- read ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Declaration get(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        RegisteredCluster cluster =
                clusters.cluster(clusterId).orElseThrow(() -> new NotFoundException(CLUSTER, clusterId));
        Optional<BrokerConfigDeclarationEntity> header = declarations.findById(clusterId);
        List<NodeState> nodes = nodeStates(clusterId);
        if (header.isEmpty()) {
            return new Declaration(
                    clusterId,
                    cluster.getName(),
                    false,
                    0,
                    0,
                    BrokerConfigDocument.empty(),
                    ApplyMode.RAHDAR_MANAGED,
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    nodes,
                    driftIntervalSeconds());
        }
        BrokerConfigDeclarationEntity h = header.get();
        BrokerConfigRevisionEntity current = revisions
                .findById(h.getCurrentRevisionId())
                .orElseThrow(() -> new IllegalStateException("Declaration points at a missing revision"));
        return new Declaration(
                clusterId,
                cluster.getName(),
                true,
                current.getRevision(),
                current.getId(),
                parse(current.getDocument()),
                h.mode(),
                h.isReportUndeclared(),
                exclusions(h),
                current.getCreatedAt(),
                current.getCreatedBy(),
                Source.valueOf(current.getSource()),
                current.getNote(),
                nodes,
                driftIntervalSeconds());
    }

    private long driftIntervalSeconds() {
        return Math.max(
                1, settings.duration(BrokerConfigSettings.DRIFT_INTERVAL).toSeconds());
    }

    @Transactional(readOnly = true)
    public List<Revision> revisions(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return revisions.findByClusterIdOrderByRevisionDesc(clusterId).stream()
                .map(this::revision)
                .toList();
    }

    @Transactional(readOnly = true)
    public Revision revision(UUID clusterId, int number) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return revisions
                .findByClusterIdAndRevision(clusterId, number)
                .map(this::revision)
                .orElseThrow(() -> new NotFoundException("revision", number));
    }

    /** The current document, or the empty one for an undeclared cluster. Internal callers only. */
    @Transactional(readOnly = true)
    public Optional<CurrentRevision> current(UUID clusterId) {
        return declarations
                .findById(clusterId)
                .flatMap(h -> revisions
                        .findById(h.getCurrentRevisionId())
                        .map(r -> new CurrentRevision(
                                r.getId(), r.getRevision(), parse(r.getDocument()), h, Source.valueOf(r.getSource()))));
    }

    public record CurrentRevision(
            long id,
            int revision,
            BrokerConfigDocument document,
            BrokerConfigDeclarationEntity header,
            /** How this revision came to exist; drift reads it to explain an IN_SYNC node. */
            Source source) {}

    // ---- write -----------------------------------------------------------

    /**
     * Save a new revision. {@code expectedRevision} is the revision the operator
     * edited; when it is no longer current the save is refused (D12), because
     * silently overwriting another operator's revision is how a cluster ends up
     * declared as something nobody intended.
     */
    @Transactional
    public Declaration save(
            UUID clusterId, BrokerConfigDocument document, Integer expectedRevision, String note, Source source) {
        return save(clusterId, document, expectedRevision, note, source, null);
    }

    /**
     * As above, with the typed confirmation an adoption needs.
     *
     * <p>An adoption that would close open drift findings is refused without the
     * cluster's name, because it is indistinguishable in its effect from an apply
     * and opposite in its meaning: the broker keeps whatever it is doing. It is also
     * refused while an apply holds the cluster, so that a run in flight cannot have
     * the declaration it is applying moved underneath it.
     */
    @Transactional
    public Declaration save(
            UUID clusterId,
            BrokerConfigDocument document,
            Integer expectedRevision,
            String note,
            Source source,
            String confirm) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        List<Violation> violations = BrokerConfigValidator.validate(document);
        if (!violations.isEmpty()) {
            throw new BrokerConfigInvalidException(violations);
        }
        if (source == Source.ADOPT) {
            List<ClosedFinding> closes = openFindings(clusterId);
            if (!closes.isEmpty() && !clusterName(clusterId).equals(confirm)) {
                throw new ConflictException(
                        "adoption-unconfirmed",
                        "Adopting this document closes " + closes.size() + " open drift finding(s) without writing"
                                + " to any broker. Confirm with the cluster's name to record that as intended.");
            }
            if (lock.isHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY)) {
                throw new ConflictException(
                        "apply-in-progress",
                        "A configuration apply is running on this cluster. Adopting now would move the"
                                + " declaration it is applying. Wait for it to finish.");
            }
        }
        Optional<BrokerConfigDeclarationEntity> header = declarations.findById(clusterId);
        int currentNumber = header.map(h -> revisions
                        .findById(h.getCurrentRevisionId())
                        .map(BrokerConfigRevisionEntity::getRevision)
                        .orElse(0))
                .orElse(0);
        if (expectedRevision != null && expectedRevision != currentNumber) {
            throw new ConflictException(
                    "stale-revision",
                    "Revision " + currentNumber + " was saved while you were editing revision " + expectedRevision
                            + ". Reload it and re-apply your edit.");
        }
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                AUDIT_EDIT,
                CLUSTER,
                clusterName(clusterId),
                clusterId,
                null,
                Map.of("revision", currentNumber + 1, "source", source.name()),
                false);
        BrokerConfigRevisionEntity saved = revisions.save(new BrokerConfigRevisionEntity(
                clusterId,
                currentNumber + 1,
                mapper.writeValueAsString(document),
                source.name(),
                note == null || note.isBlank() ? null : note.trim(),
                actorResolver.resolve().displayName()));
        if (header.isPresent()) {
            header.get().pointAt(saved.getId());
            declarations.save(header.get());
        } else {
            declarations.save(new BrokerConfigDeclarationEntity(clusterId, saved.getId()));
        }
        audit.succeed(event, 1);
        return get(clusterId);
    }

    /** Change how the cluster's configuration is applied and what drift reports. */
    @Transactional
    public Declaration configure(
            UUID clusterId, ApplyMode mode, boolean reportUndeclared, List<String> undeclaredExclusions) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        BrokerConfigDeclarationEntity header = declarations
                .findById(clusterId)
                .orElseThrow(() -> new ConflictException(
                        "no-declaration",
                        "Declare the cluster's configuration first; the mode belongs to a declaration."));
        List<String> exclusions = undeclaredExclusions == null
                ? List.of()
                : undeclaredExclusions.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                AUDIT_CONFIGURE,
                CLUSTER,
                clusterName(clusterId),
                clusterId,
                null,
                Map.of("applyMode", mode.name(), "reportUndeclared", reportUndeclared, "exclusions", exclusions),
                false);
        header.configure(mode, reportUndeclared, mapper.writeValueAsString(exclusions));
        declarations.save(header);
        audit.succeed(event, 1);
        return get(clusterId);
    }

    // ---- bridge credentials and connector names ---------------------------

    /**
     * One node's connector names, for the bridge editor to offer (ADR-0091). A node
     * that cannot be read answers "unknown", never an empty list — an absent name list
     * reads as "this broker has no connectors", which is a different claim.
     */
    public record NodeConnectors(UUID nodeId, String nodeName, List<String> names, boolean known, String reason) {}

    /** The connector names of every serving node. One extra batched read per node, on this screen only. */
    public List<NodeConnectors> connectors(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<NodeConnectors> out = new ArrayList<>();
        for (ClusterNode node : reads.targets(clusterId)) {
            if (!Boolean.TRUE.equals(node.getActive())) {
                out.add(new NodeConnectors(
                        node.getId(),
                        node.getName(),
                        List.of(),
                        false,
                        "Not live. A backup reports its connectors once it becomes active."));
                continue;
            }
            BrokerConnectors.Connectors c;
            try {
                c = brokerConnectors.read(reads.client(clusterId, node));
            } catch (RuntimeException e) {
                c = BrokerConnectors.Connectors.unknown("Could not reach this node: " + e.getMessage());
            }
            out.add(new NodeConnectors(node.getId(), node.getName(), c.names(), c.known(), c.unknownReason()));
        }
        return out;
    }

    /**
     * The credential references this cluster holds, with their usernames. A password
     * is never returned by this or any other read (ADR-0092). A read, gated like every
     * other read here — {@code CONFIG_WRITE} is for the mutations below.
     */
    @Transactional(readOnly = true)
    public List<ClusterSecrets.Credential> bridgeCredentials(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return secrets.list(clusterId);
    }

    /** Store or replace a bridge credential. The audit row names the reference and the user, never the secret. */
    @Transactional
    public void setBridgeCredential(UUID clusterId, String ref, String username, String password) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                AUDIT_CREDENTIAL,
                CLUSTER,
                clusterName(clusterId),
                clusterId,
                null,
                Map.of("credentialRef", ref, "username", username == null ? "" : username),
                false);
        secrets.store(clusterId, ref, username, password);
        audit.succeed(event, 1);
    }

    /** Forget a bridge credential. A bridge that still references it will refuse to apply, by name. */
    @Transactional
    public void forgetBridgeCredential(UUID clusterId, String ref) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                AUDIT_CREDENTIAL,
                CLUSTER,
                clusterName(clusterId),
                clusterId,
                null,
                Map.of("credentialRef", ref, "forgotten", true),
                false);
        secrets.forget(clusterId, ref);
        audit.succeed(event, 1);
    }

    // ---- XML -------------------------------------------------------------

    /** Parse pasted XML for preview. Pure: nothing is saved. */
    public BrokerXmlCodec.ParseResult importXml(UUID clusterId, String xml) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        return BrokerXmlCodec.parse(xml);
    }

    @Transactional(readOnly = true)
    public String exportXml(UUID clusterId, Integer revisionNumber) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        BrokerConfigDocument doc = revisionNumber == null
                ? get(clusterId).document()
                : revision(clusterId, revisionNumber).document();
        // A backstop to ADR-0092: the document holds a credential reference, never a value.
        return SecretRedactor.redact(BrokerXmlCodec.write(doc));
    }

    // ---- adopt -----------------------------------------------------------

    /**
     * Build a declaration from what the live nodes are running, for review. The
     * catch-all gets every key the broker reports; each address gets the keys it
     * resolves differently from the catch-all, keyed by the address, because the
     * broker cannot report the match pattern that produced them (§15 M5). Where
     * nodes disagree, the disagreement is listed and the first live node's value is
     * kept.
     */
    public Adoption adopt(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        List<String> notes = adoptionNotes();
        List<ObservedNodeConfig> observed = readLiveNodes(clusterId, notes);
        if (observed.isEmpty()) {
            throw new ConflictException("no-live-node", "No live node could be read, so there is nothing to adopt.");
        }
        return build(observed, notes, clusterId);
    }

    /**
     * What adopting would declare from nodes read before the cluster is registered (ADR-0176): the same
     * document as {@link #adopt}, without the queues a scrape has not yet cached and without drift findings,
     * because there are none. A node that cannot be read is noted and contributes nothing; empty when none
     * could be read.
     */
    public Optional<Adoption> previewAdoption(List<RegistrationAdoption.LiveNode> nodes) {
        List<String> notes = adoptionNotes();
        List<ObservedNodeConfig> observed = new ArrayList<>();
        for (RegistrationAdoption.LiveNode node : nodes) {
            try {
                observed.add(ops.readForAdoption(artemisNodes.on(node.target()), new UUID(0, 0), node.name()));
            } catch (RuntimeException e) {
                notes.add(node.name() + " could not be read and contributed nothing: " + e.getMessage());
            }
        }
        return observed.isEmpty() ? Optional.empty() : Optional.of(build(observed, notes, null));
    }

    private static List<String> adoptionNotes() {
        List<String> notes = new ArrayList<>();
        notes.add("A broker reports the settings an address resolves to, not the match patterns its"
                + " configuration declares. Address settings and security settings are therefore keyed by"
                + " '#' and by address; merge them into the patterns you know before applying.");
        return notes;
    }

    /** The declaration the observed nodes add up to; {@code clusterId} is null before the cluster exists. */
    private Adoption build(List<ObservedNodeConfig> observed, List<String> notes, UUID clusterId) {
        List<String> disagreements = new ArrayList<>();
        ObservedNodeConfig first = observed.getFirst();

        // Addresses and queues: the union across nodes; queues from the snapshot cache.
        Map<String, Set<String>> addresses = new TreeMap<>();
        observed.forEach(n -> n.addresses()
                .forEach((a, types) -> addresses.merge(a, new TreeSet<>(types), (x, y) -> {
                    x.addAll(y);
                    return x;
                })));
        Map<String, List<QueueDecl>> queuesByAddress = new TreeMap<>();
        if (clusterId != null) {
            addSnapshotQueues(clusterId, addresses, queuesByAddress);
        }
        List<AddressDecl> addressDecls = new ArrayList<>();
        addresses.forEach((name, types) -> {
            if (types.isEmpty()) {
                types.add("ANYCAST");
            }
            addressDecls.add(new AddressDecl(name, types, queuesByAddress.getOrDefault(name, List.of())));
        });

        List<AddressSettingDecl> settingDecls = adoptedAddressSettings(observed, addresses.keySet(), disagreements);
        List<SecuritySettingDecl> securityDecls = adoptedSecuritySettings(first, addresses.keySet());
        Map<String, DivertDecl> diverts = adoptedDiverts(observed, disagreements);

        BrokerConfigDocument doc = new BrokerConfigDocument(
                BrokerConfigDocument.CURRENT_VERSION,
                addressDecls,
                settingDecls,
                securityDecls,
                new ArrayList<>(diverts.values()),
                // Adoption does not adopt bridges: what a node runs is only the thirteen
                // fields BridgeControl reports, and declaring the other ten as unset would
                // silently drop them on the next apply (ADR-0090 D4a).
                List.of());
        List<ClosedFinding> closes = clusterId == null ? List.of() : openFindings(clusterId);
        if (!closes.isEmpty()) {
            notes.add(closes.size() + " open drift finding(s) will be closed by adopting this document, and no"
                    + " broker will be written: the declaration moves to match the cluster. Apply the current"
                    + " declaration instead if the cluster is what is wrong.");
        }
        return new Adoption(doc, notes, disagreements, closes);
    }

    /** What each live node runs; one that cannot be read is noted and contributes nothing. */
    private List<ObservedNodeConfig> readLiveNodes(UUID clusterId, List<String> notes) {
        List<ObservedNodeConfig> observed = new ArrayList<>();
        reads.targets(clusterId).stream()
                .filter(node -> Boolean.TRUE.equals(node.getActive()))
                .forEach(node -> {
                    try {
                        observed.add(ops.readForAdoption(reads.client(clusterId, node), node.getId(), node.getName()));
                    } catch (RuntimeException e) {
                        notes.add(node.getName() + " could not be read and contributed nothing: " + e.getMessage());
                    }
                });
        return observed;
    }

    /** The cluster's queues from the snapshot cache, added to their addresses (created when the nodes did not report them). */
    private void addSnapshotQueues(
            UUID clusterId, Map<String, Set<String>> addresses, Map<String, List<QueueDecl>> queuesByAddress) {
        Map<String, QueueSnapshot> seen = new LinkedHashMap<>();
        queueSnapshots.forCluster(clusterId).stream()
                .filter(q -> q.address() != null
                        && !q.address().startsWith("activemq.")
                        && !q.address().startsWith("$sys."))
                .forEach(q -> seen.putIfAbsent(q.queueName(), q));
        for (QueueSnapshot q : seen.values()) {
            addresses
                    .computeIfAbsent(q.address(), k -> new TreeSet<>())
                    .add(q.routingType().toUpperCase());
            queuesByAddress
                    .computeIfAbsent(q.address(), k -> new ArrayList<>())
                    .add(new QueueDecl(
                            q.queueName(),
                            q.routingType().toUpperCase(),
                            null,
                            q.durable(),
                            null,
                            null,
                            null,
                            null,
                            null));
        }
    }

    /** Address settings: '#' in full, then per-address differences. */
    private static List<AddressSettingDecl> adoptedAddressSettings(
            List<ObservedNodeConfig> observed, Set<String> addressNames, List<String> disagreements) {
        ObservedNodeConfig first = observed.getFirst();
        List<AddressSettingDecl> out = new ArrayList<>();
        Map<String, Object> base = first.addressSettings().getOrDefault("#", Map.of());
        out.add(new AddressSettingDecl("#", base));
        for (String address : addressNames) {
            Map<String, Object> resolved = first.addressSettings().get(address);
            if (resolved == null) {
                continue;
            }
            Map<String, Object> diff = new TreeMap<>();
            resolved.forEach((k, v) -> {
                if (!sameValue(v, base.get(k))) {
                    diff.put(k, v);
                }
            });
            if (!diff.isEmpty()) {
                out.add(new AddressSettingDecl(address, diff));
            }
            observed.subList(1, observed.size()).stream()
                    .filter(other -> {
                        Map<String, Object> theirs = other.addressSettings().get(address);
                        return theirs != null && !theirs.equals(resolved);
                    })
                    .forEach(other -> disagreements.add("Address settings for " + address + " differ between "
                            + first.nodeName() + " and " + other.nodeName() + "; " + first.nodeName()
                            + "'s were kept."));
        }
        return out;
    }

    /** Security settings: '#' and per-address role sets that differ from it. */
    private static List<SecuritySettingDecl> adoptedSecuritySettings(
            ObservedNodeConfig first, Set<String> addressNames) {
        List<SecuritySettingDecl> out = new ArrayList<>();
        Map<PermissionType, Set<String>> baseRoles = first.securitySettings().getOrDefault("#", Map.of());
        if (!baseRoles.isEmpty()) {
            out.add(new SecuritySettingDecl("#", baseRoles));
        }
        for (String address : addressNames) {
            Map<PermissionType, Set<String>> roles = first.securitySettings().get(address);
            if (roles != null
                    && !roles.isEmpty()
                    && !sameRoles(roles, baseRoles)
                    && !AddressMatch.isCatchAll(address)) {
                out.add(new SecuritySettingDecl(address, new EnumMap<>(roles)));
            }
        }
        return out;
    }

    /** Diverts: union by name, first node's properties kept. */
    private static Map<String, DivertDecl> adoptedDiverts(
            List<ObservedNodeConfig> observed, List<String> disagreements) {
        ObservedNodeConfig first = observed.getFirst();
        Map<String, DivertDecl> diverts = new TreeMap<>();
        for (ObservedNodeConfig n : observed) {
            n.diverts().forEach((name, d) -> {
                DivertDecl kept = diverts.putIfAbsent(name, d);
                if (kept != null && !kept.sameAs(d)) {
                    disagreements.add("Divert " + name + " differs between " + first.nodeName() + " and " + n.nodeName()
                            + "; " + first.nodeName() + "'s was kept.");
                }
            });
        }
        for (ObservedNodeConfig n : observed.subList(1, observed.size())) {
            diverts.keySet().stream()
                    .filter(name -> !n.diverts().containsKey(name))
                    .forEach(name -> disagreements.add("Divert " + name + " is missing on " + n.nodeName() + "."));
        }
        return diverts;
    }

    /** Every drift finding currently recorded against the cluster, node by node. */
    private List<ClosedFinding> openFindings(UUID clusterId) {
        List<ClosedFinding> out = new ArrayList<>();
        for (NodeState node : nodeStates(clusterId)) {
            if (node.state() != BrokerConfigNodeStateEntity.State.DRIFTED) {
                continue;
            }
            node.findings().forEach(f -> out.add(new ClosedFinding(node.nodeId(), node.nodeName(), f)));
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------

    BrokerConfigDocument parse(String json) {
        return mapper.readValue(json, BrokerConfigDocument.class);
    }

    private Revision revision(BrokerConfigRevisionEntity r) {
        return new Revision(
                r.getRevision(),
                r.getId(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                Source.valueOf(r.getSource()),
                r.getNote(),
                parse(r.getDocument()));
    }

    List<String> exclusions(BrokerConfigDeclarationEntity h) {
        return mapper.readValue(h.getUndeclaredExclusions(), new TypeReference<List<String>>() {});
    }

    private List<NodeState> nodeStates(UUID clusterId) {
        Map<UUID, BrokerConfigNodeStateEntity> stored = new LinkedHashMap<>();
        nodeStates.findByClusterId(clusterId).forEach(s -> stored.put(s.getNodeId(), s));
        List<NodeState> out = new ArrayList<>();
        for (ClusterNode node : reads.targets(clusterId)) {
            BrokerConfigNodeStateEntity s = stored.get(node.getId());
            boolean live = Boolean.TRUE.equals(node.getActive());
            if (s == null) {
                out.add(new NodeState(
                        node.getId(),
                        node.getName(),
                        live,
                        BrokerConfigNodeStateEntity.State.NOT_EVALUATED,
                        "Not evaluated yet.",
                        null,
                        null,
                        List.of(),
                        null,
                        null));
            } else {
                out.add(new NodeState(
                        node.getId(),
                        node.getName(),
                        live,
                        s.state(),
                        s.getDetail(),
                        s.getVerifiedRevision(),
                        s.getEvaluatedAt(),
                        mapper.readValue(
                                s.getFindings(), new TypeReference<List<BrokerConfigDriftService.DriftFinding>>() {}),
                        s.basis(),
                        s.getBasisRef()));
            }
        }
        return out;
    }

    String clusterName(UUID clusterId) {
        return clusters.cluster(clusterId).map(RegisteredCluster::getName).orElse(clusterId.toString());
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return a.toString().equalsIgnoreCase(b.toString());
    }

    private static boolean sameRoles(Map<PermissionType, Set<String>> a, Map<PermissionType, Set<String>> b) {
        for (PermissionType t : PermissionType.values()) {
            if (!a.getOrDefault(t, Set.of()).equals(b.getOrDefault(t, Set.of()))) {
                return false;
            }
        }
        return true;
    }
}
