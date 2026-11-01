import { useMemo } from 'react';
import { Button, Stack, Text } from '@mantine/core';
import { useParams, useSearch } from '@tanstack/react-router';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import type { ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable, type Column } from '../../ui/table/index.ts';
import { op, useChange, useFederationLinks, useShovels, type FederationLink, type Shovel } from './api.ts';
import { ChangeDialog } from './ChangeDialog.tsx';
import { useWriteGate } from './gate.ts';
import { FilterBar } from './FilterBar.tsx';
import type { FilterSearch } from './search.ts';
import { STATIC_SHOVEL_REASON } from './words.ts';

const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

/** A shovel the node's configuration defines cannot be deleted through the API. */
const isStatic = (s: Shovel) => s.managedBy === 'configuration';

function shovelColumns(gate: GateVerdict, onDelete: (s: Shovel) => void): Column<Shovel>[] {
  return [
    { id: 'vhost', header: 'Virtual host', accessor: (s) => s.vhost, kind: 'identifier', priority: 'high' },
    { id: 'name', header: 'Shovel', accessor: (s) => s.name, kind: 'identifier', priority: 'essential' },
    { id: 'state', header: 'State', accessor: (s) => s.state ?? 'Unknown', kind: 'status', priority: 'essential' },
    { id: 'source', header: 'Reads from', accessor: (s) => s.source, kind: 'code', priority: 'high' },
    { id: 'destination', header: 'Writes to', accessor: (s) => s.destination, kind: 'code', priority: 'high' },
    {
      id: 'delete',
      header: 'Change',
      accessor: (s) => (isStatic(s) ? 'Defined in configuration' : ''),
      cell: (s) =>
        isStatic(s) ? (
          <Text size="xs" c="dimmed">
            {STATIC_SHOVEL_REASON}
          </Text>
        ) : (
          <CapabilityGate verdict={gate} what="deleting a shovel">
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={gate.kind === 'blocked'}
              aria-label={`Delete shovel ${s.name}`}
              onClick={() => onDelete(s)}
            >
              Delete
            </Button>
          </CapabilityGate>
        ),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
  ];
}

const LINK_COLUMNS: Column<FederationLink>[] = [
  { id: 'vhost', header: 'Virtual host', accessor: (l) => l.vhost, kind: 'identifier', priority: 'high' },
  { id: 'name', header: 'Federated', accessor: (l) => l.name, kind: 'identifier', priority: 'essential' },
  { id: 'type', header: 'Type', accessor: (l) => l.type, kind: 'status', priority: 'high' },
  { id: 'upstream', header: 'Upstream', accessor: (l) => l.upstream, kind: 'identifier', priority: 'essential' },
  { id: 'status', header: 'Status', accessor: (l) => l.status ?? 'Unknown', kind: 'status', priority: 'essential' },
  { id: 'source', header: 'Reads from', accessor: (l) => l.source, kind: 'code', priority: 'low' },
];

/** What the section shows when its plugin is missing: the remedy, not an empty list. */
function Unavailable({ what, remedy }: Readonly<{ what: string; remedy?: string | null }>) {
  return (
    <Notice tone="info" title={`${what} are not available on this cluster`}>
      {remedy ?? `Enable the ${what.toLowerCase()} plugin on every node to list them here.`}
    </Notice>
  );
}

/**
 * The shovels and federation links of a RabbitMQ cluster. Each lives in a plugin; when it is not enabled the
 * section says what to enable. A shovel the node's configuration defines is listed and cannot be deleted here.
 */
export function LinksView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as FilterSearch;
  const shovels = useShovels(clusterId, search);
  const links = useFederationLinks(clusterId, search);
  const gate = useWriteGate(clusterId, 'route:write', 'Change shovels');
  const remove = useChange<Shovel>(clusterId, (s) => op(clusterId, 'DELETE', `shovels/${encodeURIComponent(s.id)}`));
  const { start: startRemove } = remove;
  const cols = useMemo(() => shovelColumns(gate, startRemove), [gate, startRemove]);
  const shovelRows = shovels.data?.pages.flatMap((p) => p.items) ?? [];
  const linkRows = links.data?.pages.flatMap((p) => p.items) ?? [];
  const firstShovels = shovels.data?.pages[0];
  const firstLinks = links.data?.pages[0];
  const doomed = remove.reviewed?.vars;

  return (
    <Page>
      <PageHeader
        title="Shovels and federation"
        description="The links that move messages between brokers and between virtual hosts."
      />
      <FilterBar label="Link filters" fields={[{ name: 'vhost', label: 'Virtual host' }]} />
      <Section title="Shovels">
        {firstShovels && !firstShovels.available ? (
          <Unavailable what="Shovels" remedy={firstShovels.remedy} />
        ) : (
          <Stack gap="sm">
            <DataTable
              variant="static"
              label="Shovels"
              storageKey="rabbitmq.shovels"
              columns={cols}
              data={shovelRows}
              rowKey={(s) => s.id}
              height={{ maxRows: 12 }}
              loading={shovels.isPending}
              error={
                shovels.isError ? (
                  <ErrorState error={shovels.error} onRetry={() => void shovels.refetch()} />
                ) : undefined
              }
              empty={
                <EmptyState
                  kind="empty"
                  title="No shovels"
                  description="A shovel reads messages from one queue or exchange and writes them to another."
                />
              }
            />
            {shovels.hasNextPage ? (
              <Button size="xs" variant="default" onClick={() => void shovels.fetchNextPage()}>
                Load more shovels
              </Button>
            ) : null}
          </Stack>
        )}
      </Section>
      <Section title="Federation links">
        {firstLinks && !firstLinks.available ? (
          <Unavailable what="Federation links" remedy={firstLinks.remedy} />
        ) : links.isPending ? (
          <LoadingState label="Loading the federation links" blockSize="6rem" />
        ) : (
          <DataTable
            variant="static"
            label="Federation links"
            storageKey="rabbitmq.federation"
            columns={LINK_COLUMNS}
            data={linkRows}
            rowKey={(l) => `${l.vhost}/${l.type}/${l.name}/${l.upstream}`}
            height={{ maxRows: 12 }}
            error={links.isError ? <ErrorState error={links.error} onRetry={() => void links.refetch()} /> : undefined}
            empty={
              <EmptyState
                kind="empty"
                title="No federation links"
                description="A federated exchange or queue reads from an upstream broker."
              />
            }
          />
        )}
      </Section>
      <ChangeDialog
        change={remove}
        title="Delete a shovel"
        action={DELETE}
        confirmLabel="Delete 1 shovel"
        destructive
        subject={doomed ? `shovel "${doomed.name}"` : 'the shovel'}
      />
    </Page>
  );
}
