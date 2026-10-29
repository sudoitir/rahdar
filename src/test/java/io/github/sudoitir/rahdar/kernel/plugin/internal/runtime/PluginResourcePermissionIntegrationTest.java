package io.github.sudoitir.rahdar.kernel.plugin.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceNames;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.internal.RoleService;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * A plugin's {@code resource} permission is decided by the same resolver as Rahdar's own (plugin-runtime
 * spec): a team role that holds it gives it on the queues the team owns and nowhere else, and the filter a
 * plugin builds for a list reports it among the actions a row allows.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class PluginResourcePermissionIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    PermissionResolver perm;

    @Autowired
    TeamService teams;

    @Autowired
    RoleService roleService;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    private PluginRuntime activeRuntime;

    @AfterEach
    void cleanUp() {
        if (activeRuntime != null) {
            activeRuntime.close();
            registry.remove(activeRuntime.id());
        }
    }

    @Test
    void aTeamRoleHoldingAPluginsResourcePermissionGivesItOnTheTeamsQueuesOnly() throws Exception {
        String id = "acme-peek-" + Math.abs(new SecureRandom().nextInt());
        activate(id);
        UUID cluster = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "peek-" + UUID.randomUUID(), null, null))
                .getId();
        var role = roleService.create(new RoleRequest(
                "peek-reader-" + UUID.randomUUID(), List.of("subscription:read", id + ":read"), false, true));
        UUID orders = teams.create("orders-" + UUID.randomUUID()).id();
        teams.addPattern(orders, new PatternRequest(cluster, PatternKind.SUBSCRIPTION, "orders.#"));
        AppUserEntity user = AppUserEntity.local("peeker-" + UUID.randomUUID(), null, "{noop}x");
        user.setMustChangePassword(false);
        UUID member = users.save(user).getId();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, member, null, null, role.id()));
        RahdarPrincipal principal = RahdarPrincipal.live(member, "peeker", false);

        assertThat(perm.can(principal, cluster, ResourceRef.subscription("orders.in"), id + ":read"))
                .isTrue();
        assertThat(perm.can(principal, cluster, ResourceRef.subscription("billing.in"), id + ":read"))
                .isFalse();
        // The permission acts on queues, so it does not reach an address of the same name.
        assertThat(perm.can(principal, cluster, ResourceRef.destination("orders.in"), id + ":read"))
                .isFalse();
        // And a team gives nothing cluster-wide.
        assertThat(perm.can(principal, cluster, id + ":read")).isFalse();
    }

    @Test
    void theFilterAPluginBuildsListsTheActionsARowAllowsIncludingThePluginsOwn() throws Exception {
        String id = "acme-peek-" + Math.abs(new SecureRandom().nextInt());
        activate(id);

        var filter = perm.filter(UUID.randomUUID(), ResourceType.SUBSCRIPTION);

        // As the signed-in test administrator, who holds everything on every cluster.
        assertThat(filter.everything()).isTrue();
        assertThat(filter.readable("anything")).isTrue();
        assertThat(filter.allowedActions("orders.in")).contains(id + ":read", "subscription:read");
    }

    private void activate(String id) throws Exception {
        Path jar = new PluginJarBuilder(id)
                .descriptorField("basePackage", "com.acme.peek")
                .descriptorField("configuration", "com.acme.peek.Config")
                .descriptorField(
                        "permissions",
                        List.of(Map.of(
                                "action",
                                id + ":read",
                                "scope",
                                "resource",
                                "resourceTypes",
                                List.of("subscription"),
                                "requires",
                                List.of("subscription:read"))))
                .source("com.acme.peek.Config", """
                        package com.acme.peek;
                        import org.springframework.context.annotation.*;
                        @Configuration
                        public class Config {}
                        """)
                .build();
        PluginDescriptor descriptor;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            descriptor = descriptorParser.parse(jarFile.getInputStream(jarFile.getEntry("META-INF/rahdar/plugin.json"))
                    .readAllBytes());
        }
        activeRuntime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        registry.set(descriptor.id(), new PluginRuntimeRegistry.Active(activeRuntime));
    }
}
