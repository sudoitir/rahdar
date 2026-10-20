package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The console's entry point: permission, plan, cost gate, execute, audit
 * (ADR-0058).
 *
 * <p>Planning and running are separate operations on purpose. Planning contacts
 * nothing, so the editor can call it on every keystroke; running is the one that
 * costs a broker something, and it is the one that is gated and audited.
 */
@Service
@RequiredArgsConstructor
public class SqlConsoleService {

    private final SqlQueryParser parser;
    private final QueryPlanner planner;
    private final BrokerQueryExecutor brokerExecutor;
    private final IndexQueryExecutor indexExecutor;
    private final ClusterAccessGuard clusterAccess;
    private final ActorResolver actorResolver;
    private final AuditService audit;
    private final SqlProperties properties;
    private final SqlGovernance governance;
    private final HeavyRequests heavy;

    /** Parse, validate and cost a query. Contacts no broker and no database beyond the cache. */
    public QueryPlan plan(UUID clusterId, String sql) {
        QueryAst ast = parseReadable(clusterId, sql);
        governance.guardPredicates(clusterId, ast);
        return governance.withAtRestNotice(planner.plan(clusterId, ast));
    }

    /**
     * Run a query. The cost ceiling is enforced here and not in {@link #plan}, so the
     * console can show an over-budget estimate while the operator is still able to
     * narrow it.
     */
    public Executed run(UUID clusterId, String sql, BrokerQueryExecutor.Sink sink) {
        QueryAst ast = parseReadable(clusterId, sql);
        governance.guardPredicates(clusterId, ast);
        QueryPlan plan = governance.withAtRestNotice(planner.plan(clusterId, ast));
        planner.enforceCostCeiling(plan);

        Actor actor = actorResolver.resolve();
        String key = actor == null ? "anonymous" : actor.displayName();
        // The permit is counted across replicas, so one operator cannot occupy the whole fan-out budget
        // by spreading queries over them. The query's own bounds (rows, scan, time) are reported in its result.
        HeavyRequests.Endpoint endpoint = new HeavyRequests.Endpoint(
                "SQL queries", properties.maxConcurrentQueries(), properties.timeout(), properties.maxRows());
        return heavy.run(endpoint, key, _ -> execute(clusterId, plan, actor, sink));
    }

    private Executed execute(UUID clusterId, QueryPlan plan, Actor actor, BrokerQueryExecutor.Sink sink) {
        AuditEvent event = audit.begin(
                actor,
                "sql.query",
                "CLUSTER",
                plan.ast().queuePattern(),
                clusterId,
                null,
                Map.of(
                        "sql", plan.ast().text(),
                        "source", plan.resolvedSource().name(),
                        "targets", plan.targets().size(),
                        "requiresScan", plan.requiresScan()),
                false);
        try {
            QueryResult result = plan.resolvedSource() == Source.INDEX
                    ? indexExecutor.execute(clusterId, plan, sink)
                    : brokerExecutor.execute(clusterId, plan, sink);
            // Failed only when a node failed. Reaching a bound — the row limit above
            // all — is an ordinary bounded query, and recording every `LIMIT 5` as a
            // failure would leave an audit log nobody can read for real failures.
            boolean anyNodeFailed =
                    result.nodes().stream().anyMatch(n -> n.status() == QueryResult.NodeOutcome.Status.FAILED);
            audit.finish(
                    event,
                    anyNodeFailed,
                    result.rows().size(),
                    anyNodeFailed ? "a node did not answer: " + describeBounds(result) : null,
                    Map.of(
                            "rows", result.rows().size(),
                            "nodes", result.nodes().size(),
                            "partial", result.isPartial(),
                            "bounds", result.boundsReached(),
                            // Classes and counts served in clear by grant, never the values (sql-console spec).
                            "clearFields", sink.clearServed()));
            return new Executed(plan, result);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /**
     * The query, once the caller is known to hold {@code message:read} on every queue its {@code FROM} can
     * match. A query is refused rather than trimmed, so a result never silently omits a queue the text names.
     */
    private QueryAst parseReadable(UUID clusterId, String sql) {
        clusterAccess.requireVisible(clusterId);
        QueryAst ast = parser.parse(sql);
        clusterAccess.requireOnAll(clusterId, ResourceKind.QUEUE, ast.queuePattern(), MessagePermissions.MESSAGE_READ);
        return ast;
    }

    /**
     * Whether the caller may see the clear values of what the query reads: {@code message:clear} on every
     * queue its {@code FROM} can match. Resolved on the request thread, which a stream's rows are not.
     */
    public boolean clearAccessFor(UUID clusterId, String sql) {
        return governance.clearAccess(clusterId, parser.parse(sql).queuePattern());
    }

    private String describeBounds(QueryResult result) {
        if (result.boundsReached().isEmpty()) {
            return "a node did not answer";
        }
        return result.boundsReached().stream()
                .map(b -> b.kind().name())
                .reduce((a, b) -> a + ", " + b)
                .orElse("bounded");
    }

    /** A run, with the plan it ran — the console shows both, and they must be the same one. */
    public record Executed(QueryPlan plan, QueryResult result) {}
}
