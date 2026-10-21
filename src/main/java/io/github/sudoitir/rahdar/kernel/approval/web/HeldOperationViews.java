package io.github.sudoitir.rahdar.kernel.approval.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.approval.Approvals;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperationInfo;
import io.github.sudoitir.rahdar.kernel.gate.HeldEvent;
import io.github.sudoitir.rahdar.kernel.gate.HeldOperationView;
import io.github.sudoitir.rahdar.kernel.gate.PolicyRef;
import io.github.sudoitir.rahdar.kernel.gate.Vote;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The wire shapes of the held-operations API. */
public final class HeldOperationViews {

    private HeldOperationViews() {}

    public record HeldDisplayRowView(
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(nullable = true) String from,
            @Schema(nullable = true) String to) {

        static HeldDisplayRowView of(DisplayRow row) {
            return new HeldDisplayRowView(row.label(), row.from(), row.to());
        }
    }

    public record HeldEffectView(
            @Schema(requiredMode = REQUIRED) long count,
            @Schema(requiredMode = REQUIRED) String unit,
            @Schema(nullable = true) String detail) {

        static HeldEffectView of(Effect effect) {
            return effect == null ? null : new HeldEffectView(effect.count(), effect.unit(), effect.detail());
        }
    }

    public record HeldPolicyView(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String version,
            @Schema(nullable = true) String name) {

        static HeldPolicyView of(PolicyRef policy) {
            return policy == null ? null : new HeldPolicyView(policy.id(), policy.version(), policy.name());
        }
    }

    public record HeldOperationSummaryView(
            @Schema(requiredMode = REQUIRED) UUID id,

            @Schema(requiredMode = REQUIRED, description = "The operation type, such as subscription.purge.")
            String type,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {
                        "HELD",
                        "APPROVED",
                        "EXECUTING",
                        "REJECTED",
                        "CANCELLED",
                        "EXPIRED",
                        "SUCCEEDED",
                        "FAILED",
                        "REFUSED",
                        "OUTCOME_UNKNOWN"
                    })
            String state,

            @Schema(requiredMode = REQUIRED) String summary,
            @Schema(requiredMode = REQUIRED) UUID requesterId,
            @Schema(requiredMode = REQUIRED) String requesterUsername,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"SESSION", "TOKEN", "AGENT"})
            String authKind,

            @Schema(nullable = true) UUID clusterId,
            @Schema(requiredMode = REQUIRED) Instant requestedAt,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @Schema(nullable = true) String approverUsername,
            @Schema(nullable = true) Instant decidedAt,
            @Schema(nullable = true) Instant finishedAt) {

        static HeldOperationSummaryView of(HeldOperationView view) {
            return new HeldOperationSummaryView(
                    view.id(),
                    view.type(),
                    view.state().name(),
                    view.summary(),
                    view.requester().userId(),
                    view.requester().username(),
                    view.requester().authKind().name(),
                    view.scope().clusterId(),
                    view.requestedAt(),
                    view.expiresAt(),
                    view.approverUsername(),
                    view.decidedAt(),
                    view.finishedAt());
        }
    }

    public record HeldOperationPageView(
            @Schema(requiredMode = REQUIRED) List<HeldOperationSummaryView> items,

            @Schema(nullable = true, description = "Pass as before for the next page; null at the end.")
            UUID next) {

        static HeldOperationPageView of(Approvals.Page page) {
            return new HeldOperationPageView(
                    page.items().stream().map(HeldOperationSummaryView::of).toList(), page.next());
        }
    }

    public record HeldEventView(
            @Schema(requiredMode = REQUIRED) long seq,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(nullable = true) UUID actorId,
            @Schema(nullable = true) String actorUsername,
            @Schema(nullable = true) String detail,
            @Schema(requiredMode = REQUIRED) Instant at) {

        static HeldEventView of(HeldEvent event) {
            return new HeldEventView(
                    event.cursor().seq(),
                    event.kind().name(),
                    event.actorId(),
                    event.actorUsername(),
                    event.detail(),
                    event.at());
        }
    }

    public record HeldOperationDetailView(
            @Schema(requiredMode = REQUIRED) HeldOperationSummaryView operation,
            @Schema(requiredMode = REQUIRED) int typeVersion,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"ON_APPROVAL", "BY_REQUESTER"})
            String mode,

            @Schema(requiredMode = REQUIRED) List<String> traits,
            @Schema(nullable = true) UUID environmentId,
            @Schema(requiredMode = REQUIRED) List<HeldDisplayRowView> display,

            @Schema(requiredMode = REQUIRED, description = "The canonical parameters, secrets redacted.")
            String params,

            @Schema(requiredMode = REQUIRED, description = "Echo it, with version, to decide.")
            String paramsHash,

            @Schema(requiredMode = REQUIRED) int version,

            @Schema(nullable = true, description = "The name of the API token the requester used; null for a session.")
            String tokenName,

            @Schema(requiredMode = REQUIRED) HeldEffectView effect,
            @Schema(requiredMode = REQUIRED) HeldPolicyView policy,
            @Schema(nullable = true) String approverHint,
            @Schema(nullable = true) UUID approverId,
            @Schema(nullable = true) String decisionReason,
            @Schema(nullable = true) String outcomeDetail,

            @Schema(requiredMode = REQUIRED, description = "The request's page in the console.")
            String link,

            @Schema(requiredMode = REQUIRED) List<HeldEventView> events,
            @Schema(requiredMode = REQUIRED) boolean mine,
            @Schema(requiredMode = REQUIRED) boolean canDecide,

            @Schema(nullable = true, description = "Why the current user may not decide it.")
            String decideRefusal,

            @Schema(requiredMode = REQUIRED) boolean canCancel,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether the current user's session verified a second factor; a policy may need it.")
            boolean mfaVerified,

            @Schema(
                    nullable = true,
                    description = "Whether the requester could no longer run it; null when unknown or ended.")
            Boolean requesterLacksPermission) {

        static HeldOperationDetailView of(Approvals.Detail detail) {
            HeldOperationView view = detail.view();
            return new HeldOperationDetailView(
                    HeldOperationSummaryView.of(view),
                    view.typeVersion(),
                    view.mode().name(),
                    view.traits().stream().map(Enum::name).sorted().toList(),
                    view.scope().environmentId(),
                    view.display().stream().map(HeldDisplayRowView::of).toList(),
                    view.params(),
                    view.paramsHash(),
                    detail.version(),
                    detail.tokenName(),
                    HeldEffectView.of(view.effect()),
                    HeldPolicyView.of(view.policy()),
                    detail.approverHint(),
                    view.approverId(),
                    view.decisionReason(),
                    view.outcomeDetail(),
                    detail.link(),
                    detail.events().stream().map(HeldEventView::of).toList(),
                    detail.mine(),
                    detail.canDecide(),
                    detail.decideRefusal(),
                    detail.canCancel(),
                    detail.mfaVerified(),
                    detail.requesterLacksPermission());
        }
    }

    public record HeldDecisionRequest(
            @Schema(requiredMode = REQUIRED) Vote vote,

            @Schema(nullable = true, description = "Required to reject; at most 500 characters.")
            String reason,

            @Schema(requiredMode = REQUIRED, description = "The paramsHash of the request as shown.")
            String paramsHash,

            @Schema(requiredMode = REQUIRED, description = "The version of the request as shown.")
            Integer version) {}

    public record GatedOperationListView(
            @Schema(requiredMode = REQUIRED, description = "Every operation type the gate covers, ordered by type.")
            List<GatedOperationInfo> items) {}

    public record GateStatusView(
            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether an approval provider is installed and meant to run.")
            boolean armed,

            @Schema(nullable = true) String providerId,

            @Schema(requiredMode = REQUIRED, description = "Whether the provider runs on this Rahdar instance.")
            boolean attached,

            @Schema(requiredMode = REQUIRED, description = "Whether break-glass lets operations bypass approval.")
            boolean breakGlass,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether the provider holds anything now; false while it has no policy.")
            boolean enforcing,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "How many enabled users may approve for the whole installation.")
            int approvers,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether there are enough approvers (two) for a request to be decided by"
                            + " someone other than its requester.")
            boolean quorate) {}
}
