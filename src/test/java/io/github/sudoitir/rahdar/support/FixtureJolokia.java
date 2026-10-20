package io.github.sudoitir.rahdar.support;

import com.sun.net.httpserver.HttpServer;
import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.ConnectOptions;
import io.github.sudoitir.openbroker.ConnectRequest;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseCreator;

/**
 * A Jolokia agent that answers from fixtures, in order, for tests of code that talks to Artemis
 * through an {@link ArtemisNode}. The expectations read like {@code MockRestServiceServer}'s, and take
 * its response creators ({@code withSuccess}, {@code withStatus}): each fixture answers the next request,
 * the matchers given to {@code andExpect} are checked against it, and {@link #requests()} gives what was
 * sent. The answers go
 * through the real broker SDK driver, so its parsing and error mapping are part of the test.
 */
public final class FixtureJolokia implements AutoCloseable {

    private record Answer(
            List<RequestMatcher> matchers, @Nullable ClientHttpResponse response) {}

    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final List<AssertionError> mismatches = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final List<AutoCloseable> opened = new ArrayList<>();

    public FixtureJolokia() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] sent = exchange.getRequestBody().readAllBytes();
                requests.add(new String(sent, StandardCharsets.UTF_8));
                Answer next = answers.poll();
                if (next == null) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                check(next.matchers(), exchange.getRequestMethod(), sent);
                if (next.response() == null) {
                    // A transport failure: the connection closes before any response, as when nothing listens.
                    return;
                }
                ClientHttpResponse answer = next.response();
                answer.getHeaders()
                        .forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
                byte[] body = answer.getBody().readAllBytes();
                exchange.sendResponseHeaders(answer.getStatusCode().value(), body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    /** The next request is answered by what {@code andRespond} creates. */
    public Expectation expect(RequestMatcher ignored) {
        return new Expectation();
    }

    /** The agent's URL. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/console/jolokia";
    }

    /** An Artemis node reached at this agent, through the broker SDK. */
    public ArtemisNode node() {
        return ArtemisNodes.node(target());
    }

    /** The node as the SDK sees it, for code that takes a {@link BrokerTarget}. */
    public BrokerTarget target() {
        BrokerDrivers drivers = BrokerDrivers.load();
        ClusterConnection connection = drivers.connect(new ConnectRequest(
                "artemis",
                ClusterSpec.ofNodes(List.of(
                        new NodeSpec(NodeId.of(url()), List.of(new Endpoint(Plane.MANAGEMENT, "jolokia", url()))))),
                CredentialSource.none(),
                ConnectOptions.defaults()));
        opened.add(connection);
        opened.add(drivers);
        return new BrokerTarget(connection, NodeId.of(url()));
    }

    /** The request bodies the agent received, in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** Fails with the first {@code andExpect} matcher a request did not meet. */
    public void verify() {
        if (!mismatches.isEmpty()) {
            throw mismatches.getFirst();
        }
    }

    private void check(List<RequestMatcher> matchers, String method, byte[] body) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.valueOf(method), URI.create(url()));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        request.getBody().write(body);
        for (RequestMatcher matcher : matchers) {
            try {
                matcher.match(request);
            } catch (AssertionError e) {
                mismatches.add(e);
            }
        }
    }

    @Override
    public void close() {
        for (AutoCloseable c : opened) {
            try {
                c.close();
            } catch (Exception ignored) {
                // A test helper: nothing to do about a close that fails.
            }
        }
        server.stop(0);
    }

    /**
     * One expected request. The matcher given to {@code expect} names the URL, which this agent does not
     * serve, and is not checked; the ones given to {@code andExpect} are checked against the request.
     */
    public final class Expectation {

        private final List<RequestMatcher> matchers = new ArrayList<>();

        private Expectation() {}

        public Expectation andExpect(RequestMatcher matcher) {
            matchers.add(matcher);
            return this;
        }

        /** Answers with what {@code creator} makes; a creator that throws drops the connection instead. */
        public void andRespond(ResponseCreator creator) {
            ClientHttpResponse response;
            try {
                response = creator.createResponse(null);
            } catch (IOException e) {
                response = null;
            }
            answers.add(new Answer(List.copyOf(matchers), response));
        }
    }
}
