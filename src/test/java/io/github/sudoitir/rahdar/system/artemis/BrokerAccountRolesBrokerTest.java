package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * {@code listUser} against a real broker. CI runs it at both ends of the supported range, so the
 * operation's name, its argument and the shape of its JSON answer are checked on each (ADR-0177).
 */
class BrokerAccountRolesBrokerTest extends ArtemisIntegrationTest {

    private final BrokerAccountRoles accountRoles = new BrokerAccountRoles();

    @Test
    void readsTheRolesOfTheBrokerAccount() {
        BrokerAccountRoles.Read read = accountRoles.read(jolokiaClient(), BROKER_USER);

        assertThat(read.readable()).as(read.unreadableReason()).isTrue();
        assertThat(read.roles()).containsExactly("amq");
    }

    @Test
    void aUserTheBrokerDoesNotKnowHasNoRoles() {
        BrokerAccountRoles.Read read = accountRoles.read(jolokiaClient(), "nobody");

        assertThat(read.readable()).isFalse();
    }
}
