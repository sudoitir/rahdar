package io.github.sudoitir.rahdar.kernel.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.sudoitir.rahdar.RahdarApplication;
import io.github.sudoitir.rahdar.feature.diagnostics.DiagnosticsService;
import io.github.sudoitir.rahdar.kernel.plugin.PluginLicense;
import io.github.sudoitir.rahdar.kernel.plugin.PluginLicenseChanged;
import io.github.sudoitir.rahdar.kernel.plugin.PluginLicenseStore;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.kernel.stream.Subscriber;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService;
import io.github.sudoitir.rahdar.platform.clusters.web.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernanceRuleService;
import io.github.sudoitir.rahdar.platform.governance.web.GovernanceRuleViews.RuleRequest;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two replicas, this context (A) and a second one on the same database (B): a change made on A to
 * shared state is served by B within the two seconds the spec allows, and a signal sent on the bus
 * closes the streams of an ended session or revoked token on B.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class CacheCoherenceTest extends PostgresIntegrationTest {

    private static final Duration WITHIN = Duration.ofSeconds(2);

    private static ConfigurableApplicationContext other;

    @Autowired
    SettingsService settingsA;

    @Autowired
    EnvironmentService environmentsA;

    @Autowired
    GovernanceRuleService rulesA;

    @Autowired
    DiagnosticsService diagnosticsA;

    @Autowired
    RahdarBus busA;

    @Autowired
    PluginLicenseStore licensesA;

    @Autowired
    ConfigurableApplicationContext contextA;

    @Autowired
    JsonMapper json;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void startTheOtherReplica() {
        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        other = new SpringApplicationBuilder(RahdarApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    @AfterAll
    static void stopTheOtherReplica() {
        other.close();
    }

    @Test
    void aSettingChangedOnOneReplicaIsServedByTheOtherAndPushedToItsHolders() {
        SettingsService settingsB = other.getBean(SettingsService.class);
        NodeCallLimiter limiterB = other.getBean(NodeCallLimiter.class);

        settingsA.put(BrokerSettings.RATE_LIMIT, "7");
        await().atMost(WITHIN).untilAsserted(() -> {
            assertThat(settingsB.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(7);
            assertThat(limiterB.permitsPerSecond()).isEqualTo(7);
        });

        settingsA.reset(BrokerSettings.RATE_LIMIT);
        await().atMost(WITHIN).untilAsserted(() -> {
            assertThat(settingsB.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(20);
            assertThat(limiterB.permitsPerSecond()).isEqualTo(20);
        });
    }

    @Test
    void aClusterMovedToAnotherEnvironmentOnOneReplicaIsSeenByTheOther() {
        ClusterEnvironmentIndex indexB = other.getBean(ClusterEnvironmentIndex.class);
        UUID clusterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO cluster (id, name, system) VALUES (:id, :name, 'artemis')",
                Map.of("id", clusterId, "name", "coherence-" + clusterId));
        UUID environmentId = environmentsA
                .create(new EnvironmentRequest("coherence-" + clusterId, null, 0))
                .id();
        try {
            assertThat(indexB.environmentOf(clusterId)).isNull();

            environmentsA.assignCluster(clusterId, environmentId);

            await().atMost(WITHIN)
                    .untilAsserted(
                            () -> assertThat(indexB.environmentOf(clusterId)).isEqualTo(environmentId));
        } finally {
            jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
            environmentsA.delete(environmentId);
        }
    }

    @Test
    void aGovernanceRuleWrittenOnOneReplicaChangesThePolicyOfTheOther() {
        ContentPolicy policyB = other.getBean(ContentPolicy.class);
        int before = policyB.version();

        UUID rule = rulesA.create(
                        new RuleRequest(null, "PROPERTY", "coherence-" + UUID.randomUUID(), "PERSONAL", null, true))
                .id();

        await().atMost(WITHIN).untilAsserted(() -> assertThat(policyB.version()).isGreaterThan(before));
        rulesA.delete(rule);
    }

    @Test
    void aSupportBundlePreparedOnOneReplicaCanBeDownloadedFromTheOther() {
        DiagnosticsService diagnosticsB = other.getBean(DiagnosticsService.class);

        UUID id = diagnosticsA.prepare().id();

        assertThat(diagnosticsB.take(id, List.of("about")).sections())
                .singleElement()
                .satisfies(section -> assertThat(section.key()).isEqualTo("about"));
    }

    @Test
    void aLicenseUploadedOnOneReplicaIsAnnouncedAndServedOnBoth() {
        List<PluginLicenseChanged> onA = new CopyOnWriteArrayList<>();
        List<PluginLicenseChanged> onB = new CopyOnWriteArrayList<>();
        listen(contextA, onA);
        listen(other, onB);
        String id = "acme-coherence-" + Long.toString(System.nanoTime(), 36);
        String artifact = PluginLicenseStore.sha256(id.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> descriptor = PluginJarBuilder.defaultDescriptor(id);
        descriptor.put("requiresLicense", true);
        jdbc.update(
                "INSERT INTO plugin_artifact (uploaded_at, size_bytes, sha256, content) VALUES (now(), 1, :sha, :c)",
                Map.of("sha", artifact, "c", new byte[] {1}));
        jdbc.update(
                "INSERT INTO plugin_install (installed_at, updated_at, id, version, vendor, sha256, status, installed_by,"
                        + " descriptor, schema_changed) VALUES (now(), now(), :id, '1.0.0', 'Acme', :sha, 'active', 'test',"
                        + " CAST(:d AS jsonb), false)",
                Map.of("id", id, "sha", artifact, "d", json.writeValueAsString(descriptor)));
        try {
            PluginLicenseStore storeB = other.getBean(PluginLicenseStore.class);
            String sha = licensesA.put(id, "one-file".getBytes(StandardCharsets.UTF_8), "ops");

            await().atMost(WITHIN).untilAsserted(() -> {
                assertThat(onA).contains(new PluginLicenseChanged(id));
                assertThat(onB).contains(new PluginLicenseChanged(id));
            });
            assertThat(storeB.summary(id).state()).isEqualTo(PluginLicenseStore.State.UNCHECKED);

            // The plugin on the other replica judges the file; the first replica shows the verdict.
            ((PluginLicense) storeB.beansFor(id).get("pluginLicense"))
                    .report(sha, new PluginLicense.Verdict(PluginLicense.Status.VALID, null, "Acme Ltd", null));
            assertThat(licensesA.summary(id).state()).isEqualTo(PluginLicenseStore.State.VALID);

            onB.clear();
            licensesA.remove(id);
            await().atMost(WITHIN).untilAsserted(() -> assertThat(onB).contains(new PluginLicenseChanged(id)));
            assertThat(storeB.summary(id).state()).isEqualTo(PluginLicenseStore.State.MISSING);
        } finally {
            jdbc.update("DELETE FROM plugin_license WHERE plugin_id = :id", Map.of("id", id));
            jdbc.update("DELETE FROM plugin_install WHERE id = :id", Map.of("id", id));
            jdbc.update("DELETE FROM plugin_artifact WHERE sha256 = :sha", Map.of("sha", artifact));
        }
    }

    private static void listen(ConfigurableApplicationContext context, List<PluginLicenseChanged> into) {
        context.addApplicationListener((ApplicationListener<PayloadApplicationEvent<?>>) event -> {
            if (event.getPayload() instanceof PluginLicenseChanged changed) {
                into.add(changed);
            }
        });
    }

    @Test
    void anEndedSessionClosesItsStreamsOnTheOtherReplica() {
        SseEmitter ended = mock(SseEmitter.class);
        SseEmitter kept = mock(SseEmitter.class);
        SseHub hubB = other.getBean(SseHub.class);
        UUID clusterId = UUID.randomUUID();
        hubB.register(clusterId, new Subscriber(ended, Set.of("queues"), "session-ended-1", null, null));
        hubB.register(clusterId, new Subscriber(kept, Set.of("queues"), "session-kept", null, null));

        busA.publish(new ReplicaSignal("session-ended", "session-ended-1"));

        await().atMost(WITHIN).untilAsserted(() -> verify(ended).complete());
        assertThat(hubB.clientCount()).isEqualTo(1);
    }

    @Test
    void aRevokedTokenClosesItsStreamsOnTheOtherReplica() {
        SseEmitter revoked = mock(SseEmitter.class);
        SseHub hubB = other.getBean(SseHub.class);
        UUID tokenId = UUID.randomUUID();
        hubB.register(UUID.randomUUID(), new Subscriber(revoked, Set.of("queues"), null, tokenId, null));

        busA.publish(new ReplicaSignal("token-revoked", tokenId.toString()));

        await().atMost(WITHIN).untilAsserted(() -> verify(revoked).complete());
    }
}
