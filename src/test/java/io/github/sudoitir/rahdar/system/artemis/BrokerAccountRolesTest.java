package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.support.FixtureJolokia;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** The account's roles from {@code listUser}, and every way the broker can decline to give them (ADR-0177). */
class BrokerAccountRolesTest {

    private final BrokerAccountRoles accountRoles = new BrokerAccountRoles();

    /** A client that gets the broker search answered, then each given fixture in turn. */
    private ArtemisNode client(String url, String... fixtures) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect()
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body("search-broker.json"), MediaType.APPLICATION_JSON));
        for (String fixture : fixtures) {
            server.expect()
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return server.node();
    }

    private static String body(String fixtureName) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixtureName).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsTheRolesOfTheAccountSorted() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-ok:8161/jolokia", "list-user.json"), "artemis");

        assertThat(read.readable()).isTrue();
        assertThat(read.roles()).containsExactly("amq", "ops");
    }

    @Test
    void aLoginModuleThatCannotListUsersIsAReasonNotAFailure() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-ldap:8161/jolokia", "list-user-unsupported.json"), "artemis");

        assertThat(read.readable()).isFalse();
        assertThat(read.roles()).isEmpty();
        assertThat(read.unreadableReason())
                .isEqualTo("the broker's login module does not support listing users (LDAP and custom modules do not)");
    }

    @Test
    void anAccountWithoutManagementRightsSaysSo() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-denied:8161/jolokia", "list-user-refused.json"), "artemis");

        assertThat(read.unreadableReason()).isEqualTo("this account is not allowed to read the broker's users");
    }

    @Test
    void aUserTheBrokerListsNoRolesForIsUnreadable() {
        BrokerAccountRoles.Read read =
                accountRoles.read(client("http://roles-empty:8161/jolokia", "list-user-empty.json"), "artemis");

        assertThat(read.readable()).isFalse();
        assertThat(read.unreadableReason()).isEqualTo("the broker lists no roles for artemis");
    }

    @Test
    void anAnonymousAccountIsNeverAskedAbout() {
        // listUser("") lists every user on the broker, so a blank name must not reach it.
        ArtemisNode neverCalled = mock(ArtemisNode.class);

        assertThat(accountRoles.read(neverCalled, " ").unreadableReason())
                .isEqualTo("Rahdar connects to the broker without an account");
        assertThat(accountRoles.read(neverCalled, null).readable()).isFalse();
    }
}
