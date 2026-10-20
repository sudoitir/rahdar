package io.github.sudoitir.rahdar.kernel.plugin.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.plugin.Contract;
import io.github.sudoitir.rahdar.kernel.plugin.PluginProperties;
import io.github.sudoitir.rahdar.kernel.plugin.RahdarVersion;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.plugin.support.RawZip;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * Task 5.5: one test per violation kind {@link PluginValidator} raises, one valid jar that passes
 * clean, and the static-initialiser marker proving inspection never runs plugin code.
 */
class PluginValidatorTest {

    private static final String MARKER_PROPERTY = "rahdar.test.plugin-code-ran";

    private static PluginValidator validator() {
        return validator("2026.03.15");
    }

    private static PluginValidator validator(String rahdarVersionOverride) {
        ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);
        when(noBuildInfo.getIfAvailable()).thenReturn(null);
        var rahdarVersion = new RahdarVersion(
                noBuildInfo, new PluginProperties(rahdarVersionOverride, false, null, null, null, null, null));
        return new PluginValidator(new PluginDescriptorParser(), rahdarVersion);
    }

    private static List<Violation> validate(PluginJarBuilder jar) throws Exception {
        return validate(jar, validator());
    }

    private static List<Violation> validate(PluginJarBuilder jar, PluginValidator validator) throws Exception {
        return validator.validate(jar.build(), Set.of()).violations();
    }

    private static boolean has(List<Violation> violations, String code) {
        return violations.stream().anyMatch(v -> v.code().equals(code));
    }

    @Test
    void aValidPluginPassesClean() throws Exception {
        var jar = validPlugin("acme-notes");
        ValidationReport report = validator().validate(jar.build(), Set.of());
        assertThat(report.errors()).isEmpty();
        assertThat(report.descriptor().id()).isEqualTo("acme-notes");
    }

    @Test
    void noPluginCodeEverRuns() throws Exception {
        System.clearProperty(MARKER_PROPERTY);
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.Evil", """
                        package com.acme.acme_notes;
                        public class Evil {
                            static {
                                System.setProperty("%s", "true");
                            }
                        }
                        """.formatted(MARKER_PROPERTY));
        validate(jar);
        assertThat(System.getProperty(MARKER_PROPERTY)).isNull();
    }

    @Test
    void jarTooLarge() throws Exception {
        Path oversized = Files.createTempFile("oversize", ".jar");
        try (RandomAccessFile raf = new RandomAccessFile(oversized.toFile(), "rw")) {
            raf.setLength(51L * 1024 * 1024);
        }
        assertThat(has(validator().validate(oversized, Set.of()).violations(), "jar-too-large"))
                .isTrue();
    }

    @Test
    void duplicateEntryName() throws Exception {
        byte[] zip = RawZip.build(
                List.of(new RawZip.Entry("a.txt", "one".getBytes()), new RawZip.Entry("a.txt", "two".getBytes())));
        Path jar = Files.createTempFile("dup", ".jar");
        Files.write(jar, zip);
        assertThat(has(validator().validate(jar, Set.of()).violations(), "jar-duplicate-entry"))
                .isTrue();
    }

    @Test
    void centralDirectoryThatHidesAnEntryIsRefused() throws Exception {
        byte[] zip = RawZip.buildWithCentralSubset(
                List.of(
                        new RawZip.Entry("a.txt", "seen by both".getBytes()),
                        new RawZip.Entry("hidden.txt", "seen only by a sequential reader".getBytes())),
                Set.of("a.txt"));
        Path jar = Files.createTempFile("confused", ".jar");
        Files.write(jar, zip);
        assertThat(has(validator().validate(jar, Set.of()).violations(), "jar-inconsistent"))
                .isTrue();
    }

    @Test
    void zipSlipEntryName() throws Exception {
        var jar = validPlugin("acme-notes").entry("../evil.txt", "x");
        assertThat(has(validate(jar), "jar-unsafe-entry-name")).isTrue();
    }

    @Test
    void nestedArchiveRejected() throws Exception {
        var jar = validPlugin("acme-notes").entry("com/acme/acme_notes/lib.jar", new byte[] {1, 2, 3});
        assertThat(has(validate(jar), "jar-nested-archive")).isTrue();
    }

    @Test
    void deniedManifestAttribute() throws Exception {
        var jar = validPlugin("acme-notes").manifestAttribute("Class-Path", "other.jar");
        assertThat(has(validate(jar), "manifest-attribute-denied")).isTrue();
    }

    @Test
    void missingDescriptor() throws Exception {
        // The builder always writes a plugin.json, so a jar with none at all is built by hand.
        Path noDescriptor = Files.createTempFile("no-descriptor", ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(noDescriptor))) {
            out.putNextEntry(new JarEntry("com/acme/notes/Marker.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        assertThat(has(validator().validate(noDescriptor, Set.of()).violations(), "descriptor-missing"))
                .isTrue();
    }

    @Test
    void invalidDescriptorJson() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("thisFieldDoesNotExist", "boom");
        assertThat(has(validate(jar), "descriptor-invalid")).isTrue();
    }

    @Test
    void entryOutsideAllowlist() throws Exception {
        var jar = validPlugin("acme-notes").entry("com/other/Stray.txt", "x");
        assertThat(has(validate(jar), "entry-not-allowlisted")).isTrue();
    }

    @Test
    void invalidId() throws Exception {
        var jar = validPlugin("acmenotes"); // only one kebab segment
        assertThat(has(validate(jar), "id-invalid")).isTrue();
    }

    @Test
    void reservedIdPrefix() throws Exception {
        var jar = validPlugin("identity-notes");
        assertThat(has(validate(jar), "id-reserved")).isTrue();
    }

    @Test
    void basePackageOverlapsReservedPrefix() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("basePackage", "org.springframework.acme");
        assertThat(has(validate(jar), "base-package-overlap")).isTrue();
    }

    /** The broker SDK and every installed driver's own client libraries are the host's, never a plugin's. */
    @ParameterizedTest
    @ValueSource(strings = {"io.github.sudoitir.openbroker.acme", "org.apache.activemq.acme", "org.jolokia.acme"})
    void basePackageOverlapsTheBrokerSdkOrADriversClient(String basePackage) throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("basePackage", basePackage);
        assertThat(has(validate(jar), "base-package-overlap")).isTrue();
    }

    @Test
    void basePackageOverlapsAnotherPlugin() throws Exception {
        var jar = validPlugin("acme-notes");
        var violations = validator()
                .validate(jar.build(), Set.of("com.acme.acme_notes.sub"))
                .violations();
        assertThat(has(violations, "base-package-overlap")).isTrue();
    }

    @Test
    void contractMismatch() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("contract", 999);
        assertThat(has(validate(jar), "contract-mismatch")).isTrue();
    }

    @Test
    void pluginBuiltForThePreviousContractIsRefused() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("contract", Contract.VERSION - 1);
        assertThat(has(validate(jar), "contract-mismatch")).isTrue();
    }

    @Test
    void rahdarSinceInvalid() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("rahdar", Map.of("since", "not-a-version"));
        assertThat(has(validate(jar), "rahdar-since-invalid")).isTrue();
    }

    @Test
    void rahdarVersionTooOld() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("rahdar", Map.of("since", "2099.01.0"));
        assertThat(has(validate(jar), "rahdar-too-old")).isTrue();
    }

    @Test
    void rahdarVersionUnknownIsOnlyAWarning() throws Exception {
        var jar = validPlugin("acme-notes");
        var report = validator("").validate(jar.build(), Set.of());
        assertThat(has(report.violations(), "rahdar-version-unknown")).isTrue();
        assertThat(report.errors()).isEmpty();
        assertThat(report.valid()).isTrue();
    }

    private static List<Violation> validatePermission(Map<String, Object> permission) throws Exception {
        return validate(validPlugin("acme-notes").descriptorField("permissions", List.of(permission)));
    }

    @Test
    void aPermissionDeclaringGlobalOnlyIsRefusedByNameWithTheScopeToUseInstead() throws Exception {
        var violations = validatePermission(
                Map.of("action", "acme-notes:admin", "description", "x", "scope", "global", "globalOnly", true));

        assertThat(violations)
                .filteredOn(v -> v.code().equals("permission-global-only"))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.message()).contains("acme-notes:admin", "globalOnly");
                    assertThat(v.authorFix()).contains("scope");
                });
    }

    @Test
    void aPermissionWithoutAScopeIsRefused() throws Exception {
        var violations = validatePermission(Map.of("action", "acme-notes:read", "description", "x"));

        assertThat(has(violations, "permission-scope")).isTrue();
    }

    @Test
    void aPermissionWithAnUnknownScopeIsRefused() throws Exception {
        var violations =
                validatePermission(Map.of("action", "acme-notes:read", "description", "x", "scope", "installation"));

        assertThat(has(violations, "permission-scope")).isTrue();
    }

    @Test
    void aResourcePermissionNeedsAtLeastOneKnownKind() throws Exception {
        var none = validatePermission(Map.of(
                "action", "acme-notes:read", "description", "x", "scope", "resource", "resourceTypes", List.of()));
        var unknown = validatePermission(Map.of(
                "action", "acme-notes:read",
                "description", "x",
                "scope", "resource",
                "resourceTypes", List.of("topic")));

        assertThat(has(none, "permission-resource-types")).isTrue();
        assertThat(has(unknown, "permission-resource-types")).isTrue();
    }

    @Test
    void aNonResourcePermissionMayNotNameKinds() throws Exception {
        var violations = validatePermission(Map.of(
                "action", "acme-notes:read",
                "description", "x",
                "scope", "cluster",
                "resourceTypes", List.of("subscription")));

        assertThat(has(violations, "permission-resource-types")).isTrue();
    }

    @Test
    void permissionScopesAndRequirementsThatAreWellFormedPassClean() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "permissions",
                        List.of(
                                Map.of(
                                        "action", "acme-notes:read",
                                        "description", "x",
                                        "scope", "resource",
                                        "resourceTypes", List.of("subscription", "destination")),
                                Map.of(
                                        "action", "acme-notes:write",
                                        "description", "x",
                                        "scope", "resource",
                                        "resourceTypes", List.of("subscription"),
                                        "requires", List.of("acme-notes:read", "subscription:read")),
                                Map.of("action", "acme-notes:admin", "description", "x", "scope", "global")));

        assertThat(validate(jar)).noneMatch(v -> v.code().startsWith("permission-"));
    }

    @Test
    void permissionNamespaceViolation() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "permissions", List.of(Map.of("action", "other:read", "description", "x", "scope", "cluster")));
        assertThat(has(validate(jar), "permission-namespace")).isTrue();
    }

    @Test
    void settingNamespaceViolation() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("settingKeys", List.of("other.key"));
        assertThat(has(validate(jar), "setting-namespace")).isTrue();
    }

    @Test
    void topicNamespaceViolation() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("streamTopics", List.of("other"));
        assertThat(has(validate(jar), "topic-namespace")).isTrue();
    }

    @Test
    void mcpToolNamespaceViolation() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of(
                                "name", "other_tool",
                                "posture", "read",
                                "scope", "global",
                                "permission", "acme-notes:read",
                                "description", "x")));
        assertThat(has(validate(jar), "mcp-tool-namespace")).isTrue();
    }

    @Test
    void mcpToolGuardedByAnUndeclaredPermissionIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of(
                                "name", "acme_notes_search",
                                "posture", "read",
                                "scope", "cluster",
                                "permission", "acme-notes:admin",
                                "description", "x")));
        assertThat(has(validate(jar), "mcp-tool-permission")).isTrue();
    }

    @Test
    void mcpToolWithoutAPermissionIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of(
                                "name",
                                "acme_notes_search",
                                "posture",
                                "read",
                                "scope",
                                "cluster",
                                "description",
                                "x")));
        assertThat(validate(jar)).isNotEmpty();
    }

    @Test
    void mcpToolWhoseScopeIsNotItsPermissionsScopeIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of(
                                "name", "acme_notes_search",
                                "posture", "read",
                                "scope", "global",
                                "permission", "acme-notes:read",
                                "description", "x")));
        assertThat(validate(jar))
                .filteredOn(v -> v.code().equals("mcp-tool-scope"))
                .singleElement()
                .satisfies(v -> assertThat(v.message())
                        .contains("acme_notes_search")
                        .contains("global")
                        .contains("cluster"));
    }

    private static PluginJarBuilder resourcePlugin(Map<String, Object> tool) {
        return validPlugin("acme-notes")
                .descriptorField(
                        "permissions",
                        List.of(Map.of(
                                "action",
                                "acme-notes:read",
                                "description",
                                "Read notes",
                                "scope",
                                "resource",
                                "resourceTypes",
                                List.of("subscription"))))
                .descriptorField("mcpTools", List.of(tool));
    }

    private static Map<String, Object> resourceTool(String scope, String arg, String kind) {
        var tool = new LinkedHashMap<String, Object>();
        tool.put("name", "acme_notes_search");
        tool.put("posture", "read");
        tool.put("scope", scope);
        tool.put("permission", "acme-notes:read");
        if (arg != null) {
            tool.put("resourceArg", arg);
        }
        if (kind != null) {
            tool.put("resourceType", kind);
        }
        tool.put("description", "x");
        return tool;
    }

    @Test
    void aResourceToolNamingItsArgumentAndKindPassesClean() throws Exception {
        var violations = validate(resourcePlugin(resourceTool("resource", "queue", "subscription")));
        assertThat(violations).noneMatch(v -> v.code().startsWith("mcp-tool"));
    }

    @Test
    void aResourceToolWithoutItsArgumentOrKindIsRefused() throws Exception {
        assertThat(has(validate(resourcePlugin(resourceTool("resource", null, null))), "mcp-tool-resource"))
                .isTrue();
        assertThat(has(validate(resourcePlugin(resourceTool("resource", "queue", null))), "mcp-tool-resource"))
                .isTrue();
    }

    @Test
    void aToolThatIsNotAResourceToolMayNotNameAResource() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField("mcpTools", List.of(resourceTool("cluster", "queue", "subscription")));
        assertThat(has(validate(jar), "mcp-tool-resource")).isTrue();
    }

    @Test
    void aResourceToolMustNameAKindItsPermissionActsOn() throws Exception {
        var violations = validate(resourcePlugin(resourceTool("resource", "address", "destination")));
        assertThat(violations)
                .filteredOn(v -> v.code().equals("mcp-tool-resource"))
                .singleElement()
                .satisfies(v -> assertThat(v.message()).contains("destination").contains("subscription"));
    }

    private static Map<String, String> metric(String name, String unit, String permission) {
        return Map.of("name", name, "description", "x", "unit", unit, "subject", "note", "permission", permission);
    }

    @Test
    void aDeclaredMetricAndItsRulePassClean() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "permissions",
                        List.of(
                                Map.of("action", "acme-notes:read", "description", "x", "scope", "cluster"),
                                Map.of("action", "acme-notes:stats", "description", "x", "scope", "cluster")))
                .descriptorField("metrics", List.of(metric("acme-notes:edits", "count", "acme-notes:stats")))
                .descriptorField(
                        "alertRules",
                        List.of(Map.of(
                                "key", "many-edits",
                                "name", "Many edits",
                                "metric", "acme-notes:edits",
                                "comparator", "GT",
                                "threshold", 5,
                                "forSeconds", 60,
                                "severity", "WARNING")));
        assertThat(validate(jar)).isEmpty();
    }

    @Test
    void metricOutsideTheNamespaceOrWithAnUnknownUnitIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", "acme-notes:stats", "description", "x", "scope", "cluster")))
                .descriptorField("metrics", List.of(metric("other:edits", "furlongs", "acme-notes:stats")));
        var violations = validate(jar);
        assertThat(has(violations, "metric-name")).isTrue();
        assertThat(has(violations, "metric-unit")).isTrue();
    }

    @Test
    void metricReadWithAnUndeclaredPermissionIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField("metrics", List.of(metric("acme-notes:edits", "count", "acme-notes:stats")));
        assertThat(has(validate(jar), "metric-permission")).isTrue();
    }

    @Test
    void alertRuleOnAnUndeclaredMetricIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "alertRules",
                        List.of(Map.of(
                                "key", "k",
                                "name", "n",
                                "metric", "acme-notes:edits",
                                "comparator", "GT",
                                "threshold", 1,
                                "forSeconds", 0,
                                "severity", "INFO")));
        assertThat(has(validate(jar), "alert-rule-metric")).isTrue();
    }

    @Test
    void anApprovalProviderNamingADeclaredPermissionIsAccepted() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField("approvalProvider", Map.of("approverPermission", "acme-notes:read"));
        assertThat(validate(jar)).isEmpty();
    }

    @Test
    void anApprovalProviderWithoutAnApproverPermissionIsRefused() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("approvalProvider", Map.of());
        assertThat(has(validate(jar), "approval-provider-permission")).isTrue();
    }

    @Test
    void anApprovalProviderNamingAPermissionTheyDoNotDeclareIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField("approvalProvider", Map.of("approverPermission", "subscription:purge"));
        assertThat(has(validate(jar), "approval-provider-permission")).isTrue();
    }

    @Test
    void aWellFormedIdentityProviderIsAccepted() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "identityProviders", List.of(Map.of("id", "acme-notes:corp", "label", "Corporate directory")));
        assertThat(validate(jar)).isEmpty();
    }

    @Test
    void anIdentityProviderOutsideTheNamespaceIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField("identityProviders", List.of(Map.of("id", "other:corp", "label", "Corporate")));
        assertThat(has(validate(jar), "identity-provider-id")).isTrue();
    }

    @Test
    void anIdentityProviderDeclaredTwiceIsRefused() throws Exception {
        var jar = validPlugin("acme-notes")
                .descriptorField(
                        "identityProviders",
                        List.of(
                                Map.of("id", "acme-notes:corp", "label", "One"),
                                Map.of("id", "acme-notes:corp", "label", "Two")));
        assertThat(has(validate(jar), "identity-provider-duplicate")).isTrue();
    }

    @Test
    void anIdentityProviderLabelMustBeBetweenOneAndSixtyFourCharacters() throws Exception {
        for (String label : List.of("", " ", "x".repeat(65))) {
            var jar = validPlugin("acme-notes")
                    .descriptorField("identityProviders", List.of(Map.of("id", "acme-notes:corp", "label", label)));
            assertThat(has(validate(jar), "identity-provider-label"))
                    .as("label '%s'", label)
                    .isTrue();
        }
    }

    @Test
    void deniedAnnotationScheduled() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.Job", """
                        package com.acme.acme_notes;
                        import org.springframework.scheduling.annotation.Scheduled;
                        public class Job {
                            @Scheduled(fixedRate = 1000)
                            public void run() {}
                        }
                        """);
        assertThat(has(validate(jar), "bytecode-denied-annotation")).isTrue();
    }

    @Test
    void deniedCallSystemExit() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.Bad", """
                        package com.acme.acme_notes;
                        public class Bad {
                            public void kill() { System.exit(1); }
                        }
                        """);
        assertThat(has(validate(jar), "bytecode-denied-call")).isTrue();
    }

    @Test
    void mappingPathOutsideGateway() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.NotesController", """
                        package com.acme.acme_notes;
                        import org.springframework.web.bind.annotation.GetMapping;
                        import org.springframework.web.bind.annotation.RestController;
                        @RestController
                        public class NotesController {
                            @GetMapping("/api/v1/other/path")
                            public String notes() { return "x"; }
                        }
                        """);
        assertThat(has(validate(jar), "bytecode-mapping-path")).isTrue();
    }

    @Test
    void aClassLevelPrefixCountsTowardsTheGatewayPath() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.NotesController", """
                        package com.acme.acme_notes;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/v1/clusters/{clusterId}/p/acme-notes")
                        public class NotesController {
                            @GetMapping("/notes")
                            public String notes() { return "x"; }
                            @PostMapping
                            public String add() { return "x"; }
                        }
                        """);
        assertThat(has(validate(jar), "bytecode-mapping-path")).isFalse();
    }

    @Test
    void aMethodWithNoPathMapsItsClassPrefixAndIsCheckedToo() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.NotesController", """
                        package com.acme.acme_notes;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/v1")
                        public class NotesController {
                            @GetMapping
                            public String root() { return "x"; }
                        }
                        """);
        assertThat(has(validate(jar), "bytecode-mapping-path")).isTrue();
    }

    @Test
    void configurationPropertiesPrefixMismatch() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.NotesProperties", """
                        package com.acme.acme_notes;
                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        @ConfigurationProperties(prefix = "something.else")
                        public class NotesProperties {}
                        """);
        assertThat(has(validate(jar), "bytecode-configuration-properties-prefix"))
                .isTrue();
    }

    @Test
    void multiReleaseOverrideIsCheckedLikeItsBaseEntry() throws Exception {
        // A clean class at the normal path, but a malicious override under
        // META-INF/versions/25/... for the exact same class — the JVM would substitute this one
        // in at load time, so it must be checked exactly as the base entry is (the bypass the
        // BytecodeChecks scan used to miss: it only ever looked at the literal basePackage path).
        String fqcn = "com.acme.acme_notes.Config";
        byte[] malicious = PluginJarBuilder.compileOne(fqcn, """
                        package com.acme.acme_notes;
                        public class Config {
                            public void boom() { System.exit(1); }
                        }
                        """);
        var jar = validPlugin("acme-notes")
                .source(fqcn, """
                        package com.acme.acme_notes;
                        public class Config {}
                        """)
                .manifestAttribute("Multi-Release", "true")
                .entry("META-INF/versions/25/com/acme/acme_notes/Config.class", malicious);
        assertThat(has(validate(jar), "bytecode-denied-call")).isTrue();
    }

    @Test
    void configurationClassMissingComponentScan() throws Exception {
        var jar = validPlugin("acme-notes").source("com.acme.acme_notes.PluginConfig", """
                        package com.acme.acme_notes;
                        import org.springframework.context.annotation.Configuration;
                        @Configuration
                        public class PluginConfig {}
                        """);
        assertThat(has(validate(jar), "configuration-missing-component-scan")).isTrue();
    }

    @Test
    void configurationOutsideBasePackage() throws Exception {
        var jar = validPlugin("acme-notes").descriptorField("configuration", "com.other.PluginConfig");
        assertThat(has(validate(jar), "configuration-outside-base-package")).isTrue();
    }

    @Test
    void configurationNotFound() throws Exception {
        var jar = validPlugin("acme-notes")
                .withoutDefaultConfiguration()
                .descriptorField("configuration", "com.acme.acme_notes.DoesNotExist");
        assertThat(has(validate(jar), "configuration-not-found")).isTrue();
    }

    @Test
    void changelogRunInTransactionFalseRejected() throws Exception {
        var jar = validPlugin("acme-notes").changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <changeSet id="1" author="acme" runInTransaction="false">
                                <createTable tableName="acme_notes_note">
                                    <column name="id" type="uuid"/>
                                </createTable>
                            </changeSet>
                        </databaseChangeLog>
                        """);
        assertThat(has(validate(jar), "changelog-run-in-transaction-false")).isTrue();
    }

    @Test
    void changelogThatRunsACommandIsRejected() throws Exception {
        var jar = validPlugin("acme-notes").changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <changeSet id="1" author="acme">
                                <executeCommand executable="/bin/sh"><arg value="-c"/><arg value="id"/></executeCommand>
                            </changeSet>
                        </databaseChangeLog>
                        """);
        assertThat(has(validate(jar), "changelog-change-denied")).isTrue();
    }

    /** A minimal, otherwise fully-valid plugin: every violation test starts here and breaks one thing. */
    private static PluginJarBuilder validPlugin(String id) {
        return new PluginJarBuilder(id)
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", id + ":read", "description", "Read notes", "scope", "cluster")))
                .descriptorField("settingKeys", List.of(id + ".enabled"))
                .descriptorField("streamTopics", List.of(id))
                .descriptorField(
                        "mcpTools",
                        List.of(Map.of(
                                "name",
                                id.replace('-', '_') + "_search",
                                "posture",
                                "read",
                                "scope",
                                "cluster",
                                "permission",
                                id + ":read",
                                "description",
                                "Search notes")));
    }
}
