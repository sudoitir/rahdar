package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.Plane;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The management URLs and AMQP endpoints are normalised the way the driver is given them, and never carry an account. */
class ManagementUrlsTest {

    @Test
    void everyTokenIsKeptWithTheDefaultPortOfItsScheme() {
        assertThat(ManagementUrls.parse(List.of("http://r1, HTTPS://R2\nhttp://r3:15673/", "http://r1:15672")))
                .containsExactly("http://r1:15672", "https://r2:15671", "http://r3:15673");
    }

    @Test
    void aPathPrefixIsKeptWithoutItsTrailingSlash() {
        assertThat(ManagementUrls.parse(List.of("https://proxy.example:8443/rabbit/")))
                .containsExactly("https://proxy.example:8443/rabbit");
    }

    @Test
    void aLongRunOfSlashesCostsOnlyItsLength() {
        String slashes = "/".repeat(200_000);
        List<String> stripped = List.of("http://r1:15672" + slashes);
        List<String> kept = List.of("http://r1:15672" + slashes + "x");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(ManagementUrls.parse(stripped)).containsExactly("http://r1:15672");
            assertThat(ManagementUrls.parse(kept).getFirst()).hasSize("http://r1:15672".length() + 200_001);
        });
    }

    @Test
    void anAddressIsTheEndpointOfTheManagementPlane() {
        assertThat(ManagementUrls.endpoint("https://r2:15671"))
                .isEqualTo(Endpoint.of(Plane.MANAGEMENT, "https", "https://r2:15671"));
        assertThat(ManagementUrls.host("http://rabbit-1:15672/x")).isEqualTo("rabbit-1");
    }

    @Test
    void anAccountInTheUrlIsRefusedWithoutEchoingIt() {
        var values = List.of("http://guest:secret@r1:15672");
        assertThatThrownBy(() -> ManagementUrls.parse(values))
                .hasMessageContaining("must not carry an account")
                .hasMessageNotContaining("secret");
    }

    @Test
    void aSchemeThatIsNotHttpOrAPortOutOfRangeIsRefused() {
        var values = List.of("amqp://r1");
        assertThatThrownBy(() -> ManagementUrls.parse(values)).hasMessageContaining("http(s)://host:port");
        var values2 = List.of("r1:15672");
        assertThatThrownBy(() -> ManagementUrls.parse(values2)).hasMessageContaining("http(s)://host:port");
        var values3 = List.of("http://r1:70000");
        assertThatThrownBy(() -> ManagementUrls.parse(values3)).hasMessageContaining("port from 1 to 65535");
        var values4 = List.of("http://r1:15672/?a=b");
        assertThatThrownBy(() -> ManagementUrls.parse(values4)).hasMessageContaining("query");
        var values5 = List.of("http://exa mple");
        assertThatThrownBy(() -> ManagementUrls.parse(values5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atLeastOneAndAtMostSixteenAreAllowed() {
        var values = List.of(" ");
        assertThatThrownBy(() -> ManagementUrls.parse(values)).hasMessageContaining("At least one");
        assertThatThrownBy(() -> ManagementUrls.parse(null)).hasMessageContaining("At least one");
        List<String> many =
                IntStream.rangeClosed(1, 17).mapToObj(i -> "http://r" + i).toList();
        assertThatThrownBy(() -> ManagementUrls.parse(many)).hasMessageContaining("At most 16");
    }

    @Test
    void amqpEndpointsDefaultTheirPortAndMayBeNone() {
        assertThat(AmqpEndpoints.parse(List.of("amqp://r1, AMQPS://r2:5679", "amqp://r1:5672")))
                .containsExactly("amqp://r1:5672", "amqps://r2:5679");
        assertThat(AmqpEndpoints.parse(List.of("amqps://r3"))).containsExactly("amqps://r3:5671");
        assertThat(AmqpEndpoints.parse(null)).isEmpty();
        assertThat(AmqpEndpoints.parse(List.of())).isEmpty();
    }

    @Test
    void anAmqpEndpointIsHostAndPortOnTheDataPlane() {
        assertThat(AmqpEndpoints.endpoint("amqps://r2:5671")).isEqualTo(Endpoint.of(Plane.DATA, "amqps", "r2:5671"));
        assertThat(AmqpEndpoints.hostAndPort("amqp://r1:5672")).isEqualTo("r1:5672");
    }

    @Test
    void anAmqpEndpointRefusesAnAccountAPathAndAWrongScheme() {
        var values = List.of("amqp://u:p@r1");
        assertThatThrownBy(() -> AmqpEndpoints.parse(values))
                .hasMessageContaining("must not carry an account")
                .hasMessageNotContaining("u:p");
        var values2 = List.of("amqp://r1:5672/vhost");
        assertThatThrownBy(() -> AmqpEndpoints.parse(values2)).hasMessageContaining("without a path");
        var values3 = List.of("http://r1");
        assertThatThrownBy(() -> AmqpEndpoints.parse(values3)).hasMessageContaining("amqp(s)://host:port");
        var values4 = List.of("amqp://r1:0");
        assertThatThrownBy(() -> AmqpEndpoints.parse(values4)).hasMessageContaining("port");
        List<String> many =
                IntStream.rangeClosed(1, 33).mapToObj(i -> "amqp://r" + i).toList();
        assertThatThrownBy(() -> AmqpEndpoints.parse(many)).hasMessageContaining("At most 32");
    }
}
