package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.TruncateService.Reach;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.TruncateParams;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated truncate, {@code destination.truncate} (ADR-0179, ADR-0216): destructive, run as the requester once
 * approved. Its estimate is what a dry run reports, pinned to the log start of each partition it could touch, so
 * a truncate that ran since is refused when the request would run.
 */
@Configuration(proxyBeanMethods = false)
class TruncateGatedOperations {

    @Bean
    GatedOperation<TruncateParams> destinationTruncateOperation(
            TruncateService truncates, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "destination.truncate";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<TruncateParams> paramsType() {
                return TruncateParams.class;
            }

            @Override
            public Set<Trait> traits(TruncateParams p) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(TruncateParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(TruncateParams p) {
                return "Truncate " + p.path() + " on " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(TruncateParams p) {
                return List.of(
                        DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Destination", p.path()),
                        DisplayRow.of("Removes", "the records before " + cut(p)));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(TruncateParams p) {
                Reach reach = truncates.reach(p);
                return new Effect(reach.records(), "records", reach.stateKey(), null);
            }

            @Override
            public void replay(TruncateParams p) {
                truncates.truncate(p.clusterId(), p.kind(), p.path(), p.cut(), p.etag(), false);
            }

            private String cut(TruncateParams p) {
                return p.cut().time() != null
                        ? p.cut().time().toString()
                        : p.cut().offsets().entrySet().stream()
                                .map(e -> "partition " + e.getKey() + " offset " + e.getValue())
                                .collect(Collectors.joining(", "));
            }
        };
    }
}
