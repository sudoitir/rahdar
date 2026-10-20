package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Routing starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class RoutingModuleTest extends ModuleIntegrationTest {

    /** Queue lifecycle, a direct dependency, locates queues through the scrape module. */
    @MockitoBean
    QueueLocator queueLocator;

    /** And drops a destroyed queue's snapshot rows through it, so the listing loses it at once. */
    @MockitoBean
    QueueSnapshotUpsert queueSnapshots;
}
