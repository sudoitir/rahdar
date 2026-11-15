package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class DestinationPolicyTest {

    private final DestinationPolicy policy = new DestinationPolicy();

    @Test
    void onlyHttpsIsAccepted() {
        assertThatThrownBy(() -> policy.requirePublic("url", "http://hooks.example/x"))
                .hasMessageStartingWith("url:")
                .hasMessageContaining("https");
        assertThatThrownBy(() -> policy.requirePublic("url", "ftp://hooks.example/x"))
                .hasMessageContaining("https");
        assertThatCode(() -> policy.requirePublic("url", "https://hooks.example/x"))
                .doesNotThrowAnyException();
    }

    @Test
    void aUrlIsRequiredAndMustNameAHost() {
        assertThatThrownBy(() -> policy.requirePublic("url", " ")).hasMessageStartingWith("url:");
        assertThatThrownBy(() -> policy.requirePublic("url", "https:///path")).hasMessageContaining("host");
        assertThatThrownBy(() -> policy.requirePublic("url", "not a url")).hasMessageContaining("not a valid URL");
    }

    @Test
    void credentialsInTheUrlAreRefused() {
        assertThatThrownBy(() -> policy.requirePublic("url", "https://user:pass@hooks.example/x"))
                .hasMessageContaining("credentials");
    }

    /** Every address family an internal service, a container network or a metadata service answers on. */
    @Test
    void internalAddressesAreRefused() {
        for (String url : new String[] {
            "https://127.0.0.1/x", // loopback
            "https://[::1]/x", // loopback, v6
            "https://10.1.2.3/x", // RFC 1918
            "https://192.168.0.1/x", // RFC 1918
            "https://172.16.5.4/x", // RFC 1918
            "https://169.254.169.254/latest/meta-data", // link-local, cloud metadata
            "https://100.64.0.1/x", // RFC 6598 carrier-grade NAT
            "https://[fc00::1]/x", // RFC 4193 unique local
            "https://[fd12:3456::1]/x", // RFC 4193 unique local
            "https://0.0.0.0/x" // any local
        }) {
            assertThatThrownBy(() -> policy.requirePublic("url", url)).as(url).hasMessageContaining("internal address");
        }
    }

    @Test
    void aPublicAddressIsAccepted() throws Exception {
        assertThatCode(() -> policy.requirePublic("url", "https://1.1.1.1/hook"))
                .doesNotThrowAnyException();
        assertThat(DestinationPolicy.isInternal(InetAddress.getByName("8.8.8.8")))
                .isFalse();
    }

    /** A name that does not resolve here is left to the connection, where an outage stays a retryable failure. */
    @Test
    void aNameThatDoesNotResolveIsLeftToTheConnection() {
        assertThatCode(() -> policy.requirePublic("url", "https://rahdar.invalid/hook"))
                .doesNotThrowAnyException();
    }
}
