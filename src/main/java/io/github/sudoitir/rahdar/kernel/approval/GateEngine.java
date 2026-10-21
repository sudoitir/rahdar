package io.github.sudoitir.rahdar.kernel.approval;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.Ids;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry.AttachedProvider;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.rahdar.kernel.gate.AuthKind;
import io.github.sudoitir.rahdar.kernel.gate.CanonicalJson;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GateContext;
import io.github.sudoitir.rahdar.kernel.gate.GateDecision;
import io.github.sudoitir.rahdar.kernel.gate.GatePreview;
import io.github.sudoitir.rahdar.kernel.gate.GateRequest;
import io.github.sudoitir.rahdar.kernel.gate.GateScope;
import io.github.sudoitir.rahdar.kernel.gate.GateTicket;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperationRegistry;
import io.github.sudoitir.rahdar.kernel.gate.HeldEvent;
import io.github.sudoitir.rahdar.kernel.gate.HeldState;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationDeniedException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.PolicyRef;
import io.github.sudoitir.rahdar.kernel.gate.Requester;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.plugin.PluginScopedBeans;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one {@link OperationGate} (ADR-0179, design decision 4). In order: an issued replay ticket runs its approved
 * request once; an issued covering ticket runs the items of an operation the gate let through; with no provider armed
 * the action runs as it always did; break-glass runs it and audits the bypass; a provider that is armed but not
 * attached here fails closed. Otherwise the request is built as the requester sees it and the provider, bounded,
 * allows it (it runs, covered, with the policy on its audit row), denies it (refused and audited) or holds it (stored
 * with its seal, its audit row, its first event and the approvers' inbox items in one transaction).
 */
@Component
@Slf4j
class GateEngine implements OperationGate, PluginScopedBeans {

    static final String PLUGIN_BEAN_NAME = "operationGate";

    static final String REDACTED = "[redacted]";
    static final int MAX_REASON = 500;
    static final String NO_APPROVER =
            "No other user may approve this, so it cannot be held. Grant the approver permission to"
                    + " a second person, or have whoever runs this deployment set the break-glass switch to recover"
                    + " (see the guide on approvals).";

    private static final String PARAMS_HASH = "paramsHash";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GatedOperationRegistry operations;
    private final ApprovalProviderRegistry providers;
    private final IssuedTickets tickets;
    private final ProviderCalls calls;
    private final HeldStore store;
    private final Seals seals;
    private final ApproverRules rules;
    private final ApprovalNotices notices;
    private final GateBounds bounds;
    private final AuditService audit;
    private final ActorResolver actors;
    private final BreakGlass breakGlass;
    private final Executions executions;
    private final TransactionTemplate newTransaction;

    GateEngine(
            GatedOperationRegistry operations,
            ApprovalProviderRegistry providers,
            IssuedTickets tickets,
            ProviderCalls calls,
            HeldStore store,
            Seals seals,
            ApproverRules rules,
            ApprovalNotices notices,
            GateBounds bounds,
            AuditService audit,
            ActorResolver actors,
            BreakGlass breakGlass,
            Executions executions,
            PlatformTransactionManager transactions) {
        this.operations = operations;
        this.providers = providers;
        this.tickets = tickets;
        this.calls = calls;
        this.store = store;
        this.seals = seals;
        this.rules = rules;
        this.notices = notices;
        this.bounds = bounds;
        this.audit = audit;
        this.actors = actors;
        this.breakGlass = breakGlass;
        this.executions = executions;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Each plugin gets the gate for its own operation types only (ADR-0111). */
    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(PLUGIN_BEAN_NAME, new ScopedOperationGate(this, operations, pluginId));
    }

    @Override
    public <R> R run(Operation operation, Supplier<R> action) {
        GatedOperation<Record> type = typeOf(operation);
        Record params = operation.params();
        if (GateScope.COVERED.isBound() && tickets.issued(GateScope.COVERED.get())) {
            GateTicket ticket = GateScope.COVERED.get();
            return ticket.replay() ? replay(ticket, type, params, action) : action.get();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The approval gate was called inside a transaction for '" + type.type()
                    + "'. Call OperationGate.run outside any transaction: a hold commits on its own.");
        }
        Optional<String> armed = providers.armedProviderId();
        if (armed.isEmpty()) {
            return action.get();
        }
        if (breakGlass.active()) {
            return bypass(type, params, action);
        }
        AttachedProvider provider = attached(armed.get());
        Requester requester = currentRequester()
                .orElseThrow(() -> deny(type, null, "Only a signed-in user may run this while approvals are on."));
        Built built = build(type, params, requester);
        if (type.mode() == ExecutionMode.BY_REQUESTER) {
            Optional<HeldRow> approved = store.openDuplicate(requester.userId(), built.hash())
                    .filter(row -> row.state() == HeldState.APPROVED && row.mode() == ExecutionMode.BY_REQUESTER);
            if (approved.isPresent()) {
                return executions.completeByRequester(approved.get(), requester, action);
            }
        }
        GateDecision decision =
                calls.call("decide", () -> provider.provider().decide(built.request(GateRequest.Mode.SUBMIT)));
        return switch (decision) {
            case GateDecision.Allow(var policy) -> covered(type, approval(provider.pluginId(), policy), action);
            case GateDecision.Deny(var reason) -> throw deny(type, built, reason);
            case GateDecision.Hold hold -> throw hold(built, provider, hold);
        };
    }

    @Override
    public GatePreview preview(Operation operation) {
        GatedOperation<Record> type = typeOf(operation);
        Optional<String> armed = providers.armedProviderId();
        if (armed.isEmpty() || breakGlass.active()) {
            return new GatePreview(GatePreview.Outcome.RUN, null, null, null);
        }
        AttachedProvider provider = attached(armed.get());
        Requester requester = currentRequester().orElse(null);
        if (requester == null) {
            return new GatePreview(
                    GatePreview.Outcome.DENY, null, null, "Only a signed-in user may run this while approvals are on.");
        }
        Built built = build(type, operation.params(), requester);
        GateDecision decision =
                calls.call("decide", () -> provider.provider().decide(built.request(GateRequest.Mode.PREVIEW)));
        return switch (decision) {
            case GateDecision.Allow(var policy) ->
                new GatePreview(GatePreview.Outcome.RUN, policy, built.effect(), null);
            case GateDecision.Deny(var reason) ->
                new GatePreview(GatePreview.Outcome.DENY, null, built.effect(), reason);
            case GateDecision.Hold hold ->
                rules.eligible(
                                        requester.userId(),
                                        built.scope().clusterId(),
                                        provider.approverPermission(),
                                        store.now())
                                .isEmpty()
                        ? new GatePreview(GatePreview.Outcome.DENY, hold.policy(), built.effect(), NO_APPROVER)
                        : new GatePreview(GatePreview.Outcome.HOLD, hold.policy(), built.effect(), null);
        };
    }

    // ---- steps ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private GatedOperation<Record> typeOf(Operation operation) {
        Class<? extends Record> paramsType = operation.params().getClass();
        return (GatedOperation<Record>) operations
                .forParams((Class<Record>) paramsType)
                .orElseThrow(() -> new IllegalStateException(
                        "No gated operation is registered for the parameters " + paramsType.getName()));
    }

    /** An issued replay ticket lets its approved request through once, and covers what it does. */
    private <R> R replay(GateTicket ticket, GatedOperation<Record> type, Record params, Supplier<R> action) {
        String hash =
                HexFormat.of().formatHex(CanonicalJson.hash(type.type(), type.version(), CanonicalJson.write(params)));
        if (!ticket.type().equals(type.type()) || !ticket.paramsHash().equals(hash)) {
            throw new ReplayMismatchException("The approved request's replay asked for a different operation ("
                    + type.type() + "), so nothing was run.");
        }
        if (!tickets.consume(ticket)) {
            throw new ReplayMismatchException("An approved request runs once; its replay reached the gate again.");
        }
        GateTicket cover = tickets.covering(type.type());
        try {
            return ScopedValue.where(GateScope.COVERED, cover).call(action::get);
        } finally {
            tickets.release(cover);
        }
    }

    private <R> R covered(GatedOperation<Record> type, Map<String, String> approval, Supplier<R> action) {
        GateTicket ticket = tickets.covering(type.type());
        try {
            return ScopedValue.where(GateScope.COVERED, ticket)
                    .where(AuditScope.APPROVAL, approval)
                    .call(action::get);
        } finally {
            tickets.release(ticket);
        }
    }

    private <R> R bypass(GatedOperation<Record> type, Record params, Supplier<R> action) {
        String hash =
                HexFormat.of().formatHex(CanonicalJson.hash(type.type(), type.version(), CanonicalJson.write(params)));
        UUID clusterId = null;
        String summary = type.type();
        try {
            clusterId = type.scope(params).clusterId();
            summary = type.summary(params);
        } catch (RuntimeException e) {
            log.warn("approval-gate break-glass could not describe {}: {}", type.type(), e.toString());
        }
        AuditEvent event = audit.begin(
                actors.resolve(),
                "GATE_BYPASSED",
                "GATED_OPERATION",
                summary,
                clusterId,
                null,
                Map.of("type", type.type(), PARAMS_HASH, hash, "breakGlass", breakGlass.reason()),
                false);
        audit.succeed(event, 1);
        log.warn("approval-gate bypassed type={} reason=\"break-glass\"", type.type());
        return covered(type, Map.of("bypass", "break-glass"), action);
    }

    private AttachedProvider attached(String providerId) {
        return providers
                .attached(providerId)
                .orElseThrow(() -> new ApprovalUnavailableException("The approval provider '" + providerId
                        + "' is installed but not running on this Rahdar instance, so nothing was run. Try again"
                        + " once it is running."));
    }

    /** The request as the provider sees it, built on the requester's thread: every estimate is theirs. */
    record Built(
            GatedOperation<Record> type,
            String canonical,
            String hashHex,
            String redacted,
            Set<Trait> traits,
            OperationScope scope,
            String summary,
            List<DisplayRow> display,
            Effect effect,
            Requester requester) {

        byte[] hash() {
            return HexFormat.of().parseHex(hashHex);
        }

        GateRequest request(GateRequest.Mode mode) {
            return new GateRequest(
                    mode,
                    type.type(),
                    type.version(),
                    traits,
                    type.mode(),
                    scope,
                    summary,
                    display,
                    redacted,
                    hashHex,
                    effect,
                    requester);
        }
    }

    private Built build(GatedOperation<Record> type, Record params, Requester requester) {
        String canonical = CanonicalJson.write(params);
        byte[] hash = CanonicalJson.hash(type.type(), type.version(), canonical);
        Effect effect;
        try {
            effect = type.estimate(params);
        } catch (ApprovalUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("approval-gate estimate type={} failed", type.type(), e);
            throw new ApprovalUnavailableException(
                    "Rahdar could not estimate what this would do, so it was not run: " + e.getMessage(), e);
        }
        if (effect == null) {
            throw new ApprovalUnavailableException("Rahdar could not estimate what this would do, so it was not run.");
        }
        OperationScope scope = type.scope(params);
        return new Built(
                type,
                canonical,
                HexFormat.of().formatHex(hash),
                redact(canonical, type.redactedPaths()),
                Set.copyOf(type.traits(params)),
                scope == null ? OperationScope.GLOBAL : scope,
                type.summary(params),
                List.copyOf(type.display(params)),
                effect,
                requester);
    }

    private OperationHeldException hold(Built built, AttachedProvider provider, GateDecision.Hold hold) {
        if (hold.ttl().compareTo(ApprovalSettings.MIN_HOLD) < 0) {
            log.warn(
                    "approval-gate provider={} asked to hold for {}, under Rahdar's minimum",
                    provider.pluginId(),
                    hold.ttl());
            throw new ApprovalUnavailableException("The approval provider asked to hold this for " + hold.ttl()
                    + ", under the 1 minute Rahdar allows; nothing was run.");
        }
        Duration maxHold = bounds.maxHold();
        Duration ttl = hold.ttl().compareTo(maxHold) > 0 ? maxHold : hold.ttl();
        if (!ttl.equals(hold.ttl())) {
            log.info(
                    "approval-gate provider={} asked to hold for {}, held for {}, Rahdar's maximum",
                    provider.pluginId(),
                    hold.ttl(),
                    ttl);
        }
        Requester requester = built.requester();
        Optional<HeldRow> existing = store.openDuplicate(requester.userId(), built.hash());
        if (existing.isPresent()) {
            return held(existing.get());
        }
        Instant now = store.now();
        List<UUID> approvers =
                rules.eligible(requester.userId(), built.scope().clusterId(), provider.approverPermission(), now);
        if (approvers.isEmpty()) {
            throw deny(built.type(), built, NO_APPROVER);
        }
        int open = store.openCount(requester.userId());
        if (open >= bounds.maxOpenPerRequester()) {
            throw new TooManyHeldOperationsException(open);
        }
        UUID id = Ids.v7();
        String type = built.type().type();
        byte[] sealed = seals.sealPayload(
                id,
                type,
                new Seals.Payload(
                        built.canonical(),
                        built.hashHex(),
                        requester.userId(),
                        requester.authKind(),
                        requester.tokenId(),
                        Seals.policyDigest(hold.policy()),
                        built.effect().etag()));
        Map<String, Object> auditParams = new LinkedHashMap<>();
        auditParams.put("heldOperation", id.toString());
        auditParams.put("type", type);
        auditParams.put("provider", provider.pluginId());
        auditParams.put("policy", policyLabel(hold.policy()));
        auditParams.put(PARAMS_HASH, built.hashHex());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "OPERATION_HELD",
                "HELD_OPERATION",
                built.summary(),
                built.scope().clusterId(),
                null,
                auditParams,
                false);
        HeldRow row;
        try {
            row = newTransaction.execute(status -> {
                boolean inserted = store.insert(new HeldStore.NewHeld(
                        id,
                        type,
                        built.type().version(),
                        built.type().mode(),
                        requester.authKind(),
                        provider.pluginId(),
                        requester.userId(),
                        requester.username(),
                        requester.tokenId(),
                        currentTokenName(),
                        built.summary(),
                        hold.approverHint(),
                        built.traits(),
                        built.redacted(),
                        built.display(),
                        built.effect(),
                        hold.policy(),
                        built.hash(),
                        sealed,
                        built.scope().clusterId(),
                        built.scope().environmentId(),
                        ttl,
                        event.getId()));
                if (!inserted) {
                    return null;
                }
                HeldRow stored = store.get(id).orElseThrow();
                store.event(id, HeldEvent.Kind.REQUESTED, requester.userId(), requester.username(), null);
                notices.held(stored, approvers, now);
                return stored;
            });
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        if (row == null) {
            HeldRow first =
                    store.openDuplicate(requester.userId(), built.hash()).orElseThrow();
            audit.refuse(event, "The same request is already open as " + first.id());
            return held(first);
        }
        audit.succeed(event, 1);
        log.info(
                "approval-gate held id={} type={} provider={} requester={}",
                id,
                type,
                provider.pluginId(),
                requester.username());
        return held(row);
    }

    private static OperationHeldException held(HeldRow row) {
        return new OperationHeldException(row.id(), row.summary(), row.expiresAt());
    }

    private OperationDeniedException deny(GatedOperation<Record> type, Built built, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type.type());
        if (built != null) {
            params.put(PARAMS_HASH, built.hashHex());
        }
        audit.refused(
                actors.resolve(),
                "OPERATION_DENIED",
                "GATED_OPERATION",
                built == null ? type.type() : built.summary(),
                built == null ? null : built.scope().clusterId(),
                params,
                reason);
        return new OperationDeniedException(reason);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** What the audit rows of an operation the gate let through carry about it. */
    static Map<String, String> approval(String providerId, PolicyRef policy) {
        return Map.of("provider", providerId, "policy", policyLabel(policy));
    }

    static String policyLabel(PolicyRef policy) {
        return policy.id() + "@" + policy.version();
    }

    /**
     * The signed-in user behind this thread, and how they came in: an API token, or an MCP agent when the entry
     * point bound {@link GateContext#ORIGIN} so; otherwise a session. Empty without an account.
     */
    static Optional<Requester> currentRequester() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof RahdarPrincipal principal) || principal.userId() == null) {
            return Optional.empty();
        }
        UUID tokenId = principal instanceof TokenPrincipal token ? token.tokenId() : null;
        boolean agent = GateContext.ORIGIN.isBound() && GateContext.ORIGIN.get() == AuthKind.AGENT;
        AuthKind kind = AuthKind.SESSION;
        if (agent) {
            kind = AuthKind.AGENT;
        } else if (tokenId != null) {
            kind = AuthKind.TOKEN;
        }
        return Optional.of(new Requester(principal.userId(), principal.getUsername(), kind, tokenId));
    }

    /** The name of the API token the signed-in user came in with, or {@code null} for a session. */
    private static String currentTokenName() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof RahdarPrincipal principal ? principal.tokenName() : null;
    }

    /** The canonical parameters with each of {@code paths} (JSON Pointers) replaced by {@value #REDACTED}. */
    static String redact(String canonical, Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return canonical;
        }
        JsonNode tree = JSON.readTree(canonical);
        for (String path : paths) {
            if (path == null || !path.startsWith("/")) {
                continue;
            }
            String[] tokens = path.substring(1).split("/", -1);
            JsonNode parent = tree;
            for (int i = 0; i < tokens.length - 1 && parent != null; i++) {
                parent = child(parent, unescape(tokens[i]));
            }
            String last = unescape(tokens[tokens.length - 1]);
            if (parent instanceof ObjectNode object && object.has(last)) {
                object.put(last, REDACTED);
            } else if (parent instanceof ArrayNode array
                    && last.matches("\\d+")
                    && Integer.parseInt(last) < array.size()) {
                array.set(Integer.parseInt(last), JSON.getNodeFactory().textNode(REDACTED));
            }
        }
        return CanonicalJson.write(tree);
    }

    private static JsonNode child(JsonNode node, String token) {
        if (node.isObject()) {
            return node.get(token);
        }
        if (node.isArray() && token.matches("\\d+")) {
            return node.get(Integer.parseInt(token));
        }
        return null;
    }

    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }
}
