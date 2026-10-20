package io.github.sudoitir.rahdar.architecture;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.rahdar.RahdarApplication;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatus;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatuses;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.internal.RoleService;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.PermissionView;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.StreamTopicRegistry;
import io.github.sudoitir.rahdar.platform.mcp.McpRunbookPrompts;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every optional feature can be switched off at startup (task 5.15, feature-modules spec). The
 * application starts without it and without the features that require it; its endpoints answer
 * {@code 404 feature-disabled}; its jobs, MCP tools, stream topics, settings and permissions are
 * absent; and a runbook prompt that names one of its tools says which setting restores it.
 *
 * <p>One application per feature, started and closed in turn, so their connection pools never
 * overlap with each other or with the cached test contexts.
 */
class FeatureToggleTest {

    private static final String ROOT = "io.github.sudoitir.rahdar";

    /** The tools the runbook prompts name. */
    private static final Set<String> RUNBOOK_TOOLS = Set.of(
            "diagnose",
            "list_resources",
            "activity_log",
            "browse_messages",
            "message_action",
            "metric_series",
            "rahdar_setting");

    static List<FeatureDescriptor> descriptors() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT).stream()
                        .filter(type -> type.tryGetField("DESCRIPTOR").isPresent())
                        .map(type -> (FeatureDescriptor) ReflectionTestUtils.getField(type.reflect(), "DESCRIPTOR"))
                        .toList();
    }

    static Stream<String> optionalFeatures() {
        return descriptors().stream()
                .filter(descriptor -> !descriptor.required())
                .map(FeatureDescriptor::id)
                .sorted();
    }

    /** The feature and every feature that, directly or not, requires it. */
    static Set<String> withDependents(String featureId, List<FeatureDescriptor> all) {
        Set<String> off = new LinkedHashSet<>(List.of(featureId));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (FeatureDescriptor descriptor : all) {
                if (descriptor.requires().stream().anyMatch(off::contains) && off.add(descriptor.id())) {
                    grew = true;
                }
            }
        }
        return off;
    }

    @ParameterizedTest
    @MethodSource("optionalFeatures")
    void aDisabledFeatureIsAbsent(String featureId) throws Exception {
        List<FeatureDescriptor> all = descriptors();
        FeatureDescriptor feature =
                all.stream().filter(d -> d.id().equals(featureId)).findFirst().orElseThrow();
        List<String> properties = new ArrayList<>(PostgresIntegrationTest.connectionProperties());
        properties.add("server.port=0");
        withDependents(featureId, all).forEach(id -> properties.add("rahdar.features." + id + ".enabled=false"));

        // Command-line arguments, not builder properties: those are defaults, which application.yml overrides.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(RahdarApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new))) {
            assertThat(context.getBean(FeatureRegistry.class).isEnabled(featureId))
                    .isFalse();

            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                    .apply(springSecurity())
                    .addFilters(context.getBean("featureDisabledFilter", Filter.class))
                    .build();
            for (String prefix : feature.apiPrefixes()) {
                String path = prefix.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                mvc.perform(get(path).with(authentication(administrator())))
                        .andExpect(status().isNotFound())
                        .andExpect(content().string(containsString("feature-disabled")))
                        .andExpect(content().string(containsString("rahdar.features." + featureId + ".enabled")));
            }

            assertThat(context.getBean(JobStatuses.class).all())
                    .extracting(JobStatus::featureId)
                    .doesNotContain(featureId);

            Set<String> toolNames =
                    feature.mcpTools().stream().map(McpToolDef::name).collect(Collectors.toSet());
            List<String> tools = ReflectionTestUtils.invokeMethod(context.getBean("mcpToolCatalog"), "toolNames");
            assertThat(tools).noneMatch(toolNames::contains);

            Set<String> topicNames =
                    feature.streamTopics().stream().map(TopicDef::name).collect(Collectors.toSet());
            Set<String> topics = context.getBean(StreamTopicRegistry.class).known();
            assertThat(topics).noneMatch(topicNames::contains);

            SecurityContextHolder.getContext().setAuthentication(administrator());
            try {
                assertThat(context.getBean(SettingsService.class).effective().keySet())
                        .noneMatch(feature.settingKeys()::contains);

                Set<String> actions = feature.permissions().stream()
                        .map(PermissionDef::action)
                        .collect(Collectors.toSet());
                assertThat(context.getBean(RoleService.class).catalogue())
                        .extracting(PermissionView::action)
                        .noneMatch(actions::contains);
            } finally {
                SecurityContextHolder.clearContext();
            }

            McpRunbookPrompts prompts =
                    context.getBeanProvider(McpRunbookPrompts.class).getIfAvailable();
            if (prompts != null) {
                String property = "rahdar.features." + featureId + ".enabled";
                boolean namedByARunbook =
                        feature.mcpTools().stream().map(McpToolDef::name).anyMatch(RUNBOOK_TOOLS::contains);
                if (namedByARunbook) {
                    assertThat(runbooks(prompts)).contains(property + "=true");
                } else {
                    assertThat(runbooks(prompts)).doesNotContain(property);
                }
            }
        }
    }

    private static UsernamePasswordAuthenticationToken administrator() {
        RahdarPrincipal principal = new RahdarPrincipal(
                UUID.randomUUID(),
                "feature-toggle-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("*"))),
                false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static String runbooks(McpRunbookPrompts prompts) {
        return Stream.of(
                        prompts.triageCluster(null),
                        prompts.investigateSubscription(null, null),
                        prompts.beforeYouPurge(null, null),
                        prompts.tuneScrapeLoad(null))
                .flatMap(result -> result.messages().stream())
                .map(message -> ((McpSchema.TextContent) message.content()).text())
                .collect(Collectors.joining("\n"));
    }
}
