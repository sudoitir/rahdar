package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What the Kafka operations share: destructive, run again as the requester after approval, scoped to the cluster.
 */
record KafkaGatedOperation<P extends Record>(
        String type,
        Class<P> paramsType,
        Function<P, UUID> cluster,
        Function<P, String> summary,
        Function<P, List<DisplayRow>> display,
        Function<P, Effect> estimate,
        Consumer<P> replay,
        ClusterEnvironmentIndex clusters,
        Set<String> redacted)
        implements GatedOperation<P> {

    @Override
    public int version() {
        return 1;
    }

    @Override
    public Set<Trait> traits(P params) {
        return Set.of(Trait.DESTRUCTIVE);
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.ON_APPROVAL;
    }

    @Override
    public OperationScope scope(P params) {
        UUID id = cluster.apply(params);
        return OperationScope.cluster(id, clusters.environmentOf(id));
    }

    @Override
    public String summary(P params) {
        return summary.apply(params);
    }

    @Override
    public List<DisplayRow> display(P params) {
        return display.apply(params);
    }

    @Override
    public Set<String> redactedPaths() {
        return redacted;
    }

    @Override
    public Effect estimate(P params) {
        return estimate.apply(params);
    }

    @Override
    public void replay(P params) {
        replay.accept(params);
    }
}
