package io.github.sudoitir.rahdar.kernel.audit.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../audit} — filtered, newest-first paging over `audit_event`. */
@ExtendWith(AdminAuthenticationExtension.class)
class AuditControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository audit;

    @Autowired
    AuditService auditService;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();

        save("SEND_MESSAGE", "SUCCESS", 1, false);
        save("DELETE_MESSAGES", "SUCCESS", 5, false);
        save("DELETE_MESSAGES", "FAILURE", null, false);
        save("PURGE_QUEUE", "SUCCESS", 12, true);
    }

    @AfterEach
    void cleanUp() {
        audit.deleteAll();
        clusters.deleteById(clusterId);
    }

    private void save(String action, String outcome, Integer count, boolean dryRun) {
        AuditEventEntity e = new AuditEventEntity(
                action,
                new Actor("anonymous", null, "req", null),
                new AuditEventEntity.Target("QUEUE", "Q", clusterId, "prod", null),
                null,
                dryRun);
        if ("SUCCESS".equals(outcome)) {
            e.markSuccess(count == null ? 0 : count);
        } else if ("FAILURE".equals(outcome)) {
            e.markFailure("broker refused");
        }
        audit.save(e);
    }

    @Test
    void returnsEverythingNewestFirst() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.data.length()").value(4));
    }

    @Test
    void aCursorPagesWithoutOverlapOrATotal() throws Exception {
        String body = mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.mayLag").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var first = new tools.jackson.databind.json.JsonMapper().readTree(body);
        String next = first.get("next").asString();

        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId)
                        .param("size", "3")
                        .param("after", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.next").doesNotExist())
                .andExpect(jsonPath("$.data[0].id")
                        .value(Matchers.lessThan(
                                first.get("data").get(2).get("id").asInt())));
    }

    @Test
    void aPageNumberPastTheFirstTenThousandRowsIsRefusedAndNamesTheCursor() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId)
                        .param("page", "201")
                        .param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("`after`")));
    }

    @Test
    void aCursorThatWasNotGivenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("after", "not-a-cursor"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersByActionAndOutcome() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId)
                        .param("action", "DELETE_MESSAGES")
                        .param("outcome", "FAILURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].action").value("DELETE_MESSAGES"))
                .andExpect(jsonPath("$.data[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.data[0].error").value("broker refused"));
    }

    @Test
    void filtersByOutcomeOnly() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("outcome", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void aChildNamesItsParentAndTheParentFilterListsTheChildren() throws Exception {
        AuditEvent parent = auditService.begin(
                Actor.ofSystem(), "bulk.pause", "QUEUE", "3 queues", clusterId, null, Map.of(), false);
        ScopedValue.where(AuditScope.PARENT, parent.getId())
                .run(() -> auditService.begin(
                        Actor.ofSystem(), "PAUSE_QUEUE", "QUEUE", "orders", clusterId, null, Map.of(), false));

        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId)
                        .param("parentId", parent.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].action").value("PAUSE_QUEUE"))
                .andExpect(jsonPath("$.data[0].parentId").value(parent.getId()));
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("action", "bulk.pause"))
                .andExpect(jsonPath("$.data[0].id").value(parent.getId()))
                .andExpect(jsonPath("$.data[0].parentId").doesNotExist());
    }

    private long lastId() {
        return audit.findByClusterIdOrderByTsDesc(clusterId).getFirst().getId();
    }

    @Test
    void readsOneEventById() throws Exception {
        long id = lastId();

        mvc.perform(get("/api/v1/clusters/{c}/audit/{id}", clusterId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.action").exists());
    }

    @Test
    void anUnknownEventIsA404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit/{id}", clusterId, Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherClustersEventIsA404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit/{id}", UUID.randomUUID(), lastId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCallerWithoutAGrantGetsA404() throws Exception {
        long id = lastId();
        RahdarPrincipal nobody = new RahdarPrincipal(null, "nobody", Set.of(), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(nobody, null, nobody.getAuthorities()));

        mvc.perform(get("/api/v1/clusters/{c}/audit/{id}", clusterId, id)).andExpect(status().isNotFound());
    }

    @Test
    void aFutureTimeRangeNarrowsToNothing() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId).param("from", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
