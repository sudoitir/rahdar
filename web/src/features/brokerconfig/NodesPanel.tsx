import { useMemo } from 'react';
import { Text, Tooltip } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import type { ConfigCatalogueView, ConfigDeclarationView, ConfigDriftFindingView, ConfigNodeStateView } from './api.ts';
import { absoluteLabel, elapsedLabel, serverNow, useServerNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable, MiddleTruncate, type Column } from '../../ui/table/index.ts';
import { findingKindWords, findingRows, nodeStateWords, wireSectionLabel, WIRE_SECTIONS } from './words.ts';

const KIND_ORDER = ['UNDECLARED', 'DIVERGENT_QUEUE', 'DIVERGENT_ADDRESS', 'UNVERIFIABLE', 'NOT_EVALUATED'];

function ago(at: string): string {
  return `${elapsedLabel(serverNow() - Date.parse(at))} ago`;
}

/** Every key a declared row already carries, so the same finding is not told twice. */
function declaredKeys(declaration: ConfigDeclarationView): Set<string> {
  const doc = declaration.document;
  const keys = new Set<string>();
  const add = (section: keyof typeof WIRE_SECTIONS, key: string) =>
    WIRE_SECTIONS[section].forEach((wire) => keys.add(`${wire}:${key}`));
  doc.addresses.forEach((a) => {
    add('addresses', a.name);
    a.queues.forEach((q) => add('addresses', q.name));
  });
  doc.addressSettings.forEach((s) => add('addressSettings', s.match));
  doc.securitySettings.forEach((s) => add('securitySettings', s.match));
  doc.diverts.forEach((d) => add('diverts', d.name));
  return keys;
}

/**
 * The nodes themselves (ADR-0087 D1): each one's state, why an agreeing node
 * agrees, and the findings no declared row can carry — a resource the declaration
 * does not mention, a node that could not be read, one not evaluated yet.
 *
 * <p>An item's own drift is on the item's row; repeating it here would be the
 * split this screen exists to remove.
 */
export function NodesPanel({
  declaration,
  catalogue,
}: Readonly<{
  declaration: ConfigDeclarationView;
  catalogue?: ConfigCatalogueView;
}>) {
  useDisplayZone();
  useServerNow();
  const onRows = useMemo(() => declaredKeys(declaration), [declaration]);

  return (
    <Section
      title="Nodes"
      description={
        <>
          <Link to={`/clusters/${declaration.clusterId}/artemis/config-diff`} className={linkClasses.link}>
            Config diff
          </Link>{' '}
          sets every node against the majority of the others; this screen compares every node with the declaration.
        </>
      }
    >
      {declaration.nodes.map((node) => (
        <NodeCard
          key={node.nodeId}
          node={node}
          clusterId={declaration.clusterId}
          catalogue={catalogue}
          onRows={onRows}
        />
      ))}
      {declaration.reportUndeclared ? null : (
        <Text size="sm" c="dimmed">
          Undeclared reporting is off for this cluster: settings and diverts the declaration does not mention are not
          listed. Turn it on from the mode control in the header.
        </Text>
      )}
    </Section>
  );
}

/**
 * Why an agreeing node agrees. "In sync" after an adoption and "in sync" after a
 * verified apply look identical and mean opposite things — one says Rahdar wrote
 * the values and read them back, the other says the declaration was copied from
 * whatever the broker happened to be doing. A node that agrees for no recorded
 * reason says that too, rather than implying the stronger one.
 */
function SyncEvidence({ node, clusterId }: Readonly<{ node: ConfigNodeStateView; clusterId: string }>) {
  if (node.state !== 'IN_SYNC') return null;
  if (!node.basis) {
    return (
      <Text size="sm" c="dimmed">
        No record of why it agrees.
      </Text>
    );
  }
  const words = {
    VERIFIED_APPLY: node.basisRef ? `Verified by apply #${node.basisRef}` : 'Verified by an apply',
    ADOPTED: node.basisRef
      ? `Adopted as revision ${node.basisRef}; no broker was written`
      : 'Adopted from this cluster',
    OBSERVED_MATCH: 'Observed to match; Rahdar has not written to this node',
  }[node.basis];
  return (
    <Link to={`/clusters/${clusterId}/artemis/configuration?tab=history`} className={linkClasses.link}>
      {words}
    </Link>
  );
}

/** A finding no declared row carries, with the key that makes it unique among its node's. */
interface LooseFinding {
  id: string;
  finding: ConfigDriftFindingView;
}

/** The node's findings as a table: what is wrong, where, and what the node reports for it. */
function findingColumns(nodeName: string, catalogue?: ConfigCatalogueView): Column<LooseFinding>[] {
  return [
    {
      id: 'kind',
      header: 'Finding',
      accessor: (r) => findingKindWords(r.finding.kind),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'section',
      header: 'Section',
      accessor: (r) => wireSectionLabel(r.finding.section),
      kind: 'status',
      priority: 'essential',
    },
    {
      id: 'item',
      header: 'Item',
      accessor: (r) => `${r.finding.key ?? '—'} ${r.finding.detail}`,
      cell: (r) => (
        <>
          <div>
            <MiddleTruncate text={r.finding.key ?? '—'} tooltip />
          </div>
          <Text size="sm" c="dimmed" truncate>
            {r.finding.detail}
          </Text>
        </>
      ),
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'observed',
      header: `On ${nodeName}`,
      accessor: (r) =>
        findingRows(r.finding, catalogue)
          .map((row) => `${row.key} ${row.observed}`)
          .join(' '),
      cell: (r) => {
        const rows = findingRows(r.finding, catalogue);
        const ordered = [...rows.filter((x) => x.differs), ...rows.filter((x) => !x.differs)];
        return ordered.length === 0 ? (
          '—'
        ) : (
          <DescriptionList
            oneLine
            items={ordered.map((x) => ({ term: x.key, value: x.observed === '—' ? x.declared : x.observed }))}
          />
        );
      },
      kind: 'text',
      priority: 'essential',
      min: 28,
      wrap: true,
    },
  ];
}

function NodeCard({
  node,
  clusterId,
  catalogue,
  onRows,
}: Readonly<{
  node: ConfigNodeStateView;
  clusterId: string;
  catalogue?: ConfigCatalogueView;
  onRows: Set<string>;
}>) {
  const state = nodeStateWords(node.state);
  const rows = useMemo<LooseFinding[]>(
    () =>
      node.findings
        .filter((f) => !f.key || !onRows.has(`${f.section}:${f.key}`))
        .sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind))
        .map((finding, i) => ({ id: `${finding.kind}:${finding.section}:${finding.key}:${i}`, finding })),
    [node.findings, onRows],
  );
  const columns = useMemo(() => findingColumns(node.nodeName, catalogue), [node.nodeName, catalogue]);

  return (
    <Section
      headingLevel={3}
      title={node.nodeName}
      actions={
        <StatusBadge tone={state.tone ?? 'neutral'}>
          {node.live ? state.text : 'not live — backups inherit through replication and are not evaluated'}
        </StatusBadge>
      }
      description={
        <>
          {node.live && node.evaluatedAt ? (
            <Tooltip label={absoluteLabel(node.evaluatedAt)} withArrow>
              <Text size="sm" component="span" tabIndex={0}>
                {ago(node.evaluatedAt)}
              </Text>
            </Tooltip>
          ) : null}{' '}
          {node.detail ?? ''} <SyncEvidence node={node} clusterId={clusterId} />
        </>
      }
    >
      {rows.length === 0 ? null : (
        <DataTable
          variant="static"
          label={`Findings on ${node.nodeName}`}
          columns={columns}
          data={rows}
          rowKey={(r) => r.id}
          storageKey="brokerconfig.nodes.findings"
          height={{ maxRows: rows.length }}
          empty={null}
        />
      )}
    </Section>
  );
}
