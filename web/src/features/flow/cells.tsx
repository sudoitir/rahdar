import { Group, Text } from '@mantine/core';

import { MiddleTruncate } from '../../ui/table/index.ts';
import type { FlowEdgeView, FlowNodeView } from './api.ts';
import { edgeText } from './flowFormat.ts';
import classes from './FlowView.module.css';

const KIND: Record<string, string> = {
  PRODUCER: 'client',
  DESTINATION: 'destination',
  SUBSCRIPTION: 'subscription',
  CONSUMER: 'client',
  REMOTE: 'remote',
};

/** The plain text of a path's end: its kind, its name and how many clients it stands for. */
export function nodeText(node: FlowNodeView | undefined): string {
  if (!node) return '';
  const members = node.members && node.members > 1 ? `×${node.members}` : '';
  return [KIND[node.kind ?? ''], node.label, members].filter(Boolean).join(' ');
}

/** A path's end: what it is, its name (shortened in the middle, so names sharing a prefix differ), and its client count. */
export function nodeCell(node: FlowNodeView | undefined) {
  if (!node) return null;
  return (
    <Group gap="xs" wrap="nowrap">
      {node.kind ? (
        <Text span size="xs" c="dimmed">
          {KIND[node.kind]}
        </Text>
      ) : null}
      <MiddleTruncate text={node.label ?? ''} />
      {node.members && node.members > 1 ? (
        <Text span size="xs" c="dimmed">
          ×{node.members}
        </Text>
      ) : null}
    </Group>
  );
}

/** A rate that is out of date reads as stale; a fresh one needs no emphasis. */
export function rateCell(edge: FlowEdgeView) {
  return <span className={edge.stale ? classes.stale : undefined}>{edgeText(edge)}</span>;
}

export function sourceCell(text: string) {
  return (
    <Text span size="xs" c="dimmed">
      {text}
    </Text>
  );
}

/** What is wrong with a path, in words: colour only emphasises it. */
export function faultsCell(faults: string[]) {
  if (faults.length === 0) return null;
  return (
    <Text span fw={600} className={classes.alarm}>
      {faults.join(', ')}
    </Text>
  );
}

/** A figure of a broker node's, or the word that says it is unknown or not yet measured. */
export function figureCell(text: string, stale: boolean) {
  return <span className={stale ? classes.stale : undefined}>{text}</span>;
}

/** The node's name, shortened in the middle, with the words "did not answer" beside it when its figures are unknown. */
export function nodeNameCell(node: string, stale: boolean) {
  return (
    <Group gap="xs" wrap="nowrap">
      <MiddleTruncate text={node} />
      {stale ? (
        <Text span size="xs" className={classes.stale}>
          did not answer
        </Text>
      ) : null}
    </Group>
  );
}
