import type { ResourceRef } from '../../kernel/api/resourceId.ts';
import { useMemo, useState } from 'react';
import { Drawer, Group, Select, Stack, Text } from '@mantine/core';

import { OwnerChip } from '../../kernel/auth/OwnerChip.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { AccessCheckDrawer } from './AccessCheckDrawer.tsx';
import { useResourceAccess, useUsers, type ResourceTeamGrant } from './api.ts';
import { resourceGrantColumns } from './columns.ts';

const rowKey = (g: ResourceTeamGrant) => `${g.source}:${g.teamId}:${g.roleName}:${g.pattern ?? ''}`;

/**
 * Who may act on one subscription or destination (team-access spec): the team that owns it and every team a share gives a role
 * on it, with how many people hold each. For a user administrator or the administrator of a team, who are the people
 * that can act on a request for access. A user administrator can also check what one user holds on it, with the
 * source of each permission.
 */
export function ResourceAccessPanel({
  clusterId,
  kind,
  name,
}: Readonly<{ clusterId: string; kind: 'SUBSCRIPTION' | 'DESTINATION'; name: string }>) {
  const { can } = useCan();
  const mayAdminister = can('user:admin') || can('team:admin');
  const noun = kind === 'SUBSCRIPTION' ? 'subscription' : 'destination';
  const access = useResourceAccess(clusterId, kind, name, mayAdminister);
  const columns = useMemo(resourceGrantColumns, []);
  const userAdmin = can('user:admin');
  const users = useUsers(userAdmin);
  const [checking, setChecking] = useState<{ id: string; username: string } | null>(null);

  if (!mayAdminister) return null;
  return (
    <Section
      title="Access"
      headingLevel={3}
      description={`The team that owns this ${noun}, and the teams and roles that may act on it.`}
    >
      {access.isPending ? <LoadingState label="Reading who has access" blockSize="6rem" /> : null}
      {access.isError ? <ErrorState error={access.error} onRetry={() => void access.refetch()} /> : null}
      {access.data ? (
        <Stack gap="sm">
          <Group gap="xs">
            <Text size="sm">Owner team</Text>
            <OwnerChip team={access.data.ownerTeam} />
          </Group>
          <DataTable
            variant="static"
            label={`Teams and roles with access to ${name}`}
            columns={columns}
            data={access.data.grants}
            rowKey={rowKey}
            height={{ maxRows: 8 }}
            empty={
              <EmptyState
                kind="empty"
                title={`No team has access to this ${noun}`}
                description="Only a role granted on the cluster or above reaches it. A team gets access when a pattern it owns, or a share to it, covers this name."
              />
            }
          />
          {userAdmin ? (
            <Select
              label="Check one user's access"
              description="Opens every permission the user holds here, and who gives it."
              placeholder={users.isPending ? 'Loading users' : 'Choose a user'}
              data={(users.data ?? []).map((u) => ({ value: u.id, label: u.username }))}
              value={null}
              onChange={(id) => {
                const user = users.data?.find((u) => u.id === id);
                if (user) setChecking({ id: user.id, username: user.username });
              }}
              searchable
              nothingFoundMessage="No such user"
            />
          ) : null}
        </Stack>
      ) : null}
      <AccessCheckDrawer user={checking} place={{ clusterId, kind, name }} onClose={() => setChecking(null)} />
    </Section>
  );
}

/** A subscription's panel in its detail drawer. */
export function SubscriptionAccessPanel({
  clusterId,
  subscription,
}: Readonly<{ clusterId: string; subscription: ResourceRef }>) {
  return <ResourceAccessPanel clusterId={clusterId} kind="SUBSCRIPTION" name={subscription.name} />;
}

/** A destination's access, opened from its row, since a destination has no detail page of its own. */
export function DestinationAccessDrawer({
  opened,
  onClose,
  clusterId,
  destination,
}: Readonly<{ opened: boolean; onClose: () => void; clusterId: string; destination: string }>) {
  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" title={`Access to ${destination}`}>
      <ResourceAccessPanel clusterId={clusterId} kind="DESTINATION" name={destination} />
    </Drawer>
  );
}
