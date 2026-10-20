package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.core.CentralMapperConfig;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.VersionGate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.CapabilityView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.LogicalNodeView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeVersionView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.TopologyView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.VersionGateView;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain → browser projections (ADR-0014). Enum-to-{@code String} conversions
 * ({@code SplitBrainStatus}, {@code CapabilityStatus}, {@code Level}) are
 * MapStruct defaults. {@code observedCycle} is intentionally not on
 * {@link NodeEndpointView} — an internal corroboration detail, not for the API. The view's field names
 * are the REST contract's, which names the node's endpoints in Artemis terms until the API is renamed.
 */
@Mapper(config = CentralMapperConfig.class)
public interface ClusterViewMapper {

    @Mapping(target = "versionSupport", expression = "java(BrokerVersion.support(endpoint.version()))")
    @Mapping(target = "artemisNodeId", source = "nativeNodeId")
    @Mapping(target = "jolokiaUrl", source = "managementUrl")
    @Mapping(target = "coreUrl", source = "dataUrl")
    @Mapping(target = "haRole", source = "haDesignation")
    @Mapping(target = "coreUrlManual", source = "dataUrlManual")
    NodeEndpointView endpoint(NodeEndpoint endpoint);

    @Mapping(target = "artemisNodeId", source = "nativeNodeId")
    LogicalNodeView logicalNode(LogicalNode node);

    TopologyView topology(ClusterTopology topology);

    CapabilityView capability(CapabilityAssessment assessment);

    CapabilitiesView capabilities(BrokerCapabilities capabilities, List<VersionGate.Assessment> versionGates);

    @Mapping(target = "brokerXmlSnippet", ignore = true)
    VersionGateView versionGate(VersionGate.Assessment assessment);

    NodeVersionView nodeVersion(VersionGate.NodeVerdict verdict);

    HealthView health(ClusterHealth health);
}
