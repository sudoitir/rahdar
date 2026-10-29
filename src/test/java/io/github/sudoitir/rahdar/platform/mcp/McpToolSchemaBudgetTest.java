package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The budget that stops this surface rotting back into a REST mirror.
 *
 * <p>{@code tools/list} is sent to the model on every conversation that touches
 * Rahdar, before it has asked for anything. Its size is a permanent tax, and the
 * pressure on it is one-directional: every future change adds a tool or a
 * parameter, and none removes one. Review does not catch that, because each
 * individual addition looks reasonable. A build failure does.
 *
 * <p>The token estimate is {@code chars / 4} — crude, model-independent, and
 * consistently wrong in the same direction, which is all a budget needs.
 *
 * <p>The ceiling scales with tool count (ADR-0050). A flat total froze the surface
 * at the thirteen tools that happened to exist when it was calibrated, which is a
 * stronger claim than ADR-0045 meant to make: the goal is that no tool is bloated,
 * not that the product stops gaining capabilities. So the per-tool ceiling does the
 * real work, and the total is an average.
 *
 * <p>ADR-0054 lowered both ceilings. They were calibrated for schemas that had to
 * hedge — spelling defaults and type codes into descriptions in case the detail
 * resource was never read. {@code rahdar_help} is a tool, so the detail always
 * arrives, and the hedging came out. Leaving the old ceilings in place would have
 * banked that win as headroom for future bloat instead of keeping it.
 *
 * <p>If a change makes this fail, first move enum members and body shapes into
 * {@link McpToolCatalog}, or split the tool. When a new tool or an argument a tool
 * genuinely needs still does not fit, raise the ceiling as far as needed and say why
 * beside it: the budget guards against bloat, not against capabilities.
 */
class McpToolSchemaBudgetTest extends PostgresIntegrationTest {

    /**
     * The average a tool may cost across the whole listing (ADR-0050). A tool that
     * needs more than this must move its detail into {@link McpToolCatalog}, where a
     * model pays for it only once it has chosen that tool.
     *
     * <p>Raised from 136 for the role vocabulary (19d): {@code list_resources} gained {@code kind}, which
     * keeps the rows of one kind of a role and has no other way to be asked for, and five tools now name
     * {@code subscription} where they named {@code queue}, a longer name spelled in every schema.
     * Raised from 137 for the bounded Kafka reads: {@code kafka_describe_group} and
     * {@code kafka_preview_offset_reset} take {@code topic} and {@code cursor} to page one topic's partitions
     * (the answer is otherwise capped to the top 20), and {@code kafka_topic_config} takes
     * {@code onlyOverridden} and {@code keys}, which is what keeps its answer to the settings that matter.
     */
    private static final int AVERAGE_TOKEN_BUDGET_PER_TOOL = 139;

    /**
     * A tool that needs more than this is describing too much; split it or collapse it.
     * Raised from 175 for {@code artemis_config_change}, whose apply gate legitimately
     * needs the acknowledgement list and the plan hash as separate parameters: they
     * answer different questions ("I saw these hazards" / "I saw this plan") and
     * folding them into one string would hide which one a refusal is about.
     */
    private static final int PER_TOOL_TOKEN_BUDGET = 190;

    /**
     * A tool of one broker system is offered only to a caller who can read a cluster of it (ADR-0207), and the
     * database is shared with the other tests of the run, so whether one exists would decide the listing.
     */
    @MockitoBean
    McpSystems systems;

    @BeforeEach
    void everySystemHasAReadableCluster() {
        when(systems.readsAny(anyString())).thenReturn(true);
    }

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    McpToolCatalog catalog;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApiTokenService tokens;

    private JsonNode toolList() throws Exception {
        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        return McpFixture.rpc(mvc, key, "tools/list", null).path("result").path("tools");
    }

    private static int tokens(JsonNode node) {
        return node.toString().length() / 4;
    }

    @Test
    void theWholeToolListingFitsTheBudget() throws Exception {
        JsonNode tools = toolList();
        int total = tokens(tools);
        int ceiling = tools.size() * AVERAGE_TOKEN_BUDGET_PER_TOOL;
        assertThat(total)
                .describedAs(
                        "tools/list is %d tokens over %d tools (%d per tool), against a ceiling of %d. "
                                + "Move enum members and JSON body shapes into McpToolCatalog, or "
                                + "split a tool; raise the per-tool average only for what a tool genuinely needs, and say why.",
                        total, tools.size(), total / Math.max(tools.size(), 1), ceiling)
                .isLessThanOrEqualTo(ceiling);
    }

    @Test
    void noSingleToolDominatesTheListing() throws Exception {
        List<String> over = new ArrayList<>();
        for (JsonNode tool : toolList()) {
            int size = tokens(tool);
            if (size > PER_TOOL_TOKEN_BUDGET) {
                over.add(tool.path("name").asString() + " = " + size + " tokens");
            }
        }
        assertThat(over)
                .describedAs("these tools each cost more than %d tokens to describe", PER_TOOL_TOKEN_BUDGET)
                .isEmpty();
    }

    /**
     * A long enum spelled out in a schema is paid for on every listing. The
     * convention here is to validate discriminators server-side and name the valid
     * values in the rejection message instead (see {@code McpArgs}).
     */
    @Test
    void everyToolDeclaresADescriptionAndAnAnnotationSet() throws Exception {
        List<String> problems = new ArrayList<>();
        for (JsonNode tool : toolList()) {
            String name = tool.path("name").asString();
            if (tool.path("description").asString("").isBlank()) {
                problems.add(name + " has no description");
            }
            if (!tool.path("annotations").isObject()) {
                problems.add(name + " declares no annotations, so a host cannot tell whether it mutates");
            }
        }
        assertThat(problems).isEmpty();
    }

    /**
     * Posture honesty (ADR-0054). A host gates a whole tool on one
     * {@code destructiveHint}, so a read reachable through a tool that can also
     * destroy data makes every read prompt the operator — which is how an operator
     * is trained to reflex-approve the purge. The split between
     * each module's read and action tool classes is the structural
     * guarantee; this asserts the declarations match it.
     */
    @Test
    void everyToolDeclaresThePostureTheCatalogueGivesIt() throws Exception {
        Set<String> reads = catalog.entries().stream()
                .filter(e -> e.posture() == McpToolDef.Posture.READ)
                .map(McpToolDef::name)
                .collect(Collectors.toSet());
        List<String> problems = new ArrayList<>();
        for (JsonNode tool : toolList()) {
            String name = tool.path("name").asString();
            boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
            boolean destructive =
                    tool.path("annotations").path("destructiveHint").asBoolean(false);
            if (readOnly != reads.contains(name)) {
                problems.add(name + " declares readOnlyHint=" + readOnly + " but the catalogue puts it in the "
                        + (reads.contains(name) ? "read" : "mutate") + " half");
            }
            if (readOnly && destructive) {
                problems.add(name + " declares itself both read-only and destructive");
            }
        }
        assertThat(problems).isEmpty();
    }

    /**
     * Catalogue completeness (ADR-0054). The instructions block was hand-written in
     * {@code application.yml} and had drifted, omitting two tools — which, under a
     * host that searches tools rather than dumping the listing, told a model that
     * two capabilities did not exist. Everything is generated from the catalogue
     * now, and this is what stops a new tool shipping undescribed.
     */
    @Test
    void everyRegisteredToolIsInTheCatalogueAndTheInstructions() throws Exception {
        List<String> registered = new ArrayList<>();
        for (JsonNode tool : toolList()) {
            registered.add(tool.path("name").asString());
        }
        assertThat(registered).isNotEmpty();

        assertThat(catalog.toolNames())
                .describedAs("every registered tool must be described in McpToolCatalog")
                .containsExactlyInAnyOrderElementsOf(registered);

        String instructions = catalog.instructions();
        assertThat(registered)
                .describedAs("the generated instructions must name every registered tool")
                .allSatisfy(name -> assertThat(instructions).contains(name));
    }
}
