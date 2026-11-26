import { useMemo, useState } from 'react';
import { Button, Text, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { SectionNav } from '../../kernel/shell/SectionNav.tsx';
import { useCluster } from '../clusters/index.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { schemaColumns } from './schemaColumns.tsx';
import { registryReason, schemaWriteGate, useSchemaSubjects } from './schemasApi.ts';
import type { SchemasSearch } from './search.ts';

/** The registry cannot be used: the driver's reason, and where to configure the connection. */
function Unavailable({ clusterId, reason }: Readonly<{ clusterId: string; reason: string }>) {
  return (
    <Notice
      tone="warning"
      title="The schema registry is unavailable"
      action={
        <Link to={`/clusters/${clusterId}/settings?tab=clusters-connection`} className={linkClasses.link}>
          Connection settings
        </Link>
      }
    >
      {reason}
    </Notice>
  );
}

function Subjects({ clusterId, q }: Readonly<{ clusterId: string; q: string }>) {
  const navigate = useNavigate();
  const cluster = useCluster(clusterId);
  const subjects = useSchemaSubjects(clusterId, q);
  const { can, loading } = useCan();
  const [draft, setDraft] = useState(q);
  const writeQ = useDebouncedCallback((value: string) => {
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, q: value || undefined }) });
  }, 250);

  const unavailable = registryReason(cluster.data?.capabilities.findings, subjects.error);
  const gate = schemaWriteGate(can('schema:write', clusterId), loading, unavailable);
  const rows = subjects.data?.pages.flatMap((page) => page.items) ?? [];
  const columns = useMemo(() => schemaColumns(clusterId), [clusterId]);
  const filtered = q.length > 0;
  const failed = subjects.isError && !unavailable;

  const register = (
    <CapabilityGate verdict={gate} what="registering a schema">
      <Button size="xs" disabled={gate.kind === 'blocked'} onClick={() => undefined}>
        Register a schema
      </Button>
    </CapabilityGate>
  );

  return (
    <>
      {unavailable ? <Unavailable clusterId={clusterId} reason={unavailable} /> : null}
      <Toolbar
        label="Subject filters"
        start={
          <TextInput
            label="Subject"
            size="xs"
            w="16rem"
            value={draft}
            placeholder="Search subjects"
            onChange={(event) => {
              setDraft(event.currentTarget.value);
              writeQ(event.currentTarget.value);
            }}
          />
        }
        end={
          <>
            {rows.length > 0 || filtered || gate.kind === 'blocked' || subjects.isPending ? register : null}
            {unavailable ? null : subjects.hasNextPage ? (
              <Button
                size="xs"
                variant="default"
                loading={subjects.isFetchingNextPage}
                onClick={() => void subjects.fetchNextPage()}
              >
                Load more subjects
              </Button>
            ) : (
              <Text size="xs" c="dimmed">
                {rows.length === 1 ? '1 subject' : `${rows.length} subjects`}
              </Text>
            )}
          </>
        }
      />
      {unavailable ? null : (
      <DataTable
        label="Schema subjects"
        storageKey="kafka.schemas"
        height="fill"
        columns={columns}
        data={rows}
        rowKey={(row) => row.name ?? ''}
        loading={subjects.isPending}
        error={failed ? <ErrorState error={subjects.error} onRetry={() => void subjects.refetch()} /> : undefined}
        empty={
          filtered ? (
            <EmptyState
              kind="filtered"
              title="No subject matches this filter"
              description="Subjects may still exist; none of this page matches the filter."
              onClearFilters={() => {
                setDraft('');
                writeQ('');
              }}
            />
          ) : (
            <EmptyState
              kind="empty"
              title="No subjects"
              description="A subject is a named schema, usually a topic's key or value, with a compatibility level and one version per registration."
              action={gate.kind === 'allowed' ? register : undefined}
            />
          )
        }
      />
      )}
    </>
  );
}

/**
 * A Kafka cluster's schema registry: subjects, and which serde decodes a topic's key or value.
 * The screen stays when no registry is configured; actions stay visible and say why.
 */
export function SchemasView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as SchemasSearch;

  return (
    <Page fill>
      <PageHeader
        title="Schemas"
        description="Subjects in the cluster's schema registry, and the serde that decodes a topic's key or value."
      />
      <SectionNav
        label="Schema sections"
        groups={[
          {
            id: 'schemas',
            label: 'Registry',
            tabs: [
              {
                id: 'subjects',
                title: 'Subjects',
                panel: <Subjects clusterId={clusterId} q={search.q ?? ''} />,
              },
              {
                id: 'bindings',
                title: 'Bindings',
                panel: (
                  <EmptyState
                    kind="empty"
                    title="Bindings"
                    description="A binding names the serde that decodes one topic's key or value. This installation runs only the serdes it ships."
                  />
                ),
              },
            ],
          },
        ]}
      />
    </Page>
  );
}
