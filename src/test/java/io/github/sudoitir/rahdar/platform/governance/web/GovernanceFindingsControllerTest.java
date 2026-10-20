package io.github.sudoitir.rahdar.platform.governance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernancePermissions;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** The classification inbox: confirm makes a rule, dismiss makes an exception, both audited (data-governance). */
@ExtendWith(AdminAuthenticationExtension.class)
class GovernanceFindingsControllerTest extends PostgresIntegrationTest {

    private static final String ADDRESS = "inbox." + UUID.randomUUID();

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
        jdbc.update("DELETE FROM classification_finding WHERE destination = ?", ADDRESS);
        jdbc.update("DELETE FROM governance_rule WHERE destination_pattern = ?", ADDRESS);
        jdbc.update("DELETE FROM audit_event WHERE target_type = 'CLASSIFICATION_FINDING'");
    }

    private UUID finding(String fieldPath, String dataClass) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO classification_finding
                    (first_seen_at, last_seen_at, hit_count, destination, location, field_path, data_class, id)
                VALUES (now(), now(), 12, ?, 'PROPERTY', ?, ?, ?)
                """, ADDRESS, fieldPath, dataClass, id);
        return id;
    }

    private List<String> auditActions() {
        return jdbc.queryForList(
                "SELECT action FROM audit_event WHERE target_type = 'CLASSIFICATION_FINDING' AND target_name LIKE ?",
                String.class,
                ADDRESS + "%");
    }

    @Test
    void openFindingsAreListedWithoutAnyValue() throws Exception {
        finding("contact", "EMAIL");

        mvc.perform(get("/api/v1/governance/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.address == '" + ADDRESS + "')].fieldPath")
                        .value("contact"))
                .andExpect(jsonPath("$.data[?(@.address == '" + ADDRESS + "')].dataClassLabel")
                        .value("email"))
                .andExpect(jsonPath("$.data[?(@.address == '" + ADDRESS + "')].hitCount")
                        .value(12));
    }

    @Test
    void confirmingCreatesARuleForThatFieldAndIsAudited() throws Exception {
        UUID id = finding("customerRef", "PERSONAL");

        mvc.perform(post("/api/v1/governance/findings/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(jdbc.queryForList(
                        "SELECT target || ':' || selector || ':' || data_class FROM governance_rule"
                                + " WHERE destination_pattern = ? AND NOT is_exception",
                        String.class,
                        ADDRESS))
                .containsExactly("PROPERTY:customerRef:PERSONAL");
        assertThat(auditActions()).containsExactly("CONFIRM_FINDING");
        assertThat(policy.govern(
                                new GovernContext(null, ADDRESS, false),
                                new MessageContent(Map.of(), Map.of("customerRef", "Jane Doe"), null, false, null))
                        .properties())
                .containsEntry("customerRef", "[redacted personal data]");
    }

    @Test
    void dismissingStopsTheDetectorMaskingThatClassThereAndIsAudited() throws Exception {
        UUID id = finding("orderNumber", "PHONE");
        MessageContent phoneShaped =
                new MessageContent(Map.of(), Map.of("orderNumber", "+4915112345678"), null, false, null);
        assertThat(policy.govern(new GovernContext(null, ADDRESS, false), phoneShaped)
                        .properties())
                .containsEntry("orderNumber", "[redacted phone number]");

        mvc.perform(post("/api/v1/governance/findings/{id}/dismiss", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        assertThat(policy.govern(new GovernContext(null, ADDRESS, false), phoneShaped)
                        .properties())
                .containsEntry("orderNumber", "+4915112345678");
        assertThat(auditActions()).containsExactly("DISMISS_FINDING");
    }

    @Test
    void aDecidedFindingCannotBeDecidedAgain() throws Exception {
        UUID id = finding("contact", "EMAIL");
        mvc.perform(post("/api/v1/governance/findings/{id}/dismiss", id)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/governance/findings/{id}/confirm", id)).andExpect(status().isConflict());
    }

    @Test
    void governanceReadAloneCannotDecideAFinding() throws Exception {
        UUID id = finding("contact", "EMAIL");
        RahdarPrincipal reader = new RahdarPrincipal(
                null,
                "inbox-reader",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(GovernancePermissions.GOVERNANCE_READ))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(reader, null, reader.getAuthorities()));

        mvc.perform(get("/api/v1/governance/findings")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/governance/findings/{id}/confirm", id)).andExpect(status().isForbidden());
    }
}
