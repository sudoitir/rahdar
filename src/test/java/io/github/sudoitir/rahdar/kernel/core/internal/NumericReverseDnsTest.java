package io.github.sudoitir.rahdar.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.spi.InetAddressResolverProvider;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * A reverse lookup answers at once with the address's text. {@link InetAddress#getHostName()} would have given
 * the same text, but only after a PTR query that times out where the subnets have no reverse zone.
 */
class NumericReverseDnsTest {

    @Test
    void aReverseLookupIsTheAddressTextAndNotAQuery() throws Exception {
        InetAddress broker = InetAddress.getByAddress(new byte[] {10, (byte) 100, 8, 81});

        long started = System.nanoTime();
        String name = broker.getHostName();

        assertThat(name).isEqualTo("10.100.8.81");
        assertThat(System.nanoTime() - started)
                .isLessThan(java.time.Duration.ofMillis(500).toNanos());
    }

    @Test
    void anIpv6AddressIsBracketedAsTheJdkWritesIt() {
        byte[] addr = new byte[16];
        addr[15] = 1;

        assertThat(NumericReverseDns.literal(addr)).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void theProcessResolvesThroughThisProvider() {
        assertThat(ServiceLoader.load(InetAddressResolverProvider.class))
                .singleElement()
                .isInstanceOfSatisfying(
                        NumericReverseDns.class,
                        provider -> assertThat(provider.name()).isNotBlank());
    }

    @Test
    void aForwardLookupStillResolvesNames() throws Exception {
        // The system resolver keeps answering names: TLS host-name checks and bootstrap servers depend on it.
        assertThat(InetAddress.getByName("localhost").isLoopbackAddress()).isTrue();
    }
}
