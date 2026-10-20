package io.github.sudoitir.rahdar.support.wire;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A forwarding HTTP proxy in front of one test broker's console. It records each request exactly as
 * Rahdar sent it (method, path, the headers that describe it, and the body) and forwards it
 * unchanged.
 */
final class RecordingProxy {

    /** Headers that describe a request; the rest vary by connection and say nothing about its shape. */
    private static final List<String> RECORDED =
            List.of("Accept", "Authorization", "Connection", "Content-Type", "Upgrade", "User-Agent");

    private static final Set<String> NOT_FORWARDED =
            Set.of("host", "content-length", "connection", "upgrade", "http2-settings", "expect");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final String target;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private RecordingProxy(String target) throws IOException {
        this.target = target;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::forward);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    static RecordingProxy start(String target) {
        try {
            return new RecordingProxy(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The broker's Core consumers, read directly so the read is not recorded. */
    List<JsonNode> consumers() {
        try {
            String broker = post("{\"type\":\"search\",\"mbean\":\"org.apache.activemq.artemis:broker=*\"}")
                    .get("value")
                    .get(0)
                    .asString();
            JsonNode value = post("{\"type\":\"exec\",\"mbean\":" + JSON.writeValueAsString(broker)
                            + ",\"operation\":\"listAllConsumersAsJSON()\"}")
                    .get("value");
            List<JsonNode> out = new ArrayList<>();
            JSON.readTree(value.asString()).forEach(out::add);
            return out;
        } catch (IOException | RuntimeException _) {
            return List.of();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private JsonNode post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(target + "/console/jolokia"))
                .header("Content-Type", "application/json")
                .header(
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString("artemis:artemis".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return JSON.readTree(
                client.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    String rewrite(String directUrl) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + directUrl.substring(target.length());
    }

    void stop() {
        server.stop(0);
    }

    private void forward(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            record(exchange, body);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target + exchange.getRequestURI()))
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!NOT_FORWARDED.contains(name.toLowerCase())) {
                    values.forEach(v -> request.header(name, v));
                }
            });
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.headers().map().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("content-length")
                        && !name.equalsIgnoreCase("transfer-encoding")
                        && !name.startsWith(":")) {
                    exchange.getResponseHeaders().put(name, values);
                }
            });
            byte[] out = response.body();
            exchange.sendResponseHeaders(response.statusCode(), out.length == 0 ? -1 : out.length);
            exchange.getResponseBody().write(out);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void record(HttpExchange exchange, byte[] body) {
        StringBuilder line = new StringBuilder()
                .append(exchange.getRequestMethod())
                .append(' ')
                .append(exchange.getRequestURI());
        for (String name : RECORDED) {
            List<String> values = exchange.getRequestHeaders().get(name);
            if (values != null) {
                line.append(" | ").append(name).append(": ").append(String.join(",", values));
            }
        }
        line.append(" | ").append(new String(body, StandardCharsets.UTF_8));
        WireRecorder.record(line.toString());
    }
}
