package io.github.sudoitir.rahdar.feature.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginApiContext;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.GrantLoader;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;

/**
 * A plugin reads metric history as a named user (ADR-0154): the answers of the REST API, for the
 * account as it stands at each read, and one not-found answer for every reason a user may not.
 */
class MetricHistoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MetricHistory history;

    @Autowired
    PluginApiContext pluginApi;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    GrantLoader grants;

    private UUID clusterId;
    private UUID nodeId;
    private Instant now;
    private PluginRuntime runtime;
    private String pluginId;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("history-" + UUID.randomUUID(), null, null))
                .getId();
        nodeId = nodes.save(BrokerNodeEntity.fromSeed(
                        clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString()))
                .getId();
        now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        queueSample(now.minusSeconds(600), 100);
        queueSample(now.minusSeconds(570), 400);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
            registry.remove(pluginId);
        }
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
        clusters.deleteById(clusterId);
    }

    @Test
    void aUserWithQueueReadReadsAQueuesDepth() {
        UUID user = userWith(Permissions.CLUSTER_READ, Permissions.QUEUE_READ);

        MetricSeriesResponse series = history.read(user, clusterId, depthQuery(now.minusSeconds(900)));

        assertThat(series.truncated()).isFalse();
        assertThat(series.series()).singleElement().satisfies(s -> {
            assertThat(s.metric()).isEqualTo("openbroker.backlog");
            assertThat(s.points()).isNotEmpty();
        });
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anotherPluginsMetricNeedsItsDeclaredPermission() throws Exception {
        pluginId = "acme-history-" + Math.abs(new SecureRandom().nextInt());
        activatePluginDeclaringAMetric();
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id)
                VALUES (:ts, 7, 'PLUGIN', 'daily', :m, :c)
                """, Map.of("ts", Timestamp.from(now.minusSeconds(300)), "m", pluginId + ":edits", "c", clusterId));
        UUID withIt = userWith(Permissions.CLUSTER_READ, pluginId + ":stats");
        UUID without = userWith(Permissions.CLUSTER_READ);

        MetricSeriesResponse series = history.readPluginMetric(
                withIt, clusterId, pluginId + ":edits", "daily", now.minusSeconds(900), now, null);

        assertThat(series.series()).singleElement().satisfies(s -> {
            assertThat(s.metric()).isEqualTo(pluginId + ":edits");
            assertThat(s.points())
                    .singleElement()
                    .satisfies(p -> assertThat(p.value()).isEqualTo(7.0));
        });
        String metric = pluginId + ":edits";
        Instant from = now.minusSeconds(900);
        assertThatThrownBy(() -> history.readPluginMetric(without, clusterId, metric, "daily", from, now, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("cluster " + clusterId + " does not exist.");
    }

    @Test
    void noGrantAnUnknownClusterAndADisabledUserAnswerAlike() {
        UUID noGrant = userWith();
        UUID disabled = userWith(Permissions.CLUSTER_READ);
        var account = users.findById(disabled).orElseThrow();
        account.setDisabled(true);
        users.save(account);
        UUID reader = userWith(Permissions.CLUSTER_READ);
        UUID otherCluster = UUID.randomUUID();
        var query = depthQuery(now.minusSeconds(900));

        // The reader holds the permission on the one cluster only, so another id is "no such cluster" to them.
        assertThatThrownBy(() -> history.read(noGrant, clusterId, query))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("cluster " + clusterId + " does not exist.");
        assertThatThrownBy(() -> history.read(disabled, clusterId, query))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("cluster " + clusterId + " does not exist.");
        assertThatThrownBy(() -> history.read(null, clusterId, query)).isInstanceOf(NotFoundException.class);
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> history.read(unknown, clusterId, query)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> history.read(reader, otherCluster, query))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("cluster " + otherCluster + " does not exist.");
    }

    @Test
    void aRangeOlderThanRetentionIsClampedAndSaysSo() {
        UUID user = userWith(Permissions.CLUSTER_READ, Permissions.QUEUE_READ);

        MetricSeriesResponse series = history.read(user, clusterId, depthQuery(now.minus(Duration.ofDays(5 * 365))));

        assertThat(series.truncated()).isTrue();
        assertThat(series.from()).isAfter(now.minus(Duration.ofDays(5 * 365)));
    }

    @Test
    void aGrantRemovedBetweenTwoReadsTurnsTheSecondIntoNotFound() {
        UUID user = userWith(Permissions.CLUSTER_READ, Permissions.QUEUE_READ);
        var query = depthQuery(now.minusSeconds(900));
        assertThat(history.read(user, clusterId, query).series()).isNotEmpty();

        OperatorFixture.revokeAll(userRoles, accessChanges, user);

        assertThatThrownBy(() -> history.read(user, clusterId, query)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theBeanIsVisibleInAPluginContext() {
        assertThat(pluginApi.context().getBean(MetricHistory.class)).isSameAs(history);
    }

    private MetricQuery depthQuery(Instant from) {
        return new MetricQuery(List.of("openbroker.backlog"), "QUEUE", "orders", from, now, null, null);
    }

    /** A user holding {@code permissions} on this cluster only; nobody is left signed in. */
    private UUID userWith(String... permissions) {
        UUID id = OperatorFixture.signInOnCluster(
                users, roles, rolePermissions, userRoles, grants, clusterId, permissions);
        SecurityContextHolder.clearContext();
        return id;
    }

    private void queueSample(Instant ts, double value) {
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_type, subject_name, metric, cluster_id, node_id)
                VALUES (:ts, :v, 'QUEUE', 'orders', 'openbroker.backlog', :c, :n)
                """, Map.of("ts", Timestamp.from(ts), "v", value, "c", clusterId, "n", nodeId));
    }

    private void activatePluginDeclaringAMetric() throws Exception {
        Path jar = new PluginJarBuilder(pluginId)
                .descriptorField("basePackage", "com.acme.history")
                .descriptorField("configuration", "com.acme.history.PluginConfig")
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", pluginId + ":stats", "description", "Stats", "scope", "cluster")))
                .descriptorField(
                        "metrics",
                        List.of(Map.of(
                                "name", pluginId + ":edits",
                                "description", "Edits",
                                "unit", "count",
                                "subject", "note",
                                "permission", pluginId + ":stats")))
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """)
                .source("com.acme.history.PluginConfig", """
                        package com.acme.history;
                        import org.springframework.context.annotation.ComponentScan;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        @ComponentScan(basePackages = "com.acme.history")
                        public class PluginConfig {}
                        """)
                .build();
        try (JarFile file = new JarFile(jar.toFile())) {
            var descriptor = descriptorParser.parse(file.getInputStream(file.getEntry("META-INF/rahdar/plugin.json"))
                    .readAllBytes());
            runtime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        }
        registry.set(pluginId, new PluginRuntimeRegistry.Active(runtime));
    }
}
