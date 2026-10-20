package io.github.sudoitir.rahdar.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.jobs.JobRunner;
import io.github.sudoitir.rahdar.kernel.jobs.ScheduledJob;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.CorePool;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.WebApplicationContext;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every metric a shipped Grafana panel or Prometheus rule queries is a series name that
 * {@code /actuator/prometheus} serves after the app has done the work that creates it, so renaming
 * a meter fails here and not on an operator's empty panel (telemetry-export spec).
 */
class ObservabilityAssetsIntegrationTest extends PostgresIntegrationTest {

    private static final Path ASSETS = Path.of("deploy/observability");
    private static final String ASSETS_JOB = "assets-test";
    private static final Pattern METRIC = Pattern.compile("\\b(?:rahdar|hikaricp|jvm)_\\w+");

    /** A job that runs often, so the {@code rahdar.job} timer the dashboard queries exists. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AssetsJob {
        @Bean
        ScheduledJob assetsTestJob() {
            return ScheduledJob.fixedDelay(
                    ASSETS_JOB, "assets-test", ScheduledJob.Scope.INSTANCE, () -> Duration.ofMillis(50), () -> {});
        }
    }

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    BrokerGateway clients;

    @Autowired
    CorePool corePool;

    @Autowired
    JobRunner jobs;

    @Autowired
    MeterRegistry meters;

    @Autowired
    NodeCallLimiter limiter;

    @Test
    void everyMetricTheDashboardAndRulesQueryIsServed() throws Exception {
        exerciseTheMeters();
        String scrape = webContextScrape();
        Set<String> served = new TreeSet<>();
        scrape.lines().filter(l -> !l.startsWith("#")).forEach(l -> served.add(l.split("[{ ]", 2)[0]));

        List<String> queries = new ArrayList<>();
        collectExprs(
                JsonMapper.builder().build().readValue(ASSETS.resolve("grafana/rahdar-overview.json"), Object.class),
                queries);
        collectExprs(new Yaml().load(Files.readString(ASSETS.resolve("prometheus/rahdar-alerts.yml"))), queries);
        Set<String> referenced = new TreeSet<>();
        queries.forEach(q -> {
            Matcher m = METRIC.matcher(q);
            while (m.find()) {
                referenced.add(m.group());
            }
        });

        assertThat(queries).as("expr fields found").hasSizeGreaterThan(10);
        assertThat(referenced).as("metric names the assets query").isNotEmpty();
        assertThat(referenced)
                .as("queried names absent from /actuator/prometheus")
                .isSubsetOf(served);
    }

    private void exerciseTheMeters() throws Exception {
        UUID cluster = UUID.randomUUID();
        try (ProbeSession session = clients.probe(BrokerConnectionSettings.basicAuth(
                cluster, ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD))) {
            session.node(ArtemisIntegrationTest.jolokiaUrl()).describe();
        }
        corePool.borrow(
                        cluster,
                        ArtemisIntegrationTest.coreUrl(),
                        new CoreConnectionSettings(
                                cluster,
                                ArtemisIntegrationTest.BROKER_USER,
                                ArtemisIntegrationTest.BROKER_PASSWORD,
                                null,
                                true))
                .close();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> meters.find("rahdar.job").tag("job.id", ASSETS_JOB).timer() != null);
        // A permit that cannot come within the wait is the only way a timeout counter exists. The
        // once-a-second refill is a job, so pausing the jobs lets the bucket stay empty for the wait.
        String node = "http://assets-test:8161/console/jolokia";
        jobs.pause(Duration.ofSeconds(5));
        try {
            limiter.acquire(node, limiter.permitsPerSecond());
            assertThatThrownBy(() -> limiter.acquire(node, 1)).isInstanceOf(BrokerConnectionException.class);
        } finally {
            jobs.resume();
        }
    }

    private String webContextScrape() throws Exception {
        return webAppContextSetup(webContext)
                .build()
                .perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static void collectExprs(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if ("expr".equals(k) && v instanceof String s) {
                    out.add(s);
                } else {
                    collectExprs(v, out);
                }
            });
        } else if (node instanceof Iterable<?> items) {
            items.forEach(i -> collectExprs(i, out));
        }
    }
}
