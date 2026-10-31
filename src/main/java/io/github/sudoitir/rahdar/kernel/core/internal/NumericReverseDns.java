package io.github.sudoitir.rahdar.kernel.core.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Answers a reverse lookup with the address's own text instead of asking a name server for a PTR record.
 *
 * <p>Java's {@link InetAddress#getHostName()} asks the name service for a name, and when there is none it
 * returns the address text anyway — after the query has timed out. In a network whose subnets have no reverse
 * zone those queries time out slowly and repeatedly: measured at 5–10 s each on a broker network, and Kafka
 * asks for every broker as it builds the channel. Kafka charges that wait to
 * {@code socket.connection.setup.timeout.ms}, so a SASL connection is torn down before it reads one API
 * version, which reads as an unreachable broker although the TCP connect took milliseconds.
 *
 * <p>So the answer this provider gives is exactly what the lookup would have produced on timeout, without the
 * timeout. Forward lookups still go to the system resolver: matching a broker's certificate to the name it was
 * reached by, resolving bootstrap servers and reaching Postgres all depend on those, and nothing depends on a
 * PTR record — Kafka passes the result to SASL only for GSSAPI, which takes its principal from configuration.
 *
 * <p>Registered as a service in {@code META-INF/services}, so the JDK loads it once for the whole process.
 */
public final class NumericReverseDns extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver system = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                return system.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) {
                return literal(addr);
            }
        };
    }

    /** The text of an address, as {@link InetAddress#getHostAddress()} writes it. */
    static String literal(byte[] addr) {
        try {
            return InetAddress.getByAddress(addr).getHostAddress();
        } catch (UnknownHostException e) {
            // Every byte array of four or sixteen bytes is an address; this cannot happen.
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public String name() {
        return "rahdar-numeric-reverse-dns";
    }
}
