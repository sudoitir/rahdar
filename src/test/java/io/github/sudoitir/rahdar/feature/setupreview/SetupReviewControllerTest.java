package io.github.sudoitir.rahdar.feature.setupreview;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** The setup review over HTTP, with the payloads the frontend sends (ADR-0096, ADR-0106). */
@ExtendWith(AdminAuthenticationExtension.class)
class SetupReviewControllerTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    MockMvc mvc;
    UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "review-" + UUID.randomUUID(), null, null))
                .getId();
        // No management URL: the review must say it could not read the node, not pass it.
        nodes.save(BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "NONE", UUID.randomUUID().toString()));
    }

    @AfterEach
    void tearDown() {
        clusters.deleteById(clusterId);
    }

    @Test
    void neverReviewedThenAnUnreadableNodeIsStatedAndASecondRunIsTooSoon() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/artemis/setup-review", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.rulesInCatalogue").value(22));

        mvc.perform(post("/api/v1/clusters/{id}/artemis/setup-review/run", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$.nodesTotal").value(1))
                .andExpect(jsonPath("$.nodesReviewed").value(0))
                .andExpect(jsonPath("$.clusterEvaluated").value(false))
                .andExpect(jsonPath("$.nodes[0].reviewed").value(false))
                .andExpect(jsonPath("$.nodes[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.notice").doesNotExist());

        mvc.perform(post("/api/v1/clusters/{id}/artemis/setup-review/run", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").isNotEmpty());
    }

    @Test
    void acceptingAFindingThatDoesNotExistIs404AndAReasonIsRequired() throws Exception {
        mvc.perform(post("/api/v1/clusters/{id}/artemis/setup-review/acceptances", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HA_SINGLE_PAIR_QUORUM","subject":"cluster","reason":"dev only","expiresAt":null}"""))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/clusters/{id}/artemis/setup-review/acceptances", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HA_SINGLE_PAIR_QUORUM","subject":"cluster","reason":"","expiresAt":null}"""))
                .andExpect(status().isBadRequest());
    }
}
