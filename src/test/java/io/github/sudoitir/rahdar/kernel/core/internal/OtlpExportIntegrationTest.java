package io.github.sudoitir.rahdar.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.CorePool;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.MockOtlpServer;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.SignedInSession;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * With the three OTLP exporters switched on and an endpoint set (what the OTEL_* variables map to), metrics, traces and logs reach an OTLP endpoint (telemetry-export spec).
 * One request that touches the database, a broker's Jolokia and its Core port is one trace; a credential in a
 * log, an exception or an attribute is redacted before export; and the ECS JSON log line carries the trace id
 * of the exported span.
 */
@SpringBootTest(
        properties = {
            "management.otlp.metrics.export.enabled=true",
            "management.tracing.export.otlp.enabled=true",
            "management.logging.export.otlp.enabled=true",
            "management.otlp.metrics.export.step=1s",
            "logging.structured.format.console=ecs"
        })
@ExtendWith(OutputCaptureExtension.class)
@Import({OtlpExportIntegrationTest.Probe.class, OtlpExportIntegrationTest.Collectors.class})
class OtlpExportIntegrationTest extends PostgresIntegrationTest {

    private static final String SECRET = "hunter2-Xq9-not-a-real-credential";
    private static final MockOtlpServer OTLP = new MockOtlpServer();
    private static final List<SpanData> SPANS = new CopyOnWriteArrayList<>();
    private static final List<LogRecordData> LOGS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("management.otlp.metrics.export.url", () -> OTLP.endpoint() + "/v1/metrics");
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> OTLP.endpoint() + "/v1/traces");
        registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> OTLP.endpoint() + "/v1/logs");
    }

    @AfterAll
    static void stop() {
        OTLP.close();
    }

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    OpenTelemetry openTelemetry;

    @BeforeEach
    void clear() {
        SPANS.clear();
        LOGS.clear();
    }

    private MockMvc probe() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(new ServerHttpObservationFilter(webContext.getBean(ObservationRegistry.class)))
                .apply(springSecurity())
                .build();
    }

    private void probeRequest() throws Exception {
        RahdarPrincipal admin = new RahdarPrincipal(
                null,
                "otlp-test",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))),
                false);
        probe().perform(get("/api/v1/otel-probe")
                        .with(SignedInSession.authentication(UsernamePasswordAuthenticationToken.authenticated(
                                admin, null, admin.getAuthorities()))))
                .andExpect(status().isOk());
    }

    private void flush() {
        OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
        sdk.getSdkTracerProvider().forceFlush().join(20, TimeUnit.SECONDS);
        sdk.getSdkLoggerProvider().forceFlush().join(20, TimeUnit.SECONDS);
    }

    @Test
    void metricsTracesAndLogsAllArrive() throws Exception {
        probeRequest();
        flush();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(OTLP.bodies("/v1/metrics")).isNotEmpty();
            assertThat(OTLP.bodies("/v1/traces")).isNotEmpty();
            assertThat(OTLP.bodies("/v1/logs")).isNotEmpty();
        });
        // The bodies are searchable text only if they are not compressed: this also guards that.
        assertThat(OTLP.bodyContains("/v1/traces", "jolokia")).isTrue();
        assertThat(OTLP.bodyContains("/v1/logs", "otel-probe-log")).isTrue();
        assertThat(OTLP.bodyContains("/v1/metrics", "jvm.memory.used")).isTrue();
    }

    @Test
    void oneRequestIsOneTraceThroughJolokiaCoreAndJdbc() throws Exception {
        probeRequest();
        flush();

        SpanData request = SPANS.stream()
                .filter(s -> s.getName().startsWith("http get"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no request span in "
                        + SPANS.stream().map(SpanData::getName).toList()));
        List<SpanData> trace = SPANS.stream()
                .filter(s -> s.getTraceId().equals(request.getTraceId()))
                .toList();
        List<String> names = trace.stream().map(SpanData::getName).toList();

        assertThat(names).contains("jolokia", "core borrow");
        assertThat(trace.stream()
                        .filter(s -> s.getAttributes().asMap().keySet().stream()
                                .anyMatch(k -> k.getKey().startsWith("jdbc."))))
                .as("JDBC spans in %s", names)
                .isNotEmpty();
        SpanData jolokia = trace.stream()
                .filter(s -> s.getName().equals("jolokia"))
                .findFirst()
                .orElseThrow();
        String node = ArtemisIntegrationTest.jolokiaUrl().replaceFirst("^http://([^/]+)/.*$", "$1");
        assertThat(jolokia.getAttributes().asMap()).containsValue(node);
    }

    @Test
    void aCredentialNeverReachesAnExportedSpanOrLog() throws Exception {
        probeRequest();
        flush();
        await().atMost(Duration.ofSeconds(20)).until(() -> OTLP.bodyContains("/v1/logs", "otel-probe-log"));

        assertThat(SPANS).isNotEmpty();
        assertThat(LOGS).isNotEmpty();
        assertThat(SPANS.toString()).doesNotContain(SECRET);
        assertThat(LOGS.stream()
                        .map(l -> l.getBodyValue() + " " + l.getAttributes())
                        .toList()
                        .toString())
                .doesNotContain(SECRET);
        assertThat(OTLP.anyBodyContains(SECRET)).as("raw OTLP bodies").isFalse();
        // The redaction happened, it was not just never logged: the masked form is what arrived.
        assertThat(LOGS.stream()
                        .map(l -> String.valueOf(l.getBodyValue()))
                        .toList()
                        .toString())
                .contains("[redacted]");
        SpanData secret = SPANS.stream()
                .filter(s -> s.getName().equals("test probe secret"))
                .findFirst()
                .orElseThrow();
        assertThat(secret.getAttributes().asMap().keySet().stream().map(k -> k.getKey()))
                .doesNotContain("message.content");
        assertThat(secret.getEvents().toString()).doesNotContain(SECRET).contains("[redacted]");
    }

    @Test
    void theJsonLogLineCarriesTheExportedTraceId(CapturedOutput output) throws Exception {
        probeRequest();
        flush();

        String line = output.getAll()
                .lines()
                .filter(l -> l.contains("otel-probe-log"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no probe line in the console output"));
        // Valid JSON, and its trace id is the field Boot's ECS format writes from the tracing MDC.
        assertThat(JsonMapper.builder().build().readTree(line).isObject()).isTrue();
        Matcher traceField = Pattern.compile("\"traceId\":\"([0-9a-f]{32})\"").matcher(line);
        assertThat(traceField.find()).as("a traceId in the line").isTrue();
        String traceId = traceField.group(1);
        SpanData request = SPANS.stream()
                .filter(s -> s.getName().startsWith("http get"))
                .findFirst()
                .orElseThrow();

        assertThat(traceId).isEqualTo(request.getTraceId());
        assertThat(line).doesNotContain(SECRET);
    }

    @RestController
    static class Probe {

        private static final Logger log = LoggerFactory.getLogger("otel-probe");

        private final BrokerGateway clients;
        private final CorePool corePool;
        private final JdbcTemplate jdbc;
        private final ObservationRegistry observations;

        Probe(BrokerGateway clients, CorePool corePool, JdbcTemplate jdbc, ObservationRegistry observations) {
            this.clients = clients;
            this.corePool = corePool;
            this.jdbc = jdbc;
            this.observations = observations;
        }

        @GetMapping("/otel-probe")
        String probe() throws Exception {
            UUID cluster = UUID.randomUUID();
            jdbc.queryForObject("select 1", Integer.class);
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

            Observation secret = Observation.createNotStarted("test.probe.secret", observations)
                    .contextualName("test probe secret")
                    .highCardinalityKeyValue("target", "http://admin:" + SECRET + "@broker:8161/console/jolokia")
                    .highCardinalityKeyValue("api.token", SECRET)
                    .highCardinalityKeyValue("message.content", "payload " + SECRET);
            secret.start();
            secret.error(new IllegalStateException("connect failed, password=" + SECRET));
            secret.stop();

            log.error("otel-probe-log password={}", SECRET, new IllegalStateException("token=" + SECRET));
            return "ok";
        }
    }

    /** Keeps what the SDK exports, next to the OTLP exporter, so a test can read spans and logs structurally. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Collectors {

        @Bean
        SpanExporter collectingSpanExporter() {
            return new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    SPANS.addAll(spans);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }

        @Bean
        LogRecordExporter collectingLogExporter() {
            return new LogRecordExporter() {
                @Override
                public CompletableResultCode export(Collection<LogRecordData> logs) {
                    LOGS.addAll(logs);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }
    }
}
