package io.github.sudoitir.rahdar.feature.bulk;

import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkSelection;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.NodeFigure;
import io.github.sudoitir.rahdar.kernel.core.CentralMapperConfig;
import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Bulk run and item rows → their views (ADR-0014). The JSON columns are parsed by the caller. */
@Mapper(config = CentralMapperConfig.class, imports = ResourceIds.class)
public interface BulkRunMapper {

    /** {@code bulk_run.options}: what changes how the operation acts, part of the plan hash. */
    record RunOptions(boolean disconnectConsumers) {}

    /** {@code bulk_run_item.estimate}: the preview's per-node figures and its warning. */
    record ItemEstimate(List<NodeFigure> nodes, String warning) {}

    @Mapping(target = "total", source = "run.totalItems")
    @Mapping(target = "cap", source = "cap")
    @Mapping(
            target = "overCap",
            expression =
                    "java(run.getOperation().destructive() && run.getEstimate() != null && run.getEstimate() > cap)")
    @Mapping(target = "selection", source = "chosen")
    @Mapping(target = "disconnectConsumers", source = "options.disconnectConsumers")
    BulkRunView toView(BulkRunEntity run, long cap, BulkSelection chosen, RunOptions options);

    @Mapping(target = "subscription", source = "subscriptionId")
    @Mapping(target = "kind", expression = "java(ResourceIds.kindOf(subscriptionId))")
    @Mapping(target = "nodes", source = "figures.nodes")
    @Mapping(target = "warning", source = "figures.warning")
    @Mapping(target = "outcome", source = "nodeOutcomes")
    BulkItemView toView(
            BulkRunItemEntity item,
            String subscriptionId,
            KindName kindLabel,
            ItemEstimate figures,
            List<NodeOutcome> nodeOutcomes);
}
