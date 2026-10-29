package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.ContentSealer;
import io.github.sudoitir.rahdar.platform.governance.GovernanceRuleService;
import io.github.sudoitir.rahdar.platform.governance.StoredContentRemasker;
import io.github.sudoitir.rahdar.platform.governance.web.GovernanceRuleViews.RuleRequest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The index at rest against a real PostgreSQL (message-index spec, data-governance): what Rahdar stores is the
 * masked form, the originals are sealed beside it, search sees only masked text, clear access unseals, and a
 * policy change reaches rows already stored.
 */
class MessageIndexGovernanceTest extends PostgresIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    @Autowired
    private IndexQueryExecutor executor;

    @Autowired
    private MessageIndexWriter writer;

    @Autowired
    private SqlQueryParser parser;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContentPolicy policy;

    @Autowired
    private ContentSealer sealer;

    @Autowired
    private GovernanceRuleService rules;

    @Autowired
    private List<StoredContentRemasker> remaskers;

    private static final class Collect implements BrokerQueryExecutor.Sink {
        private final List<Row> rows = new ArrayList<>();

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            // This sink only records rows; per-node outcomes are not under test.
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM message_index WHERE cluster_id = ?", CLUSTER);
        as(Set.of("message:read"));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM message_index WHERE cluster_id = ?", CLUSTER);
        jdbc.update("DELETE FROM governance_rule WHERE NOT builtin");
        jdbc.update("DELETE FROM audit_event WHERE target_type = 'GOVERNANCE_RULE'");
    }

    private static void as(Set<String> permissions) {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "index-reader", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, permissions)), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static Row message(long id, String body, Map<String, Object> properties) {
        return new Row(
                NODE,
                "primary",
                "ORDER.IN",
                "ORDER.IN",
                id,
                3,
                true,
                4,
                Instant.now().toEpochMilli(),
                0,
                body.length(),
                null,
                null,
                null,
                null,
                null,
                body,
                false,
                false,
                null,
                properties,
                Source.BROKER,
                null,
                null,
                null,
                null);
    }

    private static Map<String, Object> sensitiveProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("Authorization", "Basic dXNlcjpwYXNzd29yZA==");
        properties.put("contact", "jane.doe@example.com");
        properties.put("tenant", "acme");
        return properties;
    }

    private List<Row> run(String sql) {
        QueryAst ast = parser.parse(sql);
        Target target = new Target(NODE, "primary", "ORDER.IN", "ORDER.IN", "ANYCAST", 0L, Instant.now(), true);
        QueryPlan plan = new QueryPlan(
                ast, Source.INDEX, List.of(target), null, false, List.of(), List.of(), 0, 100, false, List.of());
        Collect sink = new Collect();
        executor.execute(CLUSTER, plan, sink);
        return sink.rows;
    }

    private Map<String, Object> storedRow(long messageId) {
        return jdbc.queryForMap(
                "SELECT body, props::text AS props, sealed, policy_version FROM message_index"
                        + " WHERE cluster_id = ? AND message_id = ?",
                CLUSTER,
                messageId);
    }

    @Test
    void aStoredRowHoldsMaskedValuesAndASealThatNeverCarriesTheCredential() {
        writer.observe(
                CLUSTER, message(1, "{\"note\":\"mail jane.doe@example.com\"}", sensitiveProperties()), Instant.now());

        Map<String, Object> stored = storedRow(1);
        assertThat((String) stored.get("body")).contains("[redacted email]").doesNotContain("jane.doe@example.com");
        assertThat((String) stored.get("props"))
                .contains("[dropped credential]", "[redacted email]", "acme")
                .doesNotContain("dXNlcjpwYXNzd29yZA", "jane.doe@example.com");
        assertThat(stored.get("sealed")).isNotNull();
        assertThat(stored).containsEntry("policy_version", policy.version());

        Map<String, String> originals =
                sealer.unseal(MessageIndexWriter.aad(CLUSTER, NODE, "ORDER.IN", 1), (byte[]) stored.get("sealed"));
        assertThat(originals).containsEntry("PROPERTY:contact", "jane.doe@example.com");
        assertThat(originals.toString()).doesNotContain("dXNlcjpwYXNzd29yZA");
    }

    @Test
    void aSearchForAStoredEmailFindsNothing() {
        writer.observe(CLUSTER, message(2, "{\"note\":\"mail jane.doe@example.com\"}", Map.of()), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE body LIKE '%jane.doe@example.com%' LIMIT 10"))
                .isEmpty();
        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE MATCH (body) AGAINST ('jane') LIMIT 10"))
                .isEmpty();
    }

    @Test
    void onlyClearAccessUnsealsTheOriginals() {
        writer.observe(
                CLUSTER, message(3, "{\"note\":\"mail jane.doe@example.com\"}", sensitiveProperties()), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" LIMIT 10"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.properties()).containsEntry("contact", "[redacted email]");
                    assertThat(row.body()).doesNotContain("jane.doe@example.com");
                });

        as(Set.of("message:read", "message:clear"));
        assertThat(run("SELECT * FROM index.\"ORDER.IN\" LIMIT 10"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.properties()).containsEntry("contact", "jane.doe@example.com");
                    assertThat(row.properties()).containsEntry("Authorization", "[dropped credential]");
                    assertThat(row.body()).contains("jane.doe@example.com");
                });
    }

    @Test
    void aNewRuleReachesRowsAlreadyStored() {
        writer.observe(CLUSTER, message(4, "{\"ok\":true}", Map.of("customerName", "Jane Doe")), Instant.now());
        assertThat((String) storedRow(4).get("props")).contains("Jane Doe");

        as(Set.of("*"));
        rules.create(new RuleRequest(null, "PROPERTY", "customerName", "PERSONAL", null, true));
        int version = policy.version();
        StoredContentRemasker index = remaskers.stream()
                .filter(r -> r.name().equals("message_index"))
                .findFirst()
                .orElseThrow();
        while (index.remask(version, 500) == 500) {
            // Other tests' rows may be stale too; keep going until none are left.
        }

        Map<String, Object> stored = storedRow(4);
        assertThat((String) stored.get("props"))
                .contains("[redacted personal data]")
                .doesNotContain("Jane Doe");
        assertThat(stored).containsEntry("policy_version", version);
        assertThat(index.countBelow(version, 10)).isZero();

        as(Set.of("message:read", "message:clear"));
        assertThat(run("SELECT * FROM index.\"ORDER.IN\" LIMIT 10"))
                .singleElement()
                .satisfies(row -> assertThat(row.properties()).containsEntry("customerName", "Jane Doe"));
    }
}
