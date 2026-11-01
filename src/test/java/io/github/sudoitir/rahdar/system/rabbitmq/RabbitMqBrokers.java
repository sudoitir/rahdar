package io.github.sudoitir.rahdar.system.rabbitmq;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A real RabbitMQ broker for the tests that run against one: the management API with the shovel, federation, event
 * exchange and stream plugins on, an administrator account, and the calls the tests make to arrange a broker the way a
 * cluster in use looks (queues, bindings, messages, a consumer).
 */
final class RabbitMqBrokers implements AutoCloseable {

    static final String IMAGE = System.getProperty("rabbitmq.image", "rabbitmq:4.4.0-management");
    static final String USER = "rahdar";
    static final String PASSWORD = "rahdar-secret";

    private static final int AMQP = 5672;
    private static final int MANAGEMENT = 15672;
    private static final String PLUGINS = "[rabbitmq_management,rabbitmq_shovel,rabbitmq_shovel_management,"
            + "rabbitmq_federation,rabbitmq_federation_management,rabbitmq_event_exchange,"
            + "rabbitmq_stream,rabbitmq_stream_management].";

    /** HTTP/1.1 only: the management API answers an upgrade request with a reset. */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final GenericContainer<?> container;

    RabbitMqBrokers() {
        container = new GenericContainer<>(IMAGE)
                .withEnv("RABBITMQ_DEFAULT_USER", USER)
                .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
                .withCopyToContainer(Transferable.of(PLUGINS), "/etc/rabbitmq/enabled_plugins")
                .withExposedPorts(AMQP, MANAGEMENT)
                .waitingFor(Wait.forHttp("/api/health/checks/virtual-hosts")
                        .forPort(MANAGEMENT)
                        .withBasicCredentials(USER, PASSWORD)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    RabbitMqBrokers start() {
        container.start();
        awaitNodeStatistics();
        return this;
    }

    /** The management API lists a node's applications, and with them its version, only once it has its first statistics. */
    private void awaitNodeStatistics() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> call("GET", "/api/nodes", null).body().contains("\"applications\""));
    }

    String managementUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MANAGEMENT);
    }

    String amqpEndpoint() {
        return "amqp://" + container.getHost() + ":" + container.getMappedPort(AMQP);
    }

    /** A call to the management API: the status and the body. */
    record Answer(int status, String body) {}

    Answer call(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(managementUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header(
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json");
        request.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void require(Answer answer, String what) {
        if (answer.status() >= 300) {
            throw new IllegalStateException(what + " answered " + answer.status() + ": " + answer.body());
        }
    }

    static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    void vhost(String name) {
        require(call("PUT", "/api/vhosts/" + encode(name), "{}"), "create virtual host " + name);
        require(
                call(
                        "PUT",
                        "/api/permissions/" + encode(name) + "/" + USER,
                        "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}"),
                "grant " + USER + " " + name);
    }

    /** Declares a queue of the type ({@code classic}, {@code quorum} or {@code stream}) in the virtual host. */
    void queue(String vhost, String name, String type) {
        require(
                call(
                        "PUT",
                        "/api/queues/" + encode(vhost) + "/" + encode(name),
                        "{\"durable\":true,\"arguments\":{\"x-queue-type\":\"" + type + "\"}}"),
                "declare " + type + " queue " + name);
    }

    void exchange(String vhost, String name, String type) {
        require(
                call(
                        "PUT",
                        "/api/exchanges/" + encode(vhost) + "/" + encode(name),
                        "{\"type\":\"" + type + "\",\"durable\":true}"),
                "declare exchange " + name);
    }

    void bind(String vhost, String exchange, String queue, String routingKey) {
        require(
                call(
                        "POST",
                        "/api/bindings/" + encode(vhost) + "/e/" + encode(exchange) + "/q/" + encode(queue),
                        "{\"routing_key\":\"" + routingKey + "\"}"),
                "bind " + queue);
    }

    /** Publishes through the default exchange to the queue, and returns once the broker has confirmed every message. */
    void publish(String vhost, String queue, int count) throws IOException, TimeoutException, InterruptedException {
        try (Connection connection = connect(vhost);
                var channel = connection.createChannel()) {
            channel.confirmSelect();
            for (int i = 0; i < count; i++) {
                channel.basicPublish("", queue, null, ("message-" + i).getBytes(StandardCharsets.UTF_8));
            }
            channel.waitForConfirmsOrDie(30_000);
        }
    }

    /** The messages ready in the queue, counted by the broker on a passive declare: exact, and at once. */
    int messageCount(String vhost, String queue) throws IOException, TimeoutException {
        try (Connection connection = connect(vhost);
                var channel = connection.createChannel()) {
            return channel.queueDeclarePassive(queue).getMessageCount();
        }
    }

    /**
     * Waits until the management API's figures for the queue show its {@code messages} ready: the broker publishes its
     * statistics on a timer, and the lists Rahdar reads are built from them.
     */
    void awaitStatistics(String vhost, String queue, int messages) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> call("GET", "/api/queues/" + encode(vhost) + "/" + encode(queue), null)
                        .body()
                        .contains("\"messages_ready\":" + messages));
    }

    /** Waits until the management API counts the {@code messages} of the stream, which is where a read finds its bounds. */
    void awaitStreamStatistics(String vhost, String stream, int messages) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> call("GET", "/api/queues/" + encode(vhost) + "/" + encode(stream), null)
                        .body()
                        .contains("\"messages\":" + messages));
    }

    /** An open AMQP connection of the administrator to the virtual host. */
    Connection connect(String vhost) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(container.getHost());
        factory.setPort(container.getMappedPort(AMQP));
        factory.setUsername(USER);
        factory.setPassword(PASSWORD);
        factory.setVirtualHost(vhost);
        return factory.newConnection("rahdar-test");
    }

    @Override
    public void close() {
        container.stop();
    }
}
