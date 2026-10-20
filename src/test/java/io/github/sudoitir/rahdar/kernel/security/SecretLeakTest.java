package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * The proof of ADR-0132 and ADR-0133: distinct, known secrets are planted in every store and in requests that fail,
 * then every place a secret could leave Rahdar is read and searched for them. The bridge declaration's own six
 * paths (ADR-0092) stay in {@code BridgeCredentialDisclosureTest}; this covers what that test cannot, the running
 * application.
 */
@ExtendWith({AdminAuthenticationExtension.class, OutputCaptureExtension.class})
class SecretLeakTest extends PostgresIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(SecretLeakTest.class);

    /** Planted secrets are generated per run, so none is a literal a secret scanner could mistake for a real one. */
    private static String planted(String kind) {
        return kind + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static final String CLUSTER_PASSWORD = planted("jolokia-leak");
    private static final String BRIDGE_PASSWORD = planted("bridge-leak");
    /** Base64, since a webhook signing secret must be. */
    private static final String CHANNEL_SECRET =
            Base64.getEncoder().encodeToString(planted("channel").getBytes(StandardCharsets.UTF_8));

    private static final String PLUGIN_SECRET = planted("plugin-leak");
    private static final String URL_SECRET = planted("urlinfo-leak");
    private static final String BODY_SECRET = planted("body-leak");
    private static final String LOG_SECRET = planted("logline-leak");
    private static final String EXCEPTION_SECRET = planted("exception-leak");
    private static final String WEBHOOK_PATH_SECRET = planted("webhook-leak");
    private static final String TOTP_CODE = planted("totp-leak");
    private static final String RECOVERY_CODE = planted("recovery-leak");

    private static final List<String> ALL = List.of(
            CLUSTER_PASSWORD,
            BRIDGE_PASSWORD,
            CHANNEL_SECRET,
            PLUGIN_SECRET,
            URL_SECRET,
            BODY_SECRET,
            LOG_SECRET,
            EXCEPTION_SECRET,
            WEBHOOK_PATH_SECRET,
            TOTP_CODE,
            RECOVERY_CODE);

    @MockitoBean
    BrokerConnections connections;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PluginSecretStore pluginSecrets;

    private final UUID clusterId = UUID.randomUUID();
    private final String plugin = "leak" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> seen = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void plantSecrets() throws Exception {
        mvc = webAppContextSetup(webContext).build();
        // The connection edit lays the request over what the cluster has now, which is nothing yet.
        when(connections.settingsFor(any()))
                .thenReturn(new BrokerConnectionSettings(clusterId, null, null, null, true));
        jdbc.update("INSERT INTO cluster (id, name, system) VALUES (?, ?, 'artemis')", clusterId, "leak-" + clusterId);

        String base = "/api/v1/clusters/" + clusterId;
        send(patch(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"management\":{\"username\":\"admin\",\"password\":\"" + CLUSTER_PASSWORD + "\"}}"));
        send(put(base + "/artemis/config/bridge-credentials/remote-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bridge\",\"password\":\"" + BRIDGE_PASSWORD + "\"}"));
        send(post("/api/v1/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"leak-" + clusterId + "\",\"kind\":\"WEBHOOK\","
                        + "\"config\":\"{\\\"url\\\":\\\"https://hooks.example.com/x\\\"}\","
                        + "\"secret\":\"" + CHANNEL_SECRET + "\",\"enabled\":true}"));
        ((PluginSecrets) pluginSecrets.beansFor(plugin).get("pluginSecrets")).put("token", PLUGIN_SECRET);
    }

    @AfterEach
    void unplant() {
        jdbc.update("DELETE FROM notification_channel WHERE name = ?", "leak-" + clusterId);
        jdbc.update("DELETE FROM plugin_secret WHERE plugin_id = ?", plugin);
        jdbc.update("DELETE FROM cluster WHERE id = ?", clusterId);
    }

    /** Runs a request and keeps the body, whatever the status, as something a caller could read. */
    private void send(MockHttpServletRequestBuilder request) throws Exception {
        seen.add(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void noSecretIsInAnApiReadAnErrorBodyOrTheExport() throws Exception {
        String base = "/api/v1/clusters/" + clusterId;
        send(get("/api/v1/clusters"));
        send(get(base));
        send(get(base + "/artemis/config/bridge-credentials"));
        send(get(base + "/artemis/config/export-xml"));
        send(get("/api/v1/channels"));
        send(get("/api/v1/settings/secrets"));

        // Requests that fail while carrying a secret: a body that does not validate, one that does not parse, and a
        // registration whose unreachable URL has user-info.
        send(patch(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"management\":{\"username\":\"\",\"password\":\"" + BODY_SECRET + "\"}}"));
        send(patch(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"management\":{\"password\":\"" + BODY_SECRET));
        send(post("/api/v1/clusters?dryRun=true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seedUrls\":[\"http://admin:" + URL_SECRET + "@127.0.0.1:1/console/jolokia\"]}"));

        // A second-factor proof that is refused or does not parse (ADR-0143).
        send(post("/api/v1/auth/second-factor")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totpCode\":\"" + TOTP_CODE + "\",\"recoveryCode\":\"" + RECOVERY_CODE + "\"}"));
        send(post("/api/v1/auth/second-factor")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recoveryCode\":\"" + RECOVERY_CODE));

        assertThat(String.join("\n", seen)).doesNotContain(ALL);
    }

    @Test
    void noSecretIsInAnAuditRow() throws Exception {
        send(post("/api/v1/clusters?dryRun=true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seedUrls\":[\"http://admin:" + URL_SECRET + "@127.0.0.1:1/console/jolokia\"]}"));

        String rows = String.join("\n", jdbc.queryForList("SELECT t::text FROM audit_event t", String.class));

        // An account in a seed URL is refused before anything is recorded, and the refusal does not repeat it.
        assertThat(seen.getLast()).contains("Put the account in the Management account fields");
        assertThat(rows).doesNotContain(ALL);
    }

    @Test
    void noSecretIsInTheLogsAndALoggedOneIsMasked(CapturedOutput output) throws Exception {
        LOG.warn("login failed password={}", LOG_SECRET);
        LOG.warn("second factor refused totpCode={} recoveryCode={}", TOTP_CODE, RECOVERY_CODE);
        LOG.error(
                "connect failed", new IllegalStateException("cannot reach tcp://bob:" + EXCEPTION_SECRET + "@broker"));
        send(post("/api/v1/clusters?dryRun=true")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seedUrls\":[\"http://admin:" + URL_SECRET + "@127.0.0.1:1/console/jolokia\"]}"));

        assertThat(output.getAll())
                .contains("password=[redacted]", "totpCode=[redacted]", "recoveryCode=[redacted]")
                .doesNotContain(ALL);
    }

    @Test
    void aFailedWebhookDeliveryDoesNotEchoTheSecretUrl(CapturedOutput output) throws Exception {
        send(post("/api/v1/channels/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SLACK\",\"secret\":\"http://127.0.0.1:1/services/T/B/" + WEBHOOK_PATH_SECRET
                        + "\"}"));

        assertThat(seen.getLast()).contains("Slack request failed");
        assertThat(seen.getLast() + output.getAll()).doesNotContain(WEBHOOK_PATH_SECRET);
    }

    @Test
    void brokerCredentialsAreStoredAsEnvelopeBlobsWithoutThePlaintext() {
        List<byte[]> blobs =
                jdbc.queryForList("SELECT sealed FROM broker_credential WHERE cluster_id = ?", byte[].class, clusterId);

        // The other two secrets really were stored, so their absence elsewhere means something.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notification_channel WHERE name = ? AND sealed IS NOT NULL",
                        Integer.class,
                        "leak-" + clusterId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plugin_secret WHERE plugin_id = ?", Integer.class, plugin))
                .isEqualTo(1);
        assertThat(blobs).hasSize(2).allSatisfy(blob -> {
            assertThat(blob[0]).isEqualTo((byte) 0x01);
            String raw = new String(blob, StandardCharsets.ISO_8859_1);
            assertThat(raw).doesNotContain(CLUSTER_PASSWORD, BRIDGE_PASSWORD);
        });
    }
}
