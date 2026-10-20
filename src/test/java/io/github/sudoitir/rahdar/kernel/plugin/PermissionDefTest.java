package io.github.sudoitir.rahdar.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A permission's scope and the kinds it acts on cannot disagree (authorization spec). */
class PermissionDefTest {

    @Test
    void aResourcePermissionNeedsAKind() {
        assertThatThrownBy(() ->
                        new PermissionDef("subscription:read", "Read", PermissionScope.RESOURCE, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("subscription:read", "resource type");
    }

    @Test
    void onlyAResourcePermissionNamesKinds() {
        Set<ResourceType> kinds = Set.of(ResourceType.SUBSCRIPTION);
        Set<String> noRequirements = Set.of();
        assertThatThrownBy(
                        () -> new PermissionDef("cluster:read", "Read", PermissionScope.CLUSTER, kinds, noRequirements))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cluster:read");
    }

    @Test
    void theFactoriesSetScopeKindsAndRequirements() {
        PermissionDef def =
                PermissionDef.resource("subscription:purge", "Purge", ResourceType.SUBSCRIPTION, "subscription:read");

        assertThat(def.scope()).isEqualTo(PermissionScope.RESOURCE);
        assertThat(def.resourceTypes()).containsExactly(ResourceType.SUBSCRIPTION);
        assertThat(def.requires()).containsExactly("subscription:read");
        assertThat(PermissionDef.global("user:admin", "Admin").requires()).isEmpty();
        assertThat(PermissionDef.cluster("cluster:write", "Write", "cluster:read")
                        .requires())
                .containsExactly("cluster:read");
    }
}
