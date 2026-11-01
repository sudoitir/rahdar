package io.github.sudoitir.rahdar.system.rabbitmq;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Whether anything accepts a TCP connection at an AMQP endpoint, and why not when nothing does. A plain socket, so
 * no AMQP client is involved: the check only needs to know the data plane can be reached at the address it was given.
 */
@FunctionalInterface
interface AmqpProbe {

    /** How long an endpoint gets to accept. */
    Duration CONNECT_WAIT = Duration.ofSeconds(3);

    /** The cause when {@code hostAndPort} did not accept a connection (DNS, REFUSED or TIMEOUT), empty when it did. */
    Optional<String> unreachable(String hostAndPort);

    /** The probe of the application: a socket opened and closed. */
    @Component
    final class Socketed implements AmqpProbe {

        @Override
        public Optional<String> unreachable(String hostAndPort) {
            int colon = hostAndPort.lastIndexOf(':');
            String host = hostAndPort.substring(0, colon).replaceAll("^\\[|]$", "");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, Integer.parseInt(hostAndPort.substring(colon + 1))), (int)
                        CONNECT_WAIT.toMillis());
                return Optional.empty();
            } catch (UnknownHostException _) {
                return Optional.of("DNS");
            } catch (SocketTimeoutException _) {
                return Optional.of("TIMEOUT");
            } catch (ConnectException | NoRouteToHostException _) {
                return Optional.of("REFUSED");
            } catch (IOException | RuntimeException _) {
                return Optional.of("TIMEOUT");
            }
        }
    }
}
