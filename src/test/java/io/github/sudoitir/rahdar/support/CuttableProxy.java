package io.github.sudoitir.rahdar.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A TCP forwarder to a broker port whose open connections can be cut, as a network blip would cut
 * them, while it keeps accepting new ones: a client that reconnects reaches the broker again through
 * the same address.
 */
public final class CuttableProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String host;
    private final int port;
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    public CuttableProxy(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("cuttable-proxy").start(this::accept);
    }

    /** The proxy's own Core URL. */
    public String coreUrl() {
        return "tcp://127.0.0.1:" + server.getLocalPort();
    }

    /** Closes every open connection; new ones are still accepted. */
    public void cut() {
        for (Socket s : open) {
            closeQuietly(s);
        }
        open.clear();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket broker = new Socket(host, port);
                open.add(client);
                open.add(broker);
                Thread.ofVirtual().start(() -> pipe(client, broker));
                Thread.ofVirtual().start(() -> pipe(broker, client));
            } catch (IOException _) {
                // Closed, or the broker refused: the client sees its connection fail.
            }
        }
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException _) {
            // Cut.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException _) {
            // Already closed.
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        cut();
    }
}
