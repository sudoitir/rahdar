package io.github.sudoitir.rahdar.feature.events.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** Broker notification props carry filter strings; what the detectors find in them is masked (data-governance). */
@ExtendWith(AdminAuthenticationExtension.class)
class EventPropsGovernanceTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        jdbc.update(
                """
                INSERT INTO broker_event (occurred_at, type, destination, data, cluster_id)
                VALUES (now(), 'openbroker.consumer.attached', 'orders', CAST(:props AS jsonb), :c)
                """,
                Map.of(
                        "props",
                        "{\"_AMQ_FilterString\":\"email = 'jane@example.com'\",\"_AMQ_Distance\":0}",
                        "c",
                        clusterId));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId));
        clusters.deleteById(clusterId);
    }

    @Test
    void aDetectedValueInAPropIsMaskedAndOtherPropsAreUnchanged() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].props._AMQ_FilterString").value("email = '[redacted email]'"))
                .andExpect(jsonPath("$.data[0].props._AMQ_Distance").value(0));
    }
}
