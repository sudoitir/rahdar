package io.github.sudoitir.rahdar.feature.events.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../events} — filtered, newest-first paging over {@code broker_event}. */
@ExtendWith(AdminAuthenticationExtension.class)
class EventControllerTest extends PostgresIntegrationTest {

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
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();

        event("openbroker.consumer.attached", "orders", 3);
        event("openbroker.consumer.detached", "orders", 2);
        event("openbroker.channel.opened", "payments", 1);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId));
        clusters.deleteById(clusterId);
    }

    private void event(String type, String address, int ageMinutes) {
        jdbc.update("""
                INSERT INTO broker_event (occurred_at, type, destination, cluster_id)
                VALUES (now() - make_interval(mins => :age), :type, :addr, :c)
                """, Map.of("age", ageMinutes, "type", type, "addr", address, "c", clusterId));
    }

    @Test
    void returnsEverythingNewestFirstWithEnvelope() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].type").value("openbroker.channel.opened"))
                .andExpect(jsonPath("$.dropped").value(0));
    }

    @Test
    void aCursorContinuesWhereThePageEndedWithoutATotal() throws Exception {
        String body = mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.mayLag").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String next = new tools.jackson.databind.json.JsonMapper()
                .readTree(body)
                .get("next")
                .asString();

        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId)
                        .param("size", "2")
                        .param("after", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("openbroker.consumer.attached"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void filtersByType() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("type", "openbroker.consumer.attached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].type").value("openbroker.consumer.attached"));
    }

    @Test
    void filtersByAddress() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("destination", "payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void readsOneEventById() throws Exception {
        long seq = jdbc.queryForObject(
                "SELECT max(seq) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Long.class);

        mvc.perform(get("/api/v1/clusters/{c}/events/{seq}", clusterId, seq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(seq))
                .andExpect(jsonPath("$.type").exists());
    }

    @Test
    void anUnknownEventIsA404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events/{seq}", clusterId, Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherClustersEventIsA404() throws Exception {
        long seq = jdbc.queryForObject(
                "SELECT max(seq) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Long.class);

        mvc.perform(get("/api/v1/clusters/{c}/events/{seq}", UUID.randomUUID(), seq))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCallerWithoutAGrantGetsA404() throws Exception {
        long seq = jdbc.queryForObject(
                "SELECT max(seq) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Long.class);
        RahdarPrincipal nobody = new RahdarPrincipal(null, "nobody", Set.of(), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(nobody, null, nobody.getAuthorities()));

        mvc.perform(get("/api/v1/clusters/{c}/events/{seq}", clusterId, seq)).andExpect(status().isNotFound());
    }

    @Test
    void aFutureTimeRangeNarrowsToNothing() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/events", clusterId).param("from", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
