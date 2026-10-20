package io.github.sudoitir.rahdar.kernel.plugin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor.Kind;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.rahdar.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.rahdar.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.rahdar.kernel.plugin.web.ManifestController;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The manifest lists every built-in module with its enabled flag and attributes the permission
 * catalogue to enabled modules and <em>active</em> plugins only, plus (task 6.9) each plugin's
 * {@code origin}, {@code version}, {@code vendor}, {@code status} and, when it has a UI and is
 * active, its {@code ui.entry} — and the top-level {@code safeMode} flag. Authentication is
 * enforced by the filter chain and swept for every endpoint by {@code EndpointProtectionTest}.
 */
class ManifestControllerTest {

    private static final String DESCRIPTOR_JSON = """
            {"schemaVersion":1,"id":"acme-notes","name":"acme-notes","version":"1.2.0",
            "vendor":{"name":"Acme"},"basePackage":"com.acme.notes",
            "configuration":"com.acme.notes.PluginConfig","contract":3,
            "rahdar":{"since":"2026.01.0"},"ui":true,"activation":"AUTO","title":"Notes",
            "permissions":[{"action":"acme-notes:write","description":"Write notes","scope":"cluster"}],
            "streamTopics":["acme-notes"]}
            """;

    @Test
    void listsEnabledAndDisabledFeaturesAndCataloguesOnlyEnabledPermissions() throws Exception {
        var env = new MockEnvironment().withProperty("rahdar.features.sql.enabled", "false");
        var registry = new FeatureRegistry(
                new InstalledFeatures(List.of(
                        FeatureDescriptor.builder()
                                .id("queues")
                                .title("Queues")
                                .kind(Kind.FEATURE)
                                .permission(PermissionDef.cluster("subscription:create", "Create queues"))
                                .streamTopic(TopicDef.signal("queues"))
                                .build(),
                        FeatureDescriptor.builder()
                                .id("sql")
                                .title("SQL console")
                                .kind(Kind.FEATURE)
                                .permission(PermissionDef.cluster("capture:write", "Turn capture on"))
                                .build())),
                env,
                event -> {});
        MockMvc mvc = mvc(registry, List.of(), false);

        mvc.perform(get("/api/v1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract").value(Contract.VERSION))
                .andExpect(jsonPath("$.safeMode").value(false))
                .andExpect(jsonPath("$.features.length()").value(2))
                .andExpect(jsonPath("$.features[0].id").value("queues"))
                .andExpect(jsonPath("$.features[0].enabled").value(true))
                .andExpect(jsonPath("$.features[0].origin").value("BUILTIN"))
                .andExpect(jsonPath("$.features[0].topics[0]").value("queues"))
                .andExpect(jsonPath("$.features[1].id").value("sql"))
                .andExpect(jsonPath("$.features[1].enabled").value(false))
                .andExpect(jsonPath("$.features[1].enabledProperty").value("rahdar.features.sql.enabled"))
                .andExpect(jsonPath("$.features[1].permissions[0]").value("capture:write"))
                .andExpect(jsonPath("$.permissionCatalogue.length()").value(1))
                .andExpect(jsonPath("$.permissionCatalogue[0].action").value("subscription:create"))
                .andExpect(jsonPath("$.permissionCatalogue[0].featureId").value("queues"))
                .andExpect(jsonPath("$.identityProviders.length()").value(0));
    }

    @Test
    void activePluginEntryCarriesVersionVendorStatusAndUiEntryAndItsPermissionsAreCatalogued() throws Exception {
        var registry = new FeatureRegistry(new InstalledFeatures(List.of()), new MockEnvironment(), event -> {});
        registry.addPlugin(new PluginDescriptorParser().parse(DESCRIPTOR_JSON.getBytes(StandardCharsets.UTF_8)));

        PluginInstallEntity entity =
                new PluginInstallEntity("acme-notes", "1.2.0", "Acme", "a".repeat(64), "root", DESCRIPTOR_JSON);
        entity.transitionTo(PluginInstallStatus.ACTIVE);

        MockMvc mvc = mvc(registry, List.of(entity), false);

        mvc.perform(get("/api/v1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0].id").value("acme-notes"))
                .andExpect(jsonPath("$.features[0].origin").value("PLUGIN"))
                .andExpect(jsonPath("$.features[0].version").value("1.2.0"))
                .andExpect(jsonPath("$.features[0].vendor").value("Acme"))
                .andExpect(jsonPath("$.features[0].status").value("active"))
                .andExpect(jsonPath("$.features[0].enabled").value(true))
                .andExpect(jsonPath("$.features[0].ui.entry")
                        .value("/plugin-ui/acme-notes/" + "a".repeat(8) + "/remoteEntry.js"))
                .andExpect(jsonPath("$.permissionCatalogue.length()").value(1))
                .andExpect(jsonPath("$.permissionCatalogue[0].action").value("acme-notes:write"))
                .andExpect(jsonPath("$.permissionCatalogue[0].featureId").value("acme-notes"));
    }

    @Test
    void inactivePluginHasNoUiEntryAndNoCataloguedPermissions() throws Exception {
        var registry = new FeatureRegistry(new InstalledFeatures(List.of()), new MockEnvironment(), event -> {});
        // Not attached via registry.addPlugin: an inactive plugin never has a FeatureRegistry entry.
        PluginInstallEntity entity =
                new PluginInstallEntity("acme-notes", "1.2.0", "Acme", "a".repeat(64), "root", DESCRIPTOR_JSON);
        entity.fail("boom");

        MockMvc mvc = mvc(registry, List.of(entity), false);

        mvc.perform(get("/api/v1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0].status").value("failed"))
                .andExpect(jsonPath("$.features[0].enabled").value(false))
                .andExpect(jsonPath("$.features[0].ui").doesNotExist())
                .andExpect(jsonPath("$.permissionCatalogue.length()").value(0));
    }

    @Test
    void safeModeFlagReflectsTheHost() throws Exception {
        var registry = new FeatureRegistry(new InstalledFeatures(List.of()), new MockEnvironment(), event -> {});
        MockMvc mvc = mvc(registry, List.of(), true);

        mvc.perform(get("/api/v1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeMode").value(true));
    }

    private static MockMvc mvc(FeatureRegistry registry, List<PluginInstallEntity> plugins, boolean safeMode) {
        PluginInstallRepository installs = Mockito.mock(PluginInstallRepository.class);
        when(installs.findAll()).thenReturn(plugins);
        PluginHost host = Mockito.mock(PluginHost.class);
        when(host.safeMode()).thenReturn(safeMode);
        return MockMvcBuilders.standaloneSetup(new ManifestController(
                        registry,
                        installs,
                        new PluginDescriptorParser(),
                        host,
                        new StaticListableBeanFactory().getBeanProvider(IdentityProviderListing.class)))
                // What ApiVersioningConfig adds in the application.
                .setCustomHandlerMapping(() -> {
                    var mapping = new RequestMappingHandlerMapping();
                    mapping.setPathPrefixes(Map.of("/api/v1", type -> true));
                    return mapping;
                })
                .build();
    }
}
