package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditFilter;
import io.github.sudoitir.rahdar.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.rahdar.kernel.audit.web.AuditViews.AuditExportRow;
import io.github.sudoitir.rahdar.kernel.core.Cursor;
import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.core.HeavyTimeoutException;
import io.github.sudoitir.rahdar.kernel.core.HistoryPage;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ReadRouting;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.core.Routed;
import io.github.sudoitir.rahdar.kernel.core.StatementTimeout;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ScopeHierarchy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read side of the audit trail — filtered, paged, newest first, or exported oldest first (non-negotiable #3). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final String ACTION = "action";
    private static final String OUTCOME = "outcome";

    private final AuditEventRepository events;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final ActorResolver actors;
    private final ScopeHierarchy clusters;
    private final HeavyRequests heavy;
    private final ReadRouting routing;

    /** A page of the history: a few at once per user, the page size at most, and the database's 30 s. */
    static final HeavyRequests.Endpoint HISTORY =
            new HeavyRequests.Endpoint("audit history reads", 4, Duration.ofSeconds(30), ResourceQuery.MAX_SIZE);

    /** An export: one at a time per user, ten minutes at most (below the driver's socket timeout), a million rows. */
    static final HeavyRequests.Endpoint EXPORT =
            new HeavyRequests.Endpoint("audit exports", 1, StatementTimeout.LONG, 1_000_000);

    private static final String COLUMNS = """
            id, parent_id, ts, username, host(source_ip) AS source_ip, request_id, action, target_type, target_name,
            affected_count, outcome, dry_run, params::text AS params, error, cluster_name, node_id,
            outcome_detail::text AS outcome_detail""";

    /** The parameters that name a queue or address other than the one the event is about. */
    private static final Set<String> RESOURCE_PARAMS =
            Set.of("source", "target", "targetQueue", "stagingQueue", "queue", "address", "forwardingAddress");

    private static final String HIDDEN = "(a queue or address you may not read)";

    /**
     * A page of the cluster's trail, newest first. By page number for the first {@value HistoryPage#MAX_OFFSET} rows,
     * with a total; by {@code query.after()} beyond that, which costs the same however deep it reads and has no total.
     * Read from the replica when there is one and it is current.
     */
    public HistoryPage<AuditEventView> page(UUID clusterId, AuditQuery query) {
        clusterAccess.requireVisible(clusterId);
        Scope scope = scope(clusterId);
        ResourceQuery paging = ResourceQuery.ofPage(query.page(), query.size());
        if (query.after() == null) {
            HistoryPage.requireReachable(paging);
        }
        AuditFilter filter = filter(clusterId, scope, query);
        Routed<Slice> routed =
                heavy.run(HISTORY, owner(), run -> run.read(routing, client -> slice(client, filter, query, paging)));
        Slice slice = routed.value();
        boolean more = slice.rows().size() > paging.size();
        List<AuditRow> shown = more ? slice.rows().subList(0, paging.size()) : slice.rows();
        String next = more ? cursor(shown.getLast()).encode() : null;
        return new HistoryPage<>(
                shown.stream().map(r -> toView(r, scope.redaction())).toList(),
                paging.page(),
                paging.size(),
                slice.count(),
                more,
                next,
                routed.fromReplica());
    }

    private String owner() {
        return actors.resolve().displayName();
    }

    private static Cursor cursor(AuditRow row) {
        return Cursor.of(row.ts(), row.id());
    }

    /** One more row than the page holds, so there is a next page when it comes back; and the total, by page number. */
    private static Slice slice(JdbcClient client, AuditFilter filter, AuditQuery query, ResourceQuery paging) {
        Where where = where(filter);
        Cursor after = query.after();
        String sql = "SELECT " + COLUMNS + " FROM audit_event WHERE " + where.sql()
                + (after == null ? "" : " AND (ts, id) < (:afterTs, :afterId)")
                + " ORDER BY ts DESC, id DESC LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec spec = client.sql(sql)
                .params(where.params())
                .param("limit", paging.size() + 1)
                .param("offset", after == null ? paging.offset() : 0);
        if (after != null) {
            spec = spec.param("afterTs", Timestamp.from(after.instant())).param("afterId", after.minor());
        }
        List<AuditRow> rows = spec.query(AuditQueryService::row).list();
        Long count = after != null
                ? null
                : client.sql("SELECT count(*) FROM audit_event WHERE " + where.sql())
                        .params(where.params())
                        .query(Long.class)
                        .single();
        return new Slice(rows, count);
    }

    private record Slice(List<AuditRow> rows, Long count) {}

    /** The conditions of a read, and what they bind. Optional filters are left out when unset. */
    private record Where(String sql, Map<String, Object> params) {}

    private static Where where(AuditFilter f) {
        StringBuilder sql = new StringBuilder("cluster_id = :cluster AND ts >= :from AND ts <= :to");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cluster", f.clusterId());
        params.put("from", Timestamp.from(f.from()));
        params.put("to", Timestamp.from(f.to()));
        if (!f.everything()) {
            sql.append(" AND ((target_type = 'QUEUE' AND target_name IN (:queues))")
                    .append(" OR (target_type = 'ADDRESS' AND target_name IN (:addresses)))");
            params.put("queues", f.queues());
            params.put("addresses", f.addresses());
        }
        optional(sql, params, "username", f.username());
        optional(sql, params, ACTION, f.action());
        optional(sql, params, OUTCOME, f.outcome());
        optional(sql, params, "parent_id", f.parentId());
        return new Where(sql.toString(), params);
    }

    private static void optional(StringBuilder sql, Map<String, Object> params, String column, Object value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = :").append(column);
            params.put(column, value);
        }
    }

    private static AuditRow row(ResultSet rs, int i) throws SQLException {
        return new AuditRow(
                rs.getLong("id"),
                rs.getObject("parent_id", Long.class),
                rs.getObject("ts", OffsetDateTime.class).toInstant(),
                rs.getString("username"),
                rs.getString("source_ip"),
                rs.getString("request_id"),
                rs.getString(ACTION),
                rs.getString("target_type"),
                rs.getString("target_name"),
                rs.getObject("affected_count", Long.class),
                rs.getString(OUTCOME),
                rs.getBoolean("dry_run"),
                rs.getString("params"),
                rs.getString("error"),
                rs.getString("cluster_name"),
                rs.getObject("node_id", UUID.class),
                rs.getString("outcome_detail"));
    }

    /** An audit event as the reads keep it: the columns the views are made of, whichever way they were read. */
    private record AuditRow(
            long id,
            Long parentId,
            Instant ts,
            String username,
            String sourceIp,
            String requestId,
            String action,
            String targetType,
            String targetName,
            Long affectedCount,
            String outcome,
            boolean dryRun,
            String params,
            String error,
            String clusterName,
            UUID nodeId,
            String outcomeDetail) {

        static AuditRow of(AuditEventEntity e) {
            return new AuditRow(
                    e.getId(),
                    e.getParentId(),
                    e.getTs(),
                    e.getUsername(),
                    e.getSourceIp(),
                    e.getRequestId(),
                    e.getAction(),
                    e.getTargetType(),
                    e.getTargetName(),
                    e.getAffectedCount(),
                    e.getOutcome(),
                    e.isDryRun(),
                    e.getParams(),
                    e.getError(),
                    e.getClusterName(),
                    e.getNodeId(),
                    e.getOutcomeDetail());
        }
    }

    /**
     * An export that has been authorised and recorded, to run on any thread: everything the caller's rights decide
     * is settled here, so the run needs no request or security context.
     */
    public static final class Export {

        private final UUID clusterId;
        private final String clusterName;
        private final AuditEvent event;
        private final Scope scope;
        private final AuditQuery query;
        private final String owner;

        private Export(
                UUID clusterId, String clusterName, AuditEvent event, Scope scope, AuditQuery query, String owner) {
            this.clusterId = clusterId;
            this.clusterName = clusterName;
            this.event = event;
            this.scope = scope;
            this.query = query;
            this.owner = owner;
        }

        /** The cluster's name, for the file's name. */
        public String clusterName() {
            return clusterName;
        }
    }

    /**
     * Authorise an export of the cluster's trail under the filters of {@code query} (its page and size are not used)
     * and record it as started. The caller needs {@link AuditPermissions#EXPORT} and sees what {@link #page} shows:
     * the whole trail with {@code cluster:read}, otherwise the events about the queues and addresses they may read.
     */
    public Export prepareExport(UUID clusterId, AuditQuery query, String format) {
        clusterAccess.requireVisible(clusterId);
        clusterAccess.requireCluster(clusterId, AuditPermissions.EXPORT);
        Scope scope = scope(clusterId);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("user", blankToNull(query.username()));
        filters.put(ACTION, blankToNull(query.action()));
        filters.put(OUTCOME, blankToNull(query.outcome()));
        filters.put("parentId", query.parentId());
        filters.put("from", query.from());
        filters.put("to", query.to());
        filters.values().removeIf(Objects::isNull);
        String clusterName = clusters.clusterName(clusterId);
        AuditEvent event = audit.begin(
                actors.resolve(),
                "EXPORT_AUDIT",
                "AUDIT",
                clusterName,
                clusterId,
                null,
                Map.of("format", format, "filters", filters),
                false);
        return new Export(clusterId, clusterName, event, scope, query, owner());
    }

    /** Where an export's rows go. Closing is {@link #finish()}, which flushes and ends the file. */
    public interface ExportSink {

        void write(AuditExportRow row);

        /** The export stopped at {@code bound}, so the file is incomplete; written before the file ends. */
        void truncated(HeavyRequests.Bound bound);

        void finish();
    }

    /**
     * Write the export's rows, oldest first, to {@code sink} and record the outcome: the count on success, how far it
     * got when the sink fails (the client went away), the read does, or a bound is reached. A bound ends the file with
     * the sink's mark that it is incomplete and records the export as partial, naming the bound. {@code finish} flushes
     * and closes the sink; it runs before the outcome, so a file cut short by a failing close is never recorded as
     * complete. The export's own row is left out.
     */
    public void run(Export export, ExportSink sink) {
        long[] written = {0};
        HeavyRequests.Bound bound;
        try {
            bound = heavy.run(EXPORT, export.owner, run -> {
                try {
                    run.read(routing, client -> {
                        stream(client, export, run, sink, written);
                        return null;
                    });
                } catch (HeavyTimeoutException _) {
                    // The database cut the read at the time bound; what was written stands, marked as incomplete.
                }
                if (run.truncated()) {
                    sink.truncated(run.reached());
                }
                sink.finish();
                return run.reached();
            });
        } catch (RuntimeException ex) {
            // A write that fails is the client going away; anything else is the read.
            String how = ex instanceof JacksonIOException ? "cancelled" : "failed";
            audit.failPartial(export.event, written[0], how + " after " + written[0] + " rows");
            throw ex;
        }
        if (bound != null) {
            audit.failPartial(
                    export.event,
                    written[0],
                    "stopped at the " + (bound == HeavyRequests.Bound.ROWS ? "row" : "time") + " bound after "
                            + written[0] + " rows");
            return;
        }
        audit.succeed(export.event, written[0]);
    }

    private void stream(JdbcClient client, Export export, HeavyRequests.Run run, ExportSink sink, long[] written) {
        Where where = where(filter(export.clusterId, export.scope, export.query));
        try (Stream<AuditRow> rows = client
                .sql("SELECT " + COLUMNS + " FROM audit_event WHERE " + where.sql() + " ORDER BY ts ASC, id ASC")
                .params(where.params())
                .query(AuditQueryService::row)
                .stream()) {
            Iterator<AuditRow> it = rows.iterator();
            while (it.hasNext()) {
                AuditRow e = it.next();
                if (e.id() == export.event.getId()) {
                    continue;
                }
                if (!run.admit()) {
                    return;
                }
                sink.write(new AuditExportRow(
                        toView(e, export.scope.redaction()), export.scope.everything() ? e.outcomeDetail() : null));
                written[0]++;
            }
        }
    }

    /** What the caller reads of a cluster's trail, resolved once. */
    private record Scope(boolean everything, List<String> queues, List<String> addresses, Redaction redaction) {}

    /**
     * A caller who reads the cluster reads its whole trail. Any other reads the events about the queues and
     * addresses they may read, chosen in the query so a total never counts the rest.
     */
    private Scope scope(UUID clusterId) {
        boolean everything = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        return new Scope(
                everything,
                everything ? List.of("") : readable(clusterId, "QUEUE", ResourceType.SUBSCRIPTION),
                everything ? List.of("") : readable(clusterId, "ADDRESS", ResourceType.DESTINATION),
                everything ? null : redaction(clusterId));
    }

    private static AuditFilter filter(UUID clusterId, Scope scope, AuditQuery query) {
        return new AuditFilter(
                clusterId,
                scope.everything(),
                scope.queues(),
                scope.addresses(),
                blankToNull(query.username()),
                blankToNull(query.action()),
                blankToNull(query.outcome()),
                query.parentId(),
                from(query),
                to(query));
    }

    private static Instant from(AuditQuery query) {
        return query.from() != null ? query.from() : Instant.EPOCH;
    }

    private static Instant to(AuditQuery query) {
        return query.to() != null ? query.to() : Instant.parse("9999-12-31T23:59:59Z");
    }

    @Transactional(readOnly = true)
    public AuditEventView get(UUID clusterId, long id) {
        clusterAccess.requireVisible(clusterId);
        boolean everything = clusterAccess.holds(clusterId, Permissions.CLUSTER_READ);
        ResourceFilter queues = permissions.filter(clusterId, ResourceType.SUBSCRIPTION);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceType.DESTINATION);
        return events.findByIdAndClusterId(id, clusterId)
                .filter(e -> everything
                        || ("QUEUE".equals(e.getTargetType()) && queues.readable(e.getTargetName()))
                        || ("ADDRESS".equals(e.getTargetType()) && addresses.readable(e.getTargetName())))
                .map(e -> toView(e, everything ? null : redaction(clusterId)))
                .orElseThrow(() -> new NotFoundException("audit event", id));
    }

    /**
     * The latest events about one Rahdar-wide target, newest first — a plugin's history. Not
     * cluster-scoped, so the caller enforces who may read it.
     */
    @Transactional(readOnly = true)
    public PagedView<AuditEventView> forTarget(String targetType, String targetName, ResourceQuery query) {
        return PagedView.of(
                events.findByTargetTypeAndTargetNameOrderByTsDesc(
                        targetType, targetName, PageRequest.of(query.page() - 1, query.size())),
                e -> toView(e, null));
    }

    /** The names of one target type that the caller may read; never empty, as an empty collection cannot be bound. */
    private List<String> readable(UUID clusterId, String targetType, ResourceType kind) {
        ResourceFilter filter = permissions.filter(clusterId, kind);
        List<String> names = events.findDistinctTargetNames(clusterId, targetType).stream()
                .filter(filter::readable)
                .toList();
        return names.isEmpty() ? List.of("") : names;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** What a reader who does not read the whole trail may see of the resources an event's parameters name. */
    private record Redaction(ResourceFilter queues, ResourceFilter addresses) {

        boolean sees(String value) {
            // A transfer names its ends as "node/queue".
            String name = value.substring(value.lastIndexOf('/') + 1);
            return queues.readable(name) || addresses.readable(name);
        }
    }

    private Redaction redaction(UUID clusterId) {
        return new Redaction(
                permissions.filter(clusterId, ResourceType.SUBSCRIPTION),
                permissions.filter(clusterId, ResourceType.DESTINATION));
    }

    /**
     * An event about a queue the caller may read can still name another in its parameters, such as where a
     * message was moved or a transfer sent them. A parameter that names a queue or address the caller may not read
     * is replaced, and so is the cluster a transfer went to when one was.
     */
    private AuditEventView toView(AuditEventEntity e, Redaction redaction) {
        return toView(AuditRow.of(e), redaction);
    }

    private AuditEventView toView(AuditRow e, Redaction redaction) {
        String params = redaction == null || e.params() == null ? e.params() : redacted(e.params(), redaction);
        return new AuditEventView(
                e.id(),
                e.parentId(),
                e.ts(),
                e.username(),
                e.sourceIp(),
                e.requestId(),
                e.action(),
                e.targetType(),
                e.targetName(),
                e.affectedCount(),
                e.outcome(),
                e.dryRun(),
                params,
                e.error(),
                e.clusterName(),
                e.nodeId());
    }

    private String redacted(String paramsJson, Redaction redaction) {
        JsonNode params = mapper.readTree(paramsJson);
        if (!(params instanceof ObjectNode object)) {
            return paramsJson;
        }
        boolean hidden = false;
        for (String key : RESOURCE_PARAMS) {
            JsonNode value = object.get(key);
            if (value != null && value.isString() && !redaction.sees(value.asString())) {
                object.put(key, HIDDEN);
                hidden = true;
            }
        }
        if (hidden) {
            object.remove("targetClusterId");
        }
        return mapper.writeValueAsString(object);
    }
}
