import { useMemo, useState } from 'react';
import { MultiSelect, SegmentedControl, Text, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useConfigDiff, type ConfigDiffView as Diff } from './api.ts';
import { configDiffColumns } from './configDiffColumns.tsx';
import { diffRows, filterRows, rowKey, summaryWords, type DiffFilter } from './configDiffRows.ts';
import type { ConfigDiffSearch } from './feature.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { DataTable } from '../../ui/table/index.ts';

const VIEWS = [
  { value: 'drift', label: 'Drift' },
  { value: 'expected', label: 'Expected' },
  { value: 'all', label: 'All keys' },
];

/** What the table says when it lists nothing: clean, filtered away, or not known because a node is silent. */
function NoRows({
  data,
  filter,
  filtered,
  onClear,
}: Readonly<{ data: Diff; filter: DiffFilter; filtered: boolean; onClear: () => void }>) {
  const silent = data.nodes.filter((n) => !n.available).map((n) => n.nodeName);
  if (silent.length > 0 && !filtered) {
    return (
      <EmptyState
        kind="unreachable"
        title="Nothing listed for the nodes that answered"
        description="The nodes below did not answer, so this may not be everything."
        nodes={silent}
      />
    );
  }
  if (filtered) {
    return <EmptyState kind="filtered" title="No key matches" onClearFilters={onClear} />;
  }
  if (filter === 'drift') {
    return (
      <EmptyState
        kind="empty"
        title="Nothing drifts"
        description="Every node has the same value for every configuration key. Expected differences, such as each broker's name and paths, are under Expected."
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No expected differences"
      description="No key differs between nodes in a way that is correct by design."
    />
  );
}

/** Why no comparison could be made: the note, and every node that did not answer with its reason. */
function NoComparison({ data }: Readonly<{ data: Diff }>) {
  const silent = data.nodes.filter((n) => !n.available);
  return (
    <EmptyState
      kind="unreachable"
      title="No comparison shown"
      description={
        <>
          {data.notes.map((note) => (
            <Text key={note} size="sm">
              {note}
            </Text>
          ))}
          {silent.map((n) => (
            <Text key={n.nodeId} size="sm">
              <strong>{n.nodeName}:</strong> {n.unavailableReason}
            </Text>
          ))}
        </>
      }
      nodes={silent.map((n) => n.nodeName)}
    />
  );
}

/** A node that did not answer, with why, beside the comparison of those that did. */
function Silent({ data }: Readonly<{ data: Diff }>) {
  const silent = data.nodes.filter((n) => !n.available);
  return silent.map((n) => (
    <div key={n.nodeId}>
      <StatusBadge tone="danger">{`${n.nodeName} unavailable`}</StatusBadge>{' '}
      <Text size="sm" component="span">
        {n.unavailableReason}
      </Text>
    </div>
  ));
}

/**
 * Broker configuration of every node set against the others (ADR-0043, ADR-0178). Drift between
 * nodes is silent until failover, when it is expensive, and the node that differs is the one a pair
 * cannot name.
 *
 * The screen opens on drift and says in one sentence how much there is. Expected differences (a
 * broker's name, its node-local paths) and unclassified keys (runtime counters) are one switch
 * away, so a clean cluster reads as clean and the operator is not trained to ignore the list.
 */
export function ConfigDiffView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as ConfigDiffSearch;
  const navigate = useNavigate();
  const diff = useConfigDiff(clusterId);
  const columns = useMemo(configDiffColumns, []);

  const filter: DiffFilter = search.view ?? 'drift';
  const nodes = useMemo(() => search.nodes ?? [], [search.nodes]);
  const [text, setText] = useState(search.q ?? '');

  const setSearch = (patch: Partial<ConfigDiffSearch>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }), replace: true });
  const commitText = useDebouncedCallback((q: string) => void setSearch({ q: q || undefined }), 250);

  const data = diff.data;
  const rows = useMemo(() => (data ? diffRows(data) : []), [data]);
  const shown = useMemo(() => filterRows(rows, { filter, text, nodes }), [rows, filter, text, nodes]);
  const filtered = text.trim() !== '' || nodes.length > 0;
  const clear = () => {
    setText('');
    void setSearch({ q: undefined, nodes: undefined });
  };

  return (
    <Page fill={data?.comparable}>
      <PageHeader
        title="Config diff"
        description="Every node's broker configuration set against the majority, so the node that differs stands out."
      />

      {diff.isPending ? <LoadingState label="Comparing the nodes" blockSize="20rem" /> : null}
      {diff.isError ? <ErrorState error={diff.error} onRetry={() => void diff.refetch()} /> : null}

      {data && !data.comparable ? <NoComparison data={data} /> : null}

      {data?.comparable ? (
        <>
          <div>
            <Text size="sm" fw={600}>
              {summaryWords(data)}
            </Text>
            <Silent data={data} />
            {data.notes.map((note) => (
              <Text key={note} size="sm" c="dimmed">
                {note}
              </Text>
            ))}
            <Text size="sm" c="dimmed">
              This compares the nodes with each other; the{' '}
              <Link to={`/clusters/${clusterId}/artemis/configuration`} className={linkClasses.link}>
                declared configuration
              </Link>{' '}
              reports drift of every live node against what you declared.
            </Text>
          </div>

          <Toolbar
            label="Comparison filters"
            start={
              <>
                <SegmentedControl
                  size="xs"
                  aria-label="Keys to list"
                  data={VIEWS}
                  value={filter}
                  onChange={(v) => setSearch({ view: v === 'drift' ? undefined : (v as ConfigDiffSearch['view']) })}
                />
                <TextInput
                  label="Search keys and values"
                  size="xs"
                  w="16rem"
                  value={text}
                  onChange={(e) => {
                    setText(e.currentTarget.value);
                    commitText(e.currentTarget.value);
                  }}
                />
                <MultiSelect
                  label="Differs on node"
                  placeholder={nodes.length > 0 ? undefined : 'Any node'}
                  size="xs"
                  w="16rem"
                  clearable
                  data={data.nodes.filter((n) => n.available).map((n) => ({ value: n.nodeId, label: n.nodeName }))}
                  value={nodes}
                  onChange={(v) => setSearch({ nodes: v.length > 0 ? v : undefined })}
                />
              </>
            }
          />

          <DataTable
            label="Configuration keys"
            storageKey="brokerconfig.diff"
            height="fill"
            columns={columns}
            data={shown}
            rowKey={rowKey}
            empty={<NoRows data={data} filter={filter} filtered={filtered} onClear={clear} />}
          />
        </>
      ) : null}
    </Page>
  );
}
