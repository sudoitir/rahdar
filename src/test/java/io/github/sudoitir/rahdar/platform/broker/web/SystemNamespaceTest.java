package io.github.sudoitir.rahdar.platform.broker.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * A broker system's own routes ({@code /clusters/{id}/artemis/...}) answer the standard 404 on a cluster that
 * runs another system, and are reached on one that runs it.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class SystemNamespaceTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    JdbcTemplate jdbc;

    private MockMvc mvc;
    private UUID artemis;
    private UUID other;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        artemis = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "a-" + UUID.randomUUID(), null, null))
                .getId();
        other = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "o-" + UUID.randomUUID(), null, null))
                .getId();
        jdbc.update("UPDATE cluster SET system = 'kafka' WHERE id = ?", other);
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(artemis);
        clusters.deleteById(other);
    }

    @Test
    void aClusterThatRunsAnotherSystemHasNoArtemisConfig() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/artemis/config", other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(containsString("not-found")))
                .andExpect(jsonPath("$.detail").value(containsString("does not run artemis")));
        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clusters/{c}/artemis/setup-review", other)).andExpect(status().isNotFound());
    }

    @Test
    void anArtemisClusterReachesItsNamespace() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/artemis/config", artemis))
                .andExpect(content().string(not(containsString("does not run"))));
    }
}
