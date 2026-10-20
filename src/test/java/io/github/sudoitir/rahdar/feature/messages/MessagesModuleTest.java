package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.platform.broker.DeadLetterDestinations;
import io.github.sudoitir.rahdar.platform.broker.MessageExpiry;
import io.github.sudoitir.rahdar.support.ModuleIntegrationTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Messages starts with its direct dependencies only (task 5.14). */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class MessagesModuleTest extends ModuleIntegrationTest {

    /** Implemented by a system module, which messages does not depend on. */
    @MockitoBean
    MessageExpiry messageExpiry;

    @MockitoBean
    DeadLetterDestinations deadLetterDestinations;
}
