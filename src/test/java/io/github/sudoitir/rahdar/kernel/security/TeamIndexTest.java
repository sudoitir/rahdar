package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** The index is rebuilt after a change, and never keeps what was read from before one (design D12). */
class TeamIndexTest {

    final TeamRepository teams = mock(TeamRepository.class);
    final TeamPatternRepository patterns = mock(TeamPatternRepository.class);
    final TeamShareRepository shares = mock(TeamShareRepository.class);
    final RolePermissionRepository rolePermissions = mock(RolePermissionRepository.class);
    final TeamIndex index = new TeamIndex(teams, patterns, shares, rolePermissions);

    final UUID cluster = UUID.randomUUID();
    final UUID orders = UUID.randomUUID();
    final List<TeamPatternEntity> rows = new ArrayList<>();

    @Test
    void anIndexReadFromDataThatChangedWhileItWasBeingReadIsNotKept() {
        List<TeamPatternEntity> before = List.of();
        when(shares.findAll()).thenReturn(List.of());
        when(patterns.findAll()).thenAnswer(read -> {
            // A change commits and is announced while this read is in flight, so what it returns is old.
            index.invalidate();
            rows.add(new TeamPatternEntity(orders, cluster, "ANY", "orders.#"));
            return before;
        });

        assertThat(index.ownerOf(cluster, ResourceRef.subscription("orders.in")))
                .as("this call may still be answered from what it read")
                .isEmpty();

        when(patterns.findAll()).thenAnswer(read -> List.copyOf(rows));
        assertThat(index.ownerOf(cluster, ResourceRef.subscription("orders.in")))
                .as("but the next call reads again and sees the change")
                .contains(orders);
    }

    @Test
    void aBuiltIndexIsKeptUntilItIsInvalidated() {
        rows.add(new TeamPatternEntity(orders, cluster, "ANY", "orders.#"));
        when(patterns.findAll()).thenAnswer(read -> List.copyOf(rows));
        when(shares.findAll()).thenReturn(List.of());
        assertThat(index.ownerOf(cluster, ResourceRef.subscription("orders.in")))
                .contains(orders);

        rows.clear();
        assertThat(index.ownerOf(cluster, ResourceRef.subscription("orders.in")))
                .contains(orders);

        index.invalidate();
        assertThat(index.ownerOf(cluster, ResourceRef.subscription("orders.in")))
                .isEmpty();
        Mockito.verify(rolePermissions, Mockito.never()).findByIdRoleId(any());
    }

    @Test
    void theOwnerTeamIsNamedAndARenameShowsOnceTheIndexIsInvalidated() {
        TeamEntity team = new TeamEntity("Orders");
        ReflectionTestUtils.setField(team, "id", orders);
        rows.add(new TeamPatternEntity(orders, cluster, "ANY", "orders.#"));
        when(patterns.findAll()).thenAnswer(read -> List.copyOf(rows));
        when(shares.findAll()).thenReturn(List.of());
        when(teams.findAll()).thenAnswer(read -> List.of(team));

        assertThat(index.ownerTeamOf(cluster, ResourceRef.subscription("orders.in")))
                .contains(new TeamRef(orders, "Orders"));
        assertThat(index.ownerTeamOf(cluster, ResourceRef.destination("billing.in")))
                .isEmpty();

        team.setName("Orders and returns");
        index.invalidate();

        assertThat(index.ownerTeamOf(cluster, ResourceRef.subscription("orders.in")))
                .contains(new TeamRef(orders, "Orders and returns"));
    }
}
