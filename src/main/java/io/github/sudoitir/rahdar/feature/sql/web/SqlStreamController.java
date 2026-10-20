package io.github.sudoitir.rahdar.feature.sql.web;

import io.github.sudoitir.rahdar.feature.sql.BrokerQueryExecutor;
import io.github.sudoitir.rahdar.feature.sql.CostRefusedException;
import io.github.sudoitir.rahdar.feature.sql.GovernanceRefusedException;
import io.github.sudoitir.rahdar.feature.sql.QueryResult;
import io.github.sudoitir.rahdar.feature.sql.SqlConsoleService;
import io.github.sudoitir.rahdar.feature.sql.SqlGovernance;
import io.github.sudoitir.rahdar.feature.sql.SqlSyntaxException;
import io.github.sudoitir.rahdar.feature.sql.SqlTailPoller;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.StreamFrameView;
import io.github.sudoitir.rahdar.kernel.core.HeavyLimitException;
import io.github.sudoitir.rahdar.kernel.core.Problems;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.kernel.stream.Subscriber;
import io.github.sudoitir.rahdar.kernel.stream.web.StreamController;
import io.github.sudoitir.rahdar.platform.governance.ClearViewAudit;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/clusters/{id}/sql/stream?queryId=…} — the console's one
 * execution path (ADR-0058), opened by reference (ADR-0064).
 *
 * <p>The query text is not in the URL, and cannot be: the caller posts it to
 * {@code /sql/query} and receives a short-lived, single-use reference to open here.
 * An {@code EventSource} can only issue a GET, and a predicate in a URL is a
 * predicate in every proxy access log on the path.
 *
 * <p>Per-request delivery does not belong on the cluster stream: its payload depends
 * on parameters this client alone supplied, and no other subscriber shares it. It is
 * therefore its own stream, scoped to the query and ending when the client
 * disconnects — with the same permission check, the same heartbeat and the same
 * subscriber release as {@link StreamController}.
 *
 * <p>Static execution runs here too rather than on a separate POST, so that progress,
 * per-node outcomes and cancellation are one mechanism whether or not the tail is
 * running. Closing the stream is what stops a query: the sink reports itself
 * cancelled and no further broker read is issued.
 *
 * <p>A refusal travels as an {@code error} frame carrying the same
 * {@link ProblemDetail} the JSON API would have returned, not as an HTTP status: an
 * {@code EventSource} cannot read the body of a non-200, and "query refused" with no
 * estimate and no hint is not something an operator can act on.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SqlStreamController {

    private final SqlConsoleService console;
    private final SqlTailPoller tailPoller;
    private final SqlViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final SseHub hub;
    private final SqlProblemAdvice problems;
    private final SqlQueryTickets tickets;
    private final SqlGovernance governance;
    private final ClearViewAudit clearViews;
    private final ActorResolver actors;

    /** Execution runs off the request thread; the emitter is returned immediately. */
    private final ExecutorService queries = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping(path = "/clusters/{clusterId}/sql/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(
            responseCode = "200",
            description = "A stream of row, node, done, tail and failed frames.",
            content = @Content(schema = @Schema(implementation = StreamFrameView.class)))
    public SseEmitter stream(
            @PathVariable UUID clusterId,
            // Defaulted rather than required: a required parameter is rejected by the
            // framework before the permission check runs, which would answer 400 for a
            // cluster the caller cannot see and confirm that it exists.
            @RequestParam(defaultValue = "") String queryId,
            HttpServletRequest request,
            HttpServletResponse response) {
        clusterAccess.requireVisible(clusterId);
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(0L);
        // Registered with no topics: it wants nothing the cluster broadcasts, only the
        // heartbeat and the drop-on-write-failure behaviour every stream shares.
        Subscriber subscriber = Subscriber.of(emitter, Set.of(), request);
        hub.register(clusterId, subscriber);

        // Clear access and the actor are resolved here, on the request thread: tail rows arrive on threads with
        // no security context, and are governed and audited for the caller who opened the stream.
        Session session = new Session(emitter, clusterId, actors.resolve());
        emitter.onCompletion(() -> release(clusterId, subscriber, session));
        emitter.onTimeout(() -> release(clusterId, subscriber, session));
        emitter.onError(e -> release(clusterId, subscriber, session));

        // The permission check and the audit actor both read the security context, and
        // a virtual thread starts with an empty one.
        SecurityContext security = SecurityContextHolder.getContext();
        SqlQueryTickets.Ticket ticket = redeem(queryId, clusterId, session);
        if (ticket == null) {
            return emitter;
        }
        queries.execute(() -> {
            SecurityContextHolder.setContext(security);
            try {
                run(clusterId, ticket.sql(), ticket.tail(), session);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    /**
     * Exchange the reference for the query it stands for (ADR-0064). A reference that
     * has expired, been used, or belongs to someone else is reported as a refusal
     * frame rather than a 404, for the same reason every other refusal here is: an
     * {@code EventSource} cannot read the body of a non-200.
     */
    private SqlQueryTickets.Ticket redeem(String queryId, UUID clusterId, Session session) {
        SqlQueryTickets.Ticket ticket = null;
        try {
            ticket = tickets.redeem(UUID.fromString(queryId), clusterId, tickets.currentOwner())
                    .orElse(null);
        } catch (IllegalArgumentException _) {
            // Not a reference at all; the message below is the same either way.
        }
        if (ticket == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.GONE,
                    "This query reference is no longer valid. A reference is single use and expires after a"
                            + " minute; run the query again.");
            problem.setTitle("The query could not be run");
            session.send("failed", problem);
            session.complete();
        }
        return ticket;
    }

    private void run(UUID clusterId, String sql, boolean tail, Session session) {
        SqlConsoleService.Executed executed;
        try {
            session.clearAccess = console.clearAccessFor(clusterId, sql);
            executed = console.run(clusterId, sql, session);
        } catch (RuntimeException e) {
            session.send("failed", problemFor(e));
            session.complete();
            return;
        }
        session.send("done", mapper.toView(executed.result(), executed.plan()));
        session.tailing = true;
        if (!tail || session.cancelled) {
            session.complete();
            return;
        }
        SqlTailPoller.Tail running = tailPoller.start(clusterId, executed.plan(), session);
        // The static rows are already on the client. Advancing the mark past them is
        // what stops the first poll from sending every one of them again.
        executed.result().rows().forEach(running::seen);
        session.tail.set(running);
    }

    private void release(UUID clusterId, Subscriber subscriber, Session session) {
        hub.remove(clusterId, subscriber);
        session.cancelled = true;
        SqlTailPoller.Tail tail = session.tail.get();
        if (tail != null) {
            tail.stop();
        }
        session.recordTailClearViews();
    }

    /**
     * The same problem the JSON API would have returned. Dispatching by type here
     * rather than rebuilding the payloads keeps one definition of what a refused query
     * says, so the console renders a refusal identically whichever path produced it.
     */
    private ProblemDetail problemFor(RuntimeException e) {
        if (e instanceof SqlSyntaxException syntax) {
            return problems.onSqlSyntax(syntax);
        }
        if (e instanceof CostRefusedException refused) {
            return problems.onCostRefused(refused);
        }
        if (e instanceof GovernanceRefusedException refused) {
            return problems.onGovernanceRefused(refused);
        }
        if (e instanceof HeavyLimitException tooMany) {
            return Problems.heavyLimit(tooMany);
        }
        log.debug("SQL console query failed", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                SecretRedactor.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        problem.setTitle("The query could not be run");
        return problem;
    }

    /**
     * One open console stream: the sink for the static run, the listener for the tail,
     * and the thing that knows the client is gone.
     */
    private final class Session implements BrokerQueryExecutor.Sink, SqlTailPoller.Listener {

        private final SseEmitter emitter;
        private final UUID clusterId;
        /** Set before the query runs, from what the query reads: rows and tail rows are shown clear only where it holds. */
        private volatile boolean clearAccess;

        private final Actor actor;
        private volatile boolean cancelled;
        private final AtomicReference<SqlTailPoller.Tail> tail = new AtomicReference<>();

        /** False while the static result streams, true once the tail delivers. They are audited separately. */
        private volatile boolean tailing;

        private final Map<String, Long> staticClear = new ConcurrentHashMap<>();
        private final Map<String, Long> tailClear = new ConcurrentHashMap<>();
        private final AtomicLong tailClearRows = new AtomicLong();

        private Session(SseEmitter emitter, UUID clusterId, Actor actor) {
            this.emitter = emitter;
            this.clusterId = clusterId;
            this.actor = actor;
        }

        @Override
        public void row(QueryResult.Row row) {
            GovernedMessage governed = governance.govern(clusterId, clearAccess, row);
            Map<String, Long> served = ClearViewAudit.classesServedClear(List.of(governed));
            if (!served.isEmpty()) {
                Map<String, Long> tally = tailing ? tailClear : staticClear;
                served.forEach((dataClass, count) -> tally.merge(dataClass, count, Long::sum));
                if (tailing) {
                    tailClearRows.incrementAndGet();
                }
            }
            send("row", mapper.toView(row, governed));
        }

        @Override
        public Map<String, Long> clearServed() {
            return Map.copyOf(staticClear);
        }

        /** The tail's clear values, audited once when the stream ends. The static result's are on the query's row. */
        private void recordTailClearViews() {
            if (tailClear.isEmpty()) {
                return;
            }
            Map<String, Long> classes = Map.copyOf(tailClear);
            tailClear.clear();
            clearViews.recordClear(
                    actor,
                    new GovernContext(clusterId, null, true),
                    "CLUSTER",
                    "sql.tail",
                    classes,
                    tailClearRows.get());
        }

        @Override
        public void nodeFinished(QueryResult.NodeOutcome outcome) {
            send("node", mapper.toView(outcome));
        }

        @Override
        public void status(SqlTailPoller.TailStatus status) {
            send("tail", mapper.toView(status));
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        private void send(String name, Object payload) {
            if (cancelled) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException | RuntimeException _) {
                // A write failure is how a disconnect is discovered, so it is the
                // signal to stop rather than something to report.
                cancelled = true;
            }
        }

        private void complete() {
            try {
                emitter.complete();
            } catch (RuntimeException _) {
                // already closed
            }
        }
    }
}
