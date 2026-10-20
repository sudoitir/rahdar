package io.github.sudoitir.rahdar.load;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rahdar under load (ADR-0198): ordinary UI reads, event-stream subscribers that read and that stall,
 * audit exports and SQL console queries. Everything runs at once against a replicated Rahdar, as a busy day does.
 *
 * <p>Exports and SQL are heavy endpoints with a per-user concurrency limit, so their virtual users share one
 * account and are expected to be refused with 429: a refusal is counted, never a failure. Any other status is.
 *
 * <p>Seeded by {@code scripts/load-seed.sh}: {@code users.csv} (the UI accounts), {@code flood.csv} (the
 * account the heavy scenarios share) and {@code clusters.csv} (cluster id, name and a queue name).
 */
public class RahdarLoadSimulation extends Simulation {

    private static final String TOPICS = "topology,health,queues";

    private final HttpProtocolBuilder protocol = http.baseUrl(Params.URL + "/api/v1")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .disableCaching();

    private final FeederBuilder<String> users =
            csv(Params.SEED.resolve("users.csv").toAbsolutePath().toString()).circular();
    private final FeederBuilder<String> flood =
            csv(Params.SEED.resolve("flood.csv").toAbsolutePath().toString()).circular();
    private final FeederBuilder<String> clusters =
            csv(Params.SEED.resolve("clusters.csv").toAbsolutePath().toString()).random();

    /** Fetches the double-submit CSRF cookie, signs in and refreshes the cookie the new session carries. */
    private ChainBuilder signIn() {
        return exec(http("auth.csrf").get("/auth/me").check(status().in(200, 401)))
                .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrf")))
                .exec(http("auth.login")
                        .post("/auth/login")
                        .header("X-XSRF-TOKEN", "#{xsrf}")
                        .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
                        .check(jsonPath("$.status").is("AUTHENTICATED")))
                .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrf")));
    }

    private ChainBuilder uiRead() {
        return exec(http("ui.clusters.list").get("/clusters"))
                .pause(1, 3)
                .exec(http("ui.cluster.get").get("/clusters/#{clusterId}"))
                .exec(http("ui.cluster.topology").get("/clusters/#{clusterId}/topology"))
                .exec(http("ui.cluster.health").get("/clusters/#{clusterId}/health"))
                .pause(1, 3)
                .exec(session -> session.set("page", ThreadLocalRandom.current().nextInt(1, Params.QUEUE_PAGES + 1)))
                .exec(http("ui.queues.page").get("/clusters/#{clusterId}/queues?size=100&page=#{page}"))
                .pause(1, 3)
                .exec(http("ui.queues.search").get("/clusters/#{clusterId}/queues?size=50&q=load.q.1&sort=name"))
                .exec(http("ui.addresses.page").get("/clusters/#{clusterId}/addresses?size=100"))
                .pause(1, 3)
                .exec(http("ui.metrics.cluster").get("/clusters/#{clusterId}/metrics?metric=messageCount"))
                .exec(http("ui.metrics.queue")
                        .get("/clusters/#{clusterId}/metrics?metric=messageCount&resource=artemis.queue%2F#{queue}"))
                .pause(1, 3);
    }

    private ScenarioBuilder uiScenario() {
        return scenario("ui-read")
                .feed(users)
                .exec(signIn())
                .during(Duration.ofSeconds(Params.DURATION))
                .on(feed(clusters).exec(uiRead()));
    }

    private ScenarioBuilder sseScenario() {
        return scenario("sse-fast")
                .feed(users)
                .exec(signIn())
                .feed(clusters)
                .exec(sse("stream.connect")
                        .get("/stream?clusterId=#{clusterId}&topics=" + TOPICS)
                        .await(30)
                        .on(sse.checkMessage("stream.first-frame").check(regex("(?s).+"))))
                .pause(Duration.ofSeconds(Params.DURATION))
                .exec(sse("stream.close").close());
    }

    private ChainBuilder exportOnce() {
        return exec(http("heavy.audit.export")
                .get("/clusters/#{clusterId}/audit/export?format=csv")
                .header("Accept", "text/csv")
                .check(status().saveAs("code"), status().in(200, 429)))
                .exec(session -> {
                    (session.getInt("code") == 429 ? Counters.REFUSED_EXPORT : Counters.ACCEPTED_EXPORT)
                            .incrementAndGet();
                    return session;
                });
    }

    private ScenarioBuilder exportScenario() {
        return scenario("exports")
                .feed(flood)
                .exec(signIn())
                .during(Duration.ofSeconds(Params.DURATION))
                .on(feed(clusters).exec(exportOnce()).pause(1, 2));
    }

    private ChainBuilder sqlOnce() {
        return exec(http("heavy.sql.query")
                        .post("/clusters/#{clusterId}/sql/query")
                        .header("X-XSRF-TOKEN", "#{xsrf}")
                        .body(StringBody("{\"sql\":\"SELECT * FROM \\\"load.q.*\\\" LIMIT 100\",\"tail\":false}"))
                        .check(status().saveAs("code"), status().in(200, 429))
                        .checkIf(session -> session.getInt("code") == 200)
                        .then(jsonPath("$.queryId").saveAs("queryId")))
                .doIfOrElse(session -> session.getInt("code") == 429)
                .then(exec(session -> {
                            Counters.REFUSED_SQL.incrementAndGet();
                            return session;
                        }))
                .orElse(exec(http("heavy.sql.stream")
                                .get("/clusters/#{clusterId}/sql/stream?queryId=#{queryId}")
                                .header("Accept", "text/event-stream")
                                .check(status().is(200), bodyString().saveAs("sqlBody")))
                        // A static query's stream ends, from the server, with `done` or `failed`, so a plain GET reads it
                        // whole. A refusal by the per-user limit is a `failed` frame of problem type
                        // too-many-heavy-requests, not a status.
                        .exec(session -> {
                            String body = session.getString("sqlBody");
                            if (body.matches("(?s).*event:\\s*done.*")) {
                                Counters.ACCEPTED_SQL.incrementAndGet();
                            } else if (body.contains("too-many-heavy-requests")) {
                                Counters.REFUSED_SQL.incrementAndGet();
                            } else {
                                Counters.SQL_ERROR_FRAMES.incrementAndGet();
                            }
                            return session;
                        }));
    }

    private ScenarioBuilder sqlScenario() {
        return scenario("sql")
                .feed(flood)
                .exec(signIn())
                .during(Duration.ofSeconds(Params.DURATION))
                .on(feed(clusters).exec(sqlOnce()).pause(1, 2));
    }

    private static PopulationBuilder inject(ScenarioBuilder scenario, int users, HttpProtocolBuilder protocol) {
        return scenario.injectOpen(rampUsers(users).during(Duration.ofSeconds(Params.RAMP)))
                .protocols(protocol);
    }

    public RahdarLoadSimulation() {
        List<PopulationBuilder> populations = new ArrayList<>();
        if (Params.UI_USERS > 0) {
            populations.add(inject(uiScenario(), Params.UI_USERS, protocol));
        }
        if (Params.SSE_FAST > 0) {
            populations.add(inject(sseScenario(), Params.SSE_FAST, protocol));
        }
        if (Params.EXPORT_USERS > 0) {
            populations.add(inject(exportScenario(), Params.EXPORT_USERS, protocol));
        }
        if (Params.SQL_USERS > 0) {
            populations.add(inject(sqlScenario(), Params.SQL_USERS, protocol));
        }

        setUp(populations).assertions(global().failedRequests().percent().lt(Params.MAX_FAILED_PERCENT));
    }

    @Override
    public void before() {
        if (Params.SSE_STALLED > 0) {
            var logins = readCsv("users.csv").stream()
                    .map(r -> new StalledStreams.Login(r[0], r[1]))
                    .toList();
            var clusterIds = readCsv("clusters.csv").stream().map(r -> r[0]).distinct().toList();
            try {
                StalledStreams.open(Params.SSE_STALLED, logins, clusterIds);
            } catch (Exception e) {
                throw new IllegalStateException("could not open the stalled subscribers", e);
            }
        }
    }

    @Override
    public void after() {
        try {
            Counters.write();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            StalledStreams.closeAll();
        }
    }

    private static List<String[]> readCsv(String file) {
        try {
            return Files.readAllLines(Params.SEED.resolve(file)).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
