package io.github.sudoitir.rahdar.feature.setupreview;

import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Setup review starts with its direct dependencies only (ADR-0069, ADR-0106). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class SetupReviewModuleTest extends ModuleIntegrationTest {

    /**
     * The kernel's permission checks walk the scope hierarchy, implemented by the clusters module;
     * mocked as that implementation, as {@code BulkModuleTest} explains.
     */
    @MockitoBean
    ClusterEnvironmentIndex clusterEnvironmentIndex;

    @MockitoBean
    QueueSnapshots queueSnapshots;
}
