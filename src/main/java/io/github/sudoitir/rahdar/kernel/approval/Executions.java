package io.github.sudoitir.rahdar.kernel.approval;

import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry.AttachedProvider;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.rahdar.kernel.gate.CanonicalJson;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GateLease;
import io.github.sudoitir.rahdar.kernel.gate.GateScope;
import io.github.sudoitir.rahdar.kernel.gate.GateTicket;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperationRegistry;
import io.github.sudoitir.rahdar.kernel.gate.HeldEvent;
import io.github.sudoitir.rahdar.kernel.gate.HeldState;
import io.github.sudoitir.rahdar.kernel.gate.OperationDeniedException;
import io.github.sudoitir.rahdar.kernel.gate.Requester;
import io.github.sudoitir.rahdar.kernel.gate.RunCheck;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.PersonalTokens;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs approved requests exactly once (ADR-0180, design decision 6). A claim is one conditional update, so of every
 * replica and thread that tries only one gets the row. Before running, the seals are opened and checked, the provider
 * must still be armed and attached and the operation's version unchanged, the requester is rebuilt as they stand now,
 * the effect is estimated again as them and its state key must not have moved, and the provider's {@code checkRun}
 * must allow it. Any of these failing ends the request {@code REFUSED}, audited. The replay then runs as the requester
 * under a one-use ticket, its audit rows children of the request's and carrying the approval.
 */
@Component
@Slf4j
class Executions {

    /** How many approved requests one replica runs at once. */
    static final int MAX_CONCURRENT = 4;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeldStore store;
    private final Seals seals;
    private final ApprovalProviderRegistry providers;
    private final GatedOperationRegistry operations;
    private final ProviderCalls calls;
    private final IssuedTickets tickets;
    private final OperatorHandoff handoff;
    /** Absent when API tokens are switched off; a request made with a token is then refused. */
    private final Optional<PersonalTokens> personalTokens;

    private final AuditService audit;
    private final ApprovalNotices notices;
    private final ReplicaRegistry replicas;
    private final TransactionTemplate tx;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private final ExecutorService runners = Executors.newVirtualThreadPerTaskExecutor();

    Executions(
            HeldStore store,
            Seals seals,
            ApprovalProviderRegistry providers,
            GatedOperationRegistry operations,
            ProviderCalls calls,
            IssuedTickets tickets,
            OperatorHandoff handoff,
            Optional<PersonalTokens> personalTokens,
            AuditService audit,
            ApprovalNotices notices,
            ReplicaRegistry replicas,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.seals = seals;
        this.providers = providers;
        this.operations = operations;
        this.calls = calls;
        this.tickets = tickets;
        this.handoff = handoff;
        this.personalTokens = personalTokens;
        this.audit = audit;
        this.notices = notices;
        this.replicas = replicas;
        this.tx = new TransactionTemplate(transactions);
    }

    /** A check before running failed; the request ends refused with this reason. */
    static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message);
        }
    }

    /** What the checks established: the operation, the sealed parameters, and who runs it. */
    private record Checked(GatedOperation<Record> type, Record params, Operator operator) {}

    // ---- starting runs -----------------------------------------------------------------------------

    /**
     * Starts an approved request in the background on this replica, when a slot is free; the runner job picks up
     * whatever is left. Returns at once.
     */
    void submit(UUID id) {
        if (!slots.tryAcquire()) {
            return;
        }
        try {
            runners.execute(() -> {
                try {
                    runApproved(id);
                } catch (RuntimeException e) {
                    log.warn("approval-run id={} could not run", id, e);
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
    }

    /** The runner job: approved requests left over by a busy or stopped replica. */
    void runDue(Duration olderThan) {
        for (HeldRow row : store.list(Set.of(HeldState.APPROVED), null, null, null, null, 100)) {
            if (row.mode() == ExecutionMode.ON_APPROVAL
                    && row.decidedAt() != null
                    && row.decidedAt().isBefore(store.now().minus(olderThan))
                    && providers.attached(row.providerId()).isPresent()) {
                submit(row.id());
            }
        }
    }

    /** Claims and runs one approved request, if it is still approved and its provider is attached here. */
    void runApproved(UUID id) {
        Optional<HeldRow> approved = store.get(id)
                .filter(row -> row.state() == HeldState.APPROVED && row.mode() == ExecutionMode.ON_APPROVAL);
        if (approved.isEmpty()
                || providers.attached(approved.get().providerId()).isEmpty()) {
            return;
        }
        Optional<HeldRow> claimed = claim(id);
        if (claimed.isEmpty()) {
            return;
        }
        HeldRow row = claimed.get();
        Checked checked;
        try {
            checked = check(row, null);
        } catch (Refusal refusal) {
            refuse(row, refusal.getMessage());
            return;
        }
        GateTicket ticket = tickets.replay(row.id(), row.type(), row.paramsHashHex());
        Operator operator = new Operator(
                checked.operator().principal(), checked.operator().actor(), new GateLease(ticket, () -> {}));
        try {
            ScopedValue.where(AuditScope.PARENT, row.requestAuditId())
                    .where(AuditScope.APPROVAL, approval(row))
                    .run(() -> handoff.runAs(operator, () -> checked.type().replay(checked.params())));
        } catch (AccessDeniedException e) {
            refuse(row, "The requester no longer holds the permission this needs: " + e.getMessage());
            return;
        } catch (ReplayMismatchException e) {
            refuse(row, e.getMessage());
            return;
        } catch (RuntimeException e) {
            if (Thread.interrupted() || interrupted(e)) {
                // Rahdar is stopping: the replay may have done part of its work, so nobody can say it failed.
                log.warn("approval-run id={} type={} interrupted", row.id(), row.type());
                finish(row, HeldState.OUTCOME_UNKNOWN, "Interrupted while running; whether it took effect is unknown.");
                return;
            }
            log.warn("approval-run id={} type={} failed", row.id(), row.type(), e);
            finish(row, HeldState.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return;
        }
        if (!tickets.consumed(ticket)) {
            log.warn("approval-run id={} type={}: the replay never passed the gate", row.id(), row.type());
        }
        finish(row, HeldState.SUCCEEDED, "Ran as " + row.requesterUsername() + ".");
    }

    /**
     * The requester submitted a {@link ExecutionMode#BY_REQUESTER} request again after its approval: it is claimed,
     * checked and run here, inside their own request, so only they see its result.
     */
    <R> R completeByRequester(HeldRow approved, Requester requester, Supplier<R> action) {
        if (!approved.requesterId().equals(requester.userId())
                || !Objects.equals(approved.tokenId(), requester.tokenId())) {
            throw new OperationDeniedException(
                    "Only the requester, signed in the same way, may complete this request.");
        }
        HeldRow row = claim(approved.id())
                .orElseThrow(() -> new OperationDeniedException(
                        "This approved request is no longer waiting to be completed; it may have expired."));
        try {
            check(row, requester);
        } catch (Refusal refusal) {
            refuse(row, refusal.getMessage());
            throw new OperationDeniedException(refusal.getMessage());
        }
        GateTicket cover = tickets.covering(row.type());
        R result;
        try {
            result = ScopedValue.where(AuditScope.PARENT, row.requestAuditId())
                    .where(AuditScope.APPROVAL, approval(row))
                    .where(GateScope.COVERED, cover)
                    .call(action::get);
        } catch (AccessDeniedException e) {
            refuse(row, "The requester no longer holds the permission this needs: " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            finish(row, HeldState.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        } finally {
            tickets.release(cover);
        }
        finish(row, HeldState.SUCCEEDED, "Completed by " + row.requesterUsername() + ".");
        return result;
    }

    // ---- the checks --------------------------------------------------------------------------------

    /**
     * Every check before running.
     *
     * @param requester the requester completing it themselves, or null to rebuild them as they stand now
     */
    private Checked check(HeldRow row, Requester requester) {
        Seals.Payload payload;
        try {
            payload = seals.verify(row);
        } catch (Seals.Integrity e) {
            log.warn("approval-run id={} integrity: {}", row.id(), e.getMessage());
            throw new Refusal("Integrity check failed: " + e.getMessage() + ". It was not run.");
        }
        if (!providers.armedProviderId().map(row.providerId()::equals).orElse(false)) {
            throw new Refusal("The approval provider that held this is no longer installed.");
        }
        AttachedProvider provider = providers
                .attached(row.providerId())
                .orElseThrow(() -> new Refusal("The approval provider is not running here."));
        GatedOperation<Record> type = typeOf(row);
        Record params;
        try {
            params = JSON.readValue(payload.canonical(), type.paramsType());
        } catch (RuntimeException _) {
            throw new Refusal("Its parameters no longer read as this operation's; request it again.");
        }
        String rehash =
                HexFormat.of().formatHex(CanonicalJson.hash(type.type(), type.version(), CanonicalJson.write(params)));
        if (!rehash.equals(row.paramsHashHex())) {
            throw new Refusal("Its parameters no longer read the same; request it again.");
        }
        Operator operator = requester == null ? requesterNow(row) : null;
        Effect now;
        try {
            now = operator == null ? type.estimate(params) : handoff.callAs(operator, () -> type.estimate(params));
        } catch (AccessDeniedException _) {
            throw new Refusal("The requester no longer holds the permission this needs.");
        } catch (RuntimeException e) {
            log.warn("approval-run id={} estimate failed", row.id(), e);
            throw new Refusal("Rahdar could not estimate what this would do now, so it was not run.");
        }
        if (now == null || !Objects.equals(now.etag(), payload.etag())) {
            throw new Refusal("What this acts on changed since it was requested; request it again.");
        }
        RunCheck verdict;
        try {
            verdict = calls.call("checkRun", () -> provider.provider().checkRun(row.view(), now));
        } catch (ApprovalUnavailableException e) {
            throw new Refusal("The approval provider did not confirm the run: " + e.getMessage());
        }
        if (!verdict.allowed()) {
            throw new Refusal(verdict.reason() == null ? "The approval provider refused the run." : verdict.reason());
        }
        return new Checked(type, params, operator);
    }

    @SuppressWarnings("unchecked")
    private GatedOperation<Record> typeOf(HeldRow row) {
        GatedOperation<?> type = operations
                .forType(row.type())
                .orElseThrow(() -> new Refusal("Rahdar no longer has the operation '" + row.type() + "'."));
        if (type.version() != row.typeVersion()) {
            throw new Refusal("Rahdar changed this operation since it was requested (version " + row.typeVersion()
                    + " is now " + type.version() + "); request it again.");
        }
        return (GatedOperation<Record>) type;
    }

    /**
     * The requester as their account stands now: their session's user, or the token they used. Empty, and so
     * refused, when the user is disabled or the token revoked or expired.
     */
    Operator requesterNow(HeldRow row) {
        if (row.tokenId() != null && personalTokens.isEmpty()) {
            throw new Refusal("The requester used an API token, and API tokens are switched off on this Rahdar.");
        }
        Optional<Operator> operator = row.tokenId() == null
                ? handoff.forUser(row.requesterId())
                : personalTokens
                        .orElseThrow()
                        .principal(row.tokenId())
                        .filter(token -> row.requesterId().equals(token.userId()))
                        .map(token -> new Operator(
                                token,
                                new Actor(token.getUsername(), null, null, token.userId(), token.tokenName()),
                                null));
        return operator.orElseThrow(() -> new Refusal(
                row.tokenId() == null
                        ? "The requester's account is disabled."
                        : "The API token the requester used is revoked or expired."));
    }

    /**
     * Whether the requester of an open request could no longer run it: their account or token no longer acts, or the
     * operation's estimate refuses them. Empty when that cannot be told.
     */
    Optional<Boolean> requesterLacksPermission(HeldRow row) {
        if (!row.state().open()) {
            return Optional.empty();
        }
        GatedOperation<Record> type;
        try {
            type = typeOf(row);
        } catch (Refusal _) {
            return Optional.empty();
        }
        try {
            Operator operator = requesterNow(row);
            Record params = JSON.readValue(seals.openPayload(row).canonical(), type.paramsType());
            handoff.callAs(operator, () -> type.estimate(params));
            return Optional.of(false);
        } catch (Refusal | AccessDeniedException _) {
            return Optional.of(true);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    // ---- ending ------------------------------------------------------------------------------------

    private Optional<HeldRow> claim(UUID id) {
        return tx.execute(status -> {
            Optional<HeldRow> row = store.claim(id, replicas.id());
            row.ifPresent(r -> store.event(r.id(), HeldEvent.Kind.EXECUTING, null, null, null));
            return row;
        });
    }

    private void refuse(HeldRow row, String why) {
        ScopedValue.where(AuditScope.PARENT, row.requestAuditId())
                .run(() -> audit.refused(
                        Actor.ofSystem(),
                        "HELD_OPERATION_REFUSED",
                        "HELD_OPERATION",
                        row.summary(),
                        row.clusterId(),
                        Map.of("heldOperation", row.id().toString(), "type", row.type()),
                        why));
        log.warn("approval-run id={} type={} refused: {}", row.id(), row.type(), why);
        finish(row, HeldState.REFUSED, why);
    }

    private void finish(HeldRow row, HeldState to, String detail) {
        tx.executeWithoutResult(status -> store.end(row.id(), List.of(HeldState.EXECUTING), to, detail)
                .ifPresent(ended -> {
                    store.event(ended.id(), kindOf(to), null, null, detail);
                    notices.ended(ended);
                }));
    }

    private static boolean interrupted(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    static HeldEvent.Kind kindOf(HeldState state) {
        return HeldEvent.Kind.valueOf(state.name());
    }

    private static Map<String, String> approval(HeldRow row) {
        Map<String, String> approval = new HashMap<>(GateEngine.approval(row.providerId(), row.policy()));
        approval.put("heldOperation", row.id().toString());
        approval.put("approver", row.approverUsername());
        return Map.copyOf(approval);
    }

    @PreDestroy
    void close() {
        runners.shutdownNow();
    }
}
