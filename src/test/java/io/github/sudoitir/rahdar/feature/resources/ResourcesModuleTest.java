package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Resources starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class ResourcesModuleTest extends ModuleIntegrationTest {

    /** Queue lifecycle resets counters through the system module, which this test does not start. */
    @MockitoBean
    QueueCounters counters;
}
