package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.feature.transfer.TransferService.Reach;
import io.github.sudoitir.rahdar.feature.transfer.TransferService.TransferExecuteParams;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferExecuteRequest;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated {@code transfer.execute} (ADR-0179). A transfer is held as destructive whichever its mode: a move removes
 * the messages from their source, and a copy is held with it so the two cannot be told apart by what they let through.
 * Its estimate is the previewed plan, and the plan's hash is the state key, so a plan that changed is refused when it
 * would start.
 */
@Configuration(proxyBeanMethods = false)
class TransferGatedOperations {

    @Bean
    GatedOperation<TransferExecuteParams> transferExecuteOperation(
            TransferService transfers, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "transfer.execute";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<TransferExecuteParams> paramsType() {
                return TransferExecuteParams.class;
            }

            @Override
            public Set<Trait> traits(TransferExecuteParams p) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(TransferExecuteParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(TransferExecuteParams p) {
                return (p.mode() == TransferMode.MOVE ? "Move" : "Copy") + " messages from queue " + p.sourceQueue()
                        + " on " + clusters.labelOf(p.clusterId()) + " to queue " + p.targetQueue() + " on "
                        + clusters.labelOf(p.targetClusterId());
            }

            @Override
            public List<DisplayRow> display(TransferExecuteParams p) {
                return List.of(
                        DisplayRow.of("Mode", p.mode() == TransferMode.MOVE ? "move" : "copy"),
                        DisplayRow.of("From", p.sourceQueue() + " on " + clusters.labelOf(p.clusterId())),
                        DisplayRow.of("To", p.targetQueue() + " on " + clusters.labelOf(p.targetClusterId())));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(TransferExecuteParams p) {
                Reach reach = transfers.reach(p.clusterId(), p.runId());
                // An unknown size is held as zero with a sentence saying so; the plan's hash still pins the run.
                return new Effect(
                        reach.messages() == null ? 0 : reach.messages(),
                        "messages",
                        reach.planHash(),
                        reach.messages() == null ? "How many messages this selects is not known." : null);
            }

            @Override
            public void replay(TransferExecuteParams p) {
                transfers.execute(
                        p.clusterId(),
                        p.runId(),
                        new TransferExecuteRequest(
                                p.planHash(), p.override(), p.acknowledged(), p.confirmSubscription()));
            }
        };
    }
}
