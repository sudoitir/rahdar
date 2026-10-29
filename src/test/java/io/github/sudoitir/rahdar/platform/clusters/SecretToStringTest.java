package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.security.web.AuthSessionController.ReauthenticateRequest;
import io.github.sudoitir.rahdar.platform.clusters.ClusterSecrets.Credential;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.AccountUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.KafkaCredentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A record that holds a password or a token never prints it, alone or inside the request that carries it. */
class SecretToStringTest {

    private static final String SECRET = "example-password-not-real";

    @Test
    void accountsAndCredentialsPrintWhoTheyAreAndNotTheirSecret() {
        assertThat(new RegisterClusterRequest.Credentials("alice", SECRET).toString())
                .contains("alice")
                .doesNotContain(SECRET);
        assertThat(new KafkaCredentials("alice", SECRET, SECRET).toString())
                .contains("alice")
                .doesNotContain(SECRET);
        assertThat(new AccountUpdate("alice", SECRET).toString())
                .contains("alice")
                .doesNotContain(SECRET);
        assertThat(new Credential("ref", "alice", SECRET).toString())
                .contains("alice")
                .doesNotContain(SECRET);
        assertThat(new ClusterRegistrar.Account("alice", SECRET).toString())
                .contains("alice")
                .doesNotContain(SECRET);
        assertThat(new ReauthenticateRequest(SECRET).toString()).doesNotContain(SECRET);
    }

    @Test
    void aRequestThatCarriesAnAccountDoesNotPrintItEither() {
        KafkaConnectionUpdate update = new KafkaConnectionUpdate(
                "kafka", List.of("k1:9092"), null, null, new KafkaCredentials("alice", SECRET, SECRET), null, null);

        assertThat(update.toString()).doesNotContain(SECRET);
    }
}
