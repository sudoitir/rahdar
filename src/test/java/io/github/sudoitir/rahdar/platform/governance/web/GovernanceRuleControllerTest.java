package io.github.sudoitir.rahdar.platform.governance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernancePermissions;
import io.github.sudoitir.rahdar.platform.governance.Location;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code /api/v1/governance/rules}: permissions, built-in immutability, audit and policy version (data-governance). */
@ExtendWith(AdminAuthenticationExtension.class)
class GovernanceRuleControllerTest extends PostgresIntegrationTest {

    private static final String AUTHORIZATION_RULE = "00000000-0000-0000-0075-000000000001";

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContentPolicy policy;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM governance_rule WHERE NOT builtin");
        jdbc.update("UPDATE governance_rule SET enabled = TRUE WHERE builtin");
        jdbc.update("DELETE FROM audit_event WHERE target_type = 'GOVERNANCE_RULE'");
    }

    private int version() {
        return jdbc.queryForObject("SELECT version FROM governance_policy", Integer.class);
    }

    private List<String> auditActions(String targetName) {
        return jdbc.queryForList(
                "SELECT action FROM audit_event WHERE target_type = 'GOVERNANCE_RULE' AND target_name = ? ORDER BY ts",
                String.class,
                targetName);
    }

    @Test
    void theBuiltInCredentialRulesAreListedFirst() throws Exception {
        mvc.perform(get("/api/v1/governance/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].builtin").value(true))
                .andExpect(jsonPath("$.data[?(@.selector == 'authorization')].dataClass")
                        .value("CREDENTIAL"))
                .andExpect(jsonPath("$.data[?(@.selector == 'authorization')].defaultAction")
                        .value("DROP"));
    }

    @Test
    void creatingARuleIsAuditedBumpsTheVersionAndTakesEffect() throws Exception {
        int before = version();

        mvc.perform(post("/api/v1/governance/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destinationPattern":"orders.#","target":"PROPERTY","selector":"customerName",
                                 "dataClass":"PERSONAL","action":null,"enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.builtin").value(false))
                .andExpect(jsonPath("$.dataClassLabel").value("personal data"));

        assertThat(version()).isEqualTo(before + 1);
        assertThat(auditActions("customerName")).containsExactly("CREATE_GOVERNANCE_RULE");
        assertThat(policy.classifies(Location.PROPERTY, "customerName")).isTrue();
    }

    @Test
    void aBuiltInRuleCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/v1/governance/rules/{id}", AUTHORIZATION_RULE)).andExpect(status().isConflict());
    }

    @Test
    void aBuiltInRuleOnlyAcceptsEnabledAndDisablingItIsAudited() throws Exception {
        mvc.perform(put("/api/v1/governance/rules/{id}", AUTHORIZATION_RULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"PROPERTY","selector":"x-authorization","dataClass":"CREDENTIAL","enabled":true}
                                """))
                .andExpect(status().isConflict());

        mvc.perform(put("/api/v1/governance/rules/{id}", AUTHORIZATION_RULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"PROPERTY","selector":"authorization","dataClass":"CREDENTIAL","enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(auditActions("authorization")).containsExactly("DISABLE_GOVERNANCE_RULE");
    }

    @Test
    void anInvalidRuleIsRejectedBeforeAnyWrite() throws Exception {
        int before = version();

        mvc.perform(post("/api/v1/governance/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"target":"SOMEWHERE","selector":"","dataClass":"PERSONAL","enabled":true}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(version()).isEqualTo(before);
    }

    @Test
    void governanceReadAloneCannotChangeRules() throws Exception {
        RahdarPrincipal reader = new RahdarPrincipal(
                null,
                "governance-reader",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(GovernancePermissions.GOVERNANCE_READ))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(reader, null, reader.getAuthorities()));

        mvc.perform(get("/api/v1/governance/rules")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/governance/rules/{id}", AUTHORIZATION_RULE)).andExpect(status().isForbidden());
    }
}
