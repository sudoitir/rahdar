package io.github.sudoitir.rahdar.system.kafka;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Whether anything accepts a TCP connection at a bootstrap server. A check that fails after its bootstrap server
 * accepted one means the brokers advertise addresses Rahdar cannot reach, the commonest misconfiguration of a
 * containerised broker; one that fails without it means the bootstrap server itself cannot be reached. A plain
 * socket, so no broker client is involved.
 */
@FunctionalInterface
interface BootstrapProbe {

    /** How long a server gets to accept. */
    Duration CONNECT_WAIT = Duration.ofSeconds(3);

    /** Whether the {@code host:port} accepted a connection. */
    boolean accepts(String hostAndPort);

    /** The probe of the application: a socket opened and closed. */
    @Component
    final class Socketed implements BootstrapProbe {

        @Override
        public boolean accepts(String hostAndPort) {
            int colon = hostAndPort.lastIndexOf(':');
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(
                                hostAndPort.substring(0, colon), Integer.parseInt(hostAndPort.substring(colon + 1))),
                        (int) CONNECT_WAIT.toMillis());
                return true;
            } catch (IOException | RuntimeException _) {
                return false;
            }
        }
    }
}
