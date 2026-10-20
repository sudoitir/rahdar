package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.core.CentralMapperConfig;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** {@link BrokerNodeEntity} → {@link NodeEndpoint} (ADR-0014). */
@Mapper(config = CentralMapperConfig.class)
public interface BrokerNodeMapper {

    @Mapping(target = "active", expression = "java(Boolean.TRUE.equals(entity.getActive()))")
    @Mapping(target = "manageable", expression = "java(entity.getManagementUrl() != null)")
    NodeEndpoint toEndpoint(ClusterNode entity);

    List<NodeEndpoint> toEndpoints(List<? extends ClusterNode> nodes);
}
