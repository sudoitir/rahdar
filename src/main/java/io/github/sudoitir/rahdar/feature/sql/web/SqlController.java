package io.github.sudoitir.rahdar.feature.sql.web;

import io.github.sudoitir.rahdar.feature.messages.web.MessageController;
import io.github.sudoitir.rahdar.feature.sql.MessageVerifier;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan;
import io.github.sudoitir.rahdar.feature.sql.SqlConsoleService;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.PlanView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.SqlExecuteRequest;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.SqlQueryRequest;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.SqlQueryTicketView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.VerifyRequest;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.VerifyView;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SQL Console's read surface (ADR-0058). {@code SELECT} only — the dialect can
 * express no mutation, and acting on a result row goes back through
 * {@link MessageController}, which already carries the dry run, the bulk cap and the
 * audit record.
 *
 * <p>{@code /plan} contacts nothing and is safe to call while the operator types.
 * {@code /query} is the one that costs a broker something, so it is the one the cost
 * ceiling refuses and the audit log records.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/sql")
@RequiredArgsConstructor
public class SqlController {

    private final SqlConsoleService console;
    private final MessageVerifier verifier;
    private final SqlViewMapper mapper;
    private final ResourceIdTexts ids;
    private final SqlQueryTickets tickets;

    /** Parse, validate and cost a query without running it. */
    @PostMapping("/plan")
    public PlanView plan(@PathVariable UUID clusterId, @RequestBody SqlQueryRequest request) {
        QueryPlan plan = console.plan(clusterId, request.sql());
        return mapper.toView(ids.of(clusterId), plan);
    }

    /**
     * Hand back a reference to a query, to be opened as a stream (ADR-0064).
     *
     * <p>The query is not run here and nothing is contacted: this exists so the text
     * travels in a request body rather than in the URL an {@code EventSource} is
     * limited to. It is validated first, so a malformed query is refused with the same
     * syntax error the console already renders instead of failing inside a stream.
     */
    @PostMapping("/query")
    public SqlQueryTicketView query(@PathVariable UUID clusterId, @RequestBody SqlExecuteRequest request) {
        console.plan(clusterId, request.sql());
        UUID id = tickets.issue(clusterId, request.sql(), Boolean.TRUE.equals(request.tail()), tickets.currentOwner());
        return new SqlQueryTicketView(id, Instant.now().plusSeconds(60).toString());
    }

    /**
     * Ask the broker whether one indexed message is still on its queue. The index can
     * only say what it saw; this is the operation that asks the authority, and it
     * answers UNKNOWN rather than GONE whenever the read could not settle it.
     */
    @PostMapping("/verify")
    public VerifyView verify(@PathVariable UUID clusterId, @RequestBody VerifyRequest request) {
        MessageVerifier.Verdict verdict = verifier.verify(
                clusterId,
                request.nodeId(),
                ResourceIds.nameOf(request.subscription()),
                request.messageId(),
                request.timestamp());
        return new VerifyView(verdict.presence().name(), verdict.detail());
    }
}
