package io.github.sudoitir.rahdar.feature.sql.web;

import io.github.sudoitir.rahdar.feature.sql.QueryPlan;
import io.github.sudoitir.rahdar.feature.sql.QueryResult;
import io.github.sudoitir.rahdar.feature.sql.SqlTailPoller;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.BoundView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.NoticeView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.PlanView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.ResultView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.RowView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.SqlNodeOutcomeView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.TailStatusView;
import io.github.sudoitir.rahdar.feature.sql.web.SqlViews.TargetView;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts.Of;
import io.github.sudoitir.rahdar.platform.governance.GovernanceViews;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Domain to wire for the SQL Console. Hand-written rather than generated: every
 * conversion here is an enum to a stable string the frontend switches on, and
 * writing them out is clearer than configuring them.
 */
@Component
public class SqlViewMapper {

    public PlanView toView(Of ids, QueryPlan plan) {
        return new PlanView(
                plan.resolvedSource().name(),
                plan.targets().stream()
                        .map(t -> new TargetView(
                                t.nodeId(),
                                t.nodeName(),
                                ids.subscription(t.subscription()),
                                ids.destination(t.destination()),
                                t.messageCount()))
                        .toList(),
                plan.selector(),
                plan.requiresScan(),
                plan.pushedDown(),
                plan.scanned(),
                plan.estimatedMessagesExamined(),
                plan.effectiveLimit(),
                plan.captured(),
                notices(plan.notices()));
    }

    public ResultView toView(Of ids, QueryResult result, QueryPlan plan) {
        return new ResultView(
                result.nodes().stream().map(n -> toView(ids, n)).toList(),
                result.boundsReached().stream()
                        .map(b -> new BoundView(b.kind().name(), b.value()))
                        .toList(),
                notices(result.notices()),
                result.isPartial(),
                toView(ids, plan));
    }

    /** A row as the caller may see it: every header, property and body value from the governed message. */
    public RowView toView(Of ids, QueryResult.Row row, GovernedMessage governed) {
        return new RowView(
                row.nodeId(),
                row.nodeName(),
                ids.subscription(row.subscription()),
                ids.destination(row.destination()),
                row.messageId(),
                row.messageType(),
                row.durable(),
                row.priority(),
                row.timestamp(),
                row.expiration(),
                row.size(),
                row.jmsType(),
                governed.headers().get("correlationId"),
                governed.headers().get("groupId"),
                governed.headers().get("userId"),
                governed.headers().get("replyTo"),
                governed.body(),
                row.bodyTruncated(),
                governed.properties(),
                row.source().name(),
                text(row.observedAt()),
                text(row.lastSeenAt()),
                row.origin(),
                row.sourceMessageId(),
                GovernanceViews.redactions(governed),
                GovernanceViews.withheld(governed));
    }

    public SqlNodeOutcomeView toView(Of ids, QueryResult.NodeOutcome outcome) {
        return new SqlNodeOutcomeView(
                outcome.nodeId(),
                outcome.nodeName(),
                ids.subscription(outcome.subscription()),
                outcome.status().name(),
                outcome.examined(),
                outcome.matched(),
                outcome.detail());
    }

    public TailStatusView toView(SqlTailPoller.TailStatus status) {
        return new TailStatusView(
                status.enqueued(),
                status.shown(),
                status.polls(),
                text(status.lastPollAt()),
                status.everyMessageMatches());
    }

    private List<NoticeView> notices(List<QueryPlan.Notice> notices) {
        return notices.stream()
                .map(n -> new NoticeView(n.kind().name(), n.detail()))
                .toList();
    }

    private String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
