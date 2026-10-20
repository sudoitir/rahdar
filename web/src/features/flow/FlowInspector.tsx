import { useEffect, useRef } from 'react';
import { Button, CloseButton, Stack, Text } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { Section } from '../../ui/Section.tsx';
import type { FlowEdgeView, FlowGraphView, FlowNodeView } from './api.ts';
import { edgeText, FAULT_LABELS, formatCount, rateSourceLabel } from './flowFormat.ts';
import { focusOf } from './flowSearch.ts';
import classes from './FlowView.module.css';

const KIND_WORD: Record<string, string> = {
  PRODUCER: 'Producing client',
  CONSUMER: 'Consuming client',
  DESTINATION: 'Address',
  SUBSCRIPTION: 'Queue',
  REMOTE: 'Remote',
};

const ROLE_WORD: Record<string, string> = {
  STORE_AND_FORWARD: 'Cluster redistribution queue',
  TEMPORARY: 'Temporary queues, collapsed',
  ANONYMOUS: 'Producers that name no address',
  CAPTURE: "Rahdar's message capture",
  DEAD_LETTER: 'Dead-letter address',
  EXPIRY: 'Expiry address',
  CLUSTER_NODE: 'Another node of this cluster',
  LINK_TARGET: 'A bridge target outside this cluster',
};

/** What the inspector states about a node, as term and value; each kind contributes what it has. */
function nodeFacts(node: FlowNodeView): DescriptionItem[] {
  const facts: DescriptionItem[] = [];
  if (node.role) facts.push({ term: 'What it is', value: ROLE_WORD[node.role] ?? node.role.toLowerCase() });
  if (node.kind === 'SUBSCRIPTION') {
    facts.push(
      {
        term: 'Backlog',
        value:
          node.messageCount === null || node.messageCount === undefined
            ? 'not swept yet'
            : `${formatCount(node.messageCount)} waiting`,
      },
      {
        term: 'Consumers',
        value:
          node.consumerCount === null || node.consumerCount === undefined
            ? 'not swept yet'
            : String(node.consumerCount),
      },
    );
  }
  if (node.kind === 'DESTINATION' && node.routingTypes?.length)
    facts.push({ term: 'Routing', value: node.routingTypes.join(', ').toLowerCase() });
  if (node.members) facts.push({ term: 'Connections', value: String(node.members) });
  if (node.protocols?.length) facts.push({ term: 'Protocols', value: node.protocols.join(', ') });
  if (node.hosts?.length) facts.push({ term: 'Hosts', value: node.hosts.join(', ') });
  if (node.users?.length) facts.push({ term: 'Users', value: node.users.join(', ') });
  if (node.brokerNodes?.length) facts.push({ term: 'Seen on', value: node.brokerNodes.join(', ') });
  return facts;
}

/** Where the node can be followed up: the screen that owns it. Focusing the view is in the header. */
function InspectorActions({
  node,
  open,
}: Readonly<{
  node: FlowNodeView;
  open: (path: string, search?: Record<string, string | undefined>) => unknown;
}>) {
  return (
    <Stack gap="xs">
      {node.kind === 'SUBSCRIPTION' && !node.role ? (
        <Button size="xs" variant="subtle" onClick={() => open('queues', { queue: node.label })}>
          Open in Queues
        </Button>
      ) : null}
      {node.kind === 'DESTINATION' && !node.role ? (
        <Button size="xs" variant="subtle" onClick={() => open('addresses')}>
          Open in Addresses
        </Button>
      ) : null}
      {node.kind === 'CONSUMER' ? (
        <Button size="xs" variant="subtle" onClick={() => open('consumers')}>
          Open its consumers
        </Button>
      ) : null}
      {node.kind === 'PRODUCER' ? (
        <Button size="xs" variant="subtle" onClick={() => open('producers')}>
          Open its producers
        </Button>
      ) : null}
      {node.kind === 'PRODUCER' || node.kind === 'CONSUMER' ? (
        <Button size="xs" variant="subtle" onClick={() => open('connections')}>
          Open its connections
        </Button>
      ) : null}
      <Text size="xs" c="dimmed">
        Flow never changes the broker. Closing a connection or consumer happens on its own screen, with its
        confirmation.
      </Text>
    </Stack>
  );
}

/**
 * One node, explained (flow-visualization spec: the inspector explains a resource and links to
 * existing actions without mutating). Closing it returns focus to whatever opened it — the caller
 * owns that, because only it knows what opened it.
 */
export function FlowInspector({
  graph,
  nodeId,
  clusterId,
  onClose,
  onFocus,
}: Readonly<{
  graph: FlowGraphView;
  nodeId: string;
  clusterId: string;
  onClose: () => void;
  onFocus: (focus: string) => void;
}>) {
  const navigate = useNavigate();
  const now = useServerNow(5_000);
  const close = useRef<HTMLButtonElement>(null);
  const nodes = new Map((graph.nodes ?? []).map((n) => [n.id, n]));
  const node = nodes.get(nodeId);

  useEffect(() => {
    close.current?.focus();
  }, [nodeId]);

  if (!node) return null;

  const inbound = (graph.edges ?? []).filter((e) => e.target === nodeId);
  const outbound = (graph.edges ?? []).filter((e) => e.source === nodeId);
  const faults = (node.faults ?? []).map((f) => FAULT_LABELS[f] ?? f.toLowerCase());
  // The exact resource: a queue opens itself; the rest open the view filtered to the name, which
  // the resource filters match on (client id, user or host for connections).
  const open = (path: string, search?: Record<string, string | undefined>) =>
    navigate({ to: `/clusters/$clusterId/${path}`, params: { clusterId }, search: search ?? { q: node.label } });

  const facts = nodeFacts(node);

  const flowList = (title: string, edges: FlowEdgeView[], other: (e: FlowEdgeView) => string | undefined) =>
    edges.length === 0 ? null : (
      <Section headingLevel={3} title={title}>
        <ul className={classes.flowList} aria-label={title}>
          {edges.map((e) => (
            <li key={e.id} className={classes.flowRow}>
              <Text size="sm" truncate title={nodes.get(other(e))?.label}>
                {nodes.get(other(e))?.label ?? '—'}
              </Text>
              <Text size="sm" className={e.faults?.length ? classes.alarm : undefined}>
                {edgeText(e)}
              </Text>
              <Text size="xs" c="dimmed">
                {rateSourceLabel(e)}
                {e.asOf ? ` · ${elapsedLabel(now - Date.parse(e.asOf))} ago` : ''}
              </Text>
            </li>
          ))}
        </ul>
      </Section>
    );

  return (
    // Escape closes it from anywhere inside, and the caller returns focus to what opened it.
    <aside
      aria-label={`Details of ${KIND_WORD[node.kind ?? ''] ?? 'node'} ${node.label}`}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          onClose();
        }
      }}
    >
      <Section
        variant="card"
        headingLevel={3}
        title={node.label ?? ''}
        description={KIND_WORD[node.kind ?? '']}
        actions={
          <>
            {focusOf(node) ? (
              <Button size="xs" variant="default" onClick={() => onFocus(focusOf(node)!)}>
                Focus the view on this
              </Button>
            ) : null}
            <CloseButton ref={close} aria-label="Close details" onClick={onClose} />
          </>
        }
      >
        {faults.length ? (
          <Text size="sm" fw={600} className={classes.alarm}>
            {faults.join(', ')}
          </Text>
        ) : null}

        {facts.length ? <DescriptionList label="Facts" items={facts} /> : null}

        {flowList('Flow in', inbound, (e) => e.source)}
        {flowList('Flow out', outbound, (e) => e.target)}

        <InspectorActions node={node} open={open} />
      </Section>
    </aside>
  );
}
