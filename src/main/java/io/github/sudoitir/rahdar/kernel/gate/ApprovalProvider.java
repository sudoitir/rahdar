package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * Decides whether a gated operation may run now (ADR-0179). A plugin declares {@code approvalProvider}
 * in {@code plugin.json}, naming the permission its approvers need, and exposes one bean of this
 * type; at most one provider is active. Every method must be pure and return quickly: Rahdar bounds
 * each call (3 seconds by default) and treats a throw or a timeout as "unavailable", which fails
 * closed. Rahdar owns held operations, applies its own approver rules before {@link #checkVote}
 * (ADR-0181), and enforces {@link Effect#etag()} itself before {@link #checkRun}.
 */
@PluginApi
public interface ApprovalProvider {

    /**
     * Whether this provider holds anything right now. A provider with no policy yet allows everything (setup mode)
     * and returns {@code false}, so Rahdar does not require {@link ApproverPool#QUORUM} approvers of an installation
     * that is not asking anyone to approve. The default is {@code true}: a provider that does not say enforces.
     * Answered from the provider's own state; it must be quick and must not throw.
     */
    default boolean enforcing() {
        return true;
    }

    /** Allow, hold or deny. In {@link GateRequest.Mode#PREVIEW} nothing is stored either way. */
    GateDecision decide(GateRequest request);

    /** Whether this approver may cast this vote on this request, once Rahdar's own rules passed. */
    VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote);

    /**
     * Whether an approved request may still run, given its effect now; for example refusing when the
     * count grew beyond what the policy tolerates.
     */
    RunCheck checkRun(HeldOperationView held, Effect now);
}
