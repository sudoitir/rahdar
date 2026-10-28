package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.PluginMetrics;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeTierCompleted;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Plugin metrics end to end (ADR-0113), with a real plugin jar: its source is sampled on tier B
 * into {@code metric_sample} and Prometheus, a threshold rule on it fires and resolves, its
 * declared rule is seeded once and never recreated, and nothing is sampled once it stops.
 */
class PluginMetricsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PluginMetrics pluginMetrics;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    AlertFiringRepository firings;

    @Autowired
    JdbcTemplate jdbc;

    private final List<PluginRuntime> runtimes = new ArrayList<>();
    private UUID clusterId;
    private String id;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        id = "acme-meter-" + Math.abs(new SecureRandom().nextInt());
    }

    @AfterEach
    void tearDown() {
        runtimes.forEach(PluginRuntime::close);
        if (id != null) {
            registry.remove(id);
            System.clearProperty(id + ".value");
        }
        clusters.deleteById(clusterId);
    }

    @Test
    void aSourceIsSampledStoredExportedAndAlertedOn() throws Exception {
        System.setProperty(id + ".value", "9");
        activate(jar(id, "1.0.0", true, "0"));

        List<AlertRuleEntity> seeded = pluginRules();
        assertThat(seeded).singleElement().satisfies(r -> {
            assertThat(r.getName()).isEqualTo("Many edits");
            assertThat(r.getThreshold()).isEqualTo(5.0);
        });

        tierB();

        assertThat(jdbc.queryForObject(
                        "SELECT value FROM metric_sample WHERE cluster_id = ? AND subject_kind = 'rahdar.plugin'"
                                + " AND metric = ? AND subject_path = 'daily' AND node_id IS NULL",
                        Double.class,
                        clusterId,
                        id + ":edits"))
                .isEqualTo(9.0);
        assertThat(meters.get("rahdar.plugin.metric")
                        .tags(
                                "plugin",
                                id,
                                "metric",
                                id + ":edits",
                                "cluster",
                                clusterId.toString(),
                                "subject",
                                "daily")
                        .gauge()
                        .value())
                .isEqualTo(9.0);
        assertThat(firings.findOpenVisible(clusterId, false, true, List.of(UUID.randomUUID())))
                .singleElement()
                .satisfies(f -> assertThat(f.getSubjectKey()).isEqualTo("note:daily"));

        System.setProperty(id + ".value", "1");
        tierB();
        assertThat(firings.findOpenVisible(clusterId, false, true, List.of(UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void aSeededRuleIsCreatedOnceAndADeletedOneStaysDeleted() throws Exception {
        PluginRuntime v1 = activate(jar(id, "1.0.0", true, "0"));
        activate(jar(id, "2.0.0", true, "0"));
        v1.close();
        runtimes.remove(v1);
        assertThat(pluginRules()).hasSize(1);

        rules.deleteAll(pluginRules());
        activate(jar(id, "3.0.0", true, "0"));
        assertThat(pluginRules()).isEmpty();
    }

    @Test
    void aStoppedPluginIsNoLongerSampledAndItsRulesHaveNoSource() throws Exception {
        System.setProperty(id + ".value", "9");
        PluginRuntime runtime = activate(jar(id, "1.0.0", true, "0"));
        tierB();
        runtime.close();
        runtimes.remove(runtime);
        registry.remove(id);

        tierB();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM metric_sample WHERE cluster_id = ? AND metric = ?",
                        Integer.class,
                        clusterId,
                        id + ":edits"))
                .isEqualTo(1);
        assertThat(meters.find("rahdar.plugin.metric").tag("plugin", id).gauges())
                .allSatisfy(g -> assertThat(g.getId().getTag("subject")).isNull());
        assertThat(pluginMetrics.declared(id + ":edits")).isEmpty();
        assertThat(firings.findOpenVisible(clusterId, false, true, List.of(UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void aSlowSourceIsSkippedWithoutHoldingUpTheOthers() throws Exception {
        System.setProperty(id + ".value", "4");
        activate(jar(id, "1.0.0", true, "10000"));

        long started = System.nanoTime();
        tierB();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(8));
        assertThat(pluginMetrics.latest(clusterId, id + ":edits")).containsEntry("daily", 4.0);
        assertThat(pluginMetrics.latest(clusterId, id + ":slow")).isEmpty();
    }

    @Test
    void aSourceForAnUndeclaredMetricRefusesActivation() throws Exception {
        Path jar = jar(id, "1.0.0", false, "0");
        assertThatThrownBy(() -> activate(jar)).hasStackTraceContaining(id + ":edits");
    }

    private void tierB() {
        events.publishEvent(new ScrapeTierCompleted(clusterId, ScrapeTierCompleted.Tier.B));
    }

    private List<AlertRuleEntity> pluginRules() {
        return rules.findVisible(clusterId, false).stream()
                .filter(r -> (id + ":edits").equals(r.getMetric()))
                .toList();
    }

    private PluginRuntime activate(Path jar) throws Exception {
        PluginDescriptor descriptor;
        try (JarFile file = new JarFile(jar.toFile())) {
            descriptor = descriptorParser.parse(file.getInputStream(file.getEntry("META-INF/rahdar/plugin.json"))
                    .readAllBytes());
        }
        PluginRuntime runtime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        runtimes.add(runtime);
        registry.set(id, new PluginRuntimeRegistry.Active(runtime));
        return runtime;
    }

    /**
     * A plugin with a source for {@code <id>:edits} (the value of a system property, subject
     * {@code daily}) and one for {@code <id>:slow} that sleeps {@code slowMillis}.
     */
    private static Path jar(String id, String version, boolean declare, String slowMillis) throws Exception {
        List<Map<String, String>> metrics = declare
                ? List.of(
                        Map.of(
                                "name", id + ":edits",
                                "description", "Edits",
                                "unit", "count",
                                "subject", "note",
                                "permission", id + ":stats"),
                        Map.of(
                                "name", id + ":slow",
                                "description", "Slow",
                                "unit", "ms",
                                "subject", "note",
                                "permission", id + ":stats"))
                : List.of();
        return new PluginJarBuilder(id)
                .descriptorField("version", version)
                .descriptorField("basePackage", "com.acme.meter")
                .descriptorField("configuration", "com.acme.meter.PluginConfig")
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", id + ":stats", "description", "Stats", "scope", "cluster")))
                .descriptorField("metrics", metrics)
                .descriptorField(
                        "alertRules",
                        declare
                                ? List.of(Map.of(
                                        "key", "many-edits",
                                        "name", "Many edits",
                                        "metric", id + ":edits",
                                        "comparator", "GT",
                                        "threshold", 5,
                                        "forSeconds", 0,
                                        "severity", "WARNING"))
                                : List.of())
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """)
                .source("com.acme.meter.PluginConfig", """
                        package com.acme.meter;
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        @ComponentScan(basePackages = "com.acme.meter")
                        public class PluginConfig {}
                        """)
                .source("com.acme.meter.Edits", """
                        package com.acme.meter;
                        import io.github.sudoitir.rahdar.platform.scrape.PluginMetricSource;
                        import java.util.Map;
                        import java.util.UUID;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Edits implements PluginMetricSource {
                            public String metric() { return "%s:edits"; }
                            public Map<String, Double> sample(UUID clusterId) {
                                return Map.of("daily", Double.parseDouble(System.getProperty("%s.value", "0")));
                            }
                        }
                        """.formatted(id, id))
                .source("com.acme.meter.Slow", """
                        package com.acme.meter;
                        import io.github.sudoitir.rahdar.platform.scrape.PluginMetricSource;
                        import java.util.Map;
                        import java.util.UUID;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Slow implements PluginMetricSource {
                            public String metric() { return "%s:slow"; }
                            public Map<String, Double> sample(UUID clusterId) {
                                try { Thread.sleep(%s); } catch (InterruptedException e) { return Map.of(); }
                                return Map.of("daily", 1.0);
                            }
                        }
                        """.formatted(id, slowMillis))
                .build();
    }
}
