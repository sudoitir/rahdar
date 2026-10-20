import type { ReactNode } from 'react';
import { Drawer, Group, Stack, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { useRrFlow } from './api.ts';
import { FlowStateBadge } from './cells.tsx';
import type { components } from '../../kernel/api/schema.d.ts';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { RedactionMarks, WithheldNotice } from '../../ui/RedactedValue.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import classes from './rr.module.css';

type RedactionView = components['schemas']['RedactionView'];
type WithheldView = components['schemas']['WithheldView'];

/**
 * One timeline event's detail. A captured payload is shown as the governed preview with its marks, or as the
 * reason it was omitted; any other detail is shown as it came.
 */
function EventDetail({ detail }: Readonly<{ detail: Record<string, unknown> }>) {
  if (typeof detail.payloadOmitted === 'string') {
    return (
      <Text size="xs" c="dimmed">
        {detail.payloadOmitted}
      </Text>
    );
  }
  if ('bodyPreview' in detail) {
    const redactions = Array.isArray(detail.redactions) ? (detail.redactions as RedactionView[]) : [];
    const withheld = Array.isArray(detail.withheld) ? (detail.withheld as WithheldView[]) : [];
    return (
      <Stack gap="xs">
        <RedactionMarks redactions={redactions} />
        <WithheldNotice withheld={withheld} />
        <CodeHighlight
          code={typeof detail.bodyPreview === 'string' ? detail.bodyPreview : '(no preview)'}
          language="text"
        />
        {detail.truncated === true ? (
          <Text size="xs" c="dimmed">
            Cut to the payload capture limit.
          </Text>
        ) : null}
      </Stack>
    );
  }
  return <CodeHighlight code={JSON.stringify(detail, null, 2)} language="json" />;
}

type Flow = NonNullable<ReturnType<typeof useRrFlow>['data']>;

/** Which clock measured the latency is part of the measurement: an OBSERVED figure is the gap between two sample ticks (ADR-0053). */
function latencyItem(f: Flow): DescriptionItem {
  const bound = f.latencySource === 'OBSERVED' && f.latencyBoundMs != null ? ` ± ${f.latencyBoundMs}ms` : '';
  return {
    term: 'Latency',
    value: `${f.latencyMs}ms${bound}`,
    hint:
      f.latencySource === 'MESSAGE_TIMESTAMPS'
        ? 'from the messages’ own timestamps, normalised onto Rahdar’s clock'
        : 'observed between sample ticks, so it cannot resolve anything shorter',
  };
}

/** What the clocks on the two sides claim, when either is ahead of Rahdar's. */
function skewItem(f: Flow): DescriptionItem {
  const request =
    f.requestSkewMs == null ? '' : `the request claimed to be produced ${f.requestSkewMs}ms in the future`;
  const separator = f.requestSkewMs != null && f.replySkewMs != null ? '; ' : '';
  const reply = f.replySkewMs == null ? '' : `the reply claimed to be produced ${f.replySkewMs}ms in the future`;
  return {
    term: 'Clock skew',
    value: (
      <span className={classes.warn}>
        {request}
        {separator}
        {reply}
      </span>
    ),
  };
}

/** When the flow was requested and replied, its deadline and latency, and how it is identified. */
function FlowFacts({ f }: Readonly<{ f: Flow }>) {
  const items: DescriptionItem[] = [{ term: 'Requested at', value: f.requestedAt }];
  if (f.repliedAt) items.push({ term: 'Replied at', value: f.repliedAt });
  if (f.deadlineAt) items.push({ term: 'Deadline', value: f.deadlineAt });
  if (f.latencyMs != null) items.push(latencyItem(f));
  if (f.requestSkewMs != null || f.replySkewMs != null) items.push(skewItem(f));
  if (f.correlationId) items.push({ term: 'Correlation id', value: f.correlationId });
  if (f.replyDestination) items.push({ term: 'Reply destination', value: f.replyDestination });
  return <DescriptionList label="Flow facts" items={items} />;
}

/** The flow's events in order, each with its detail. */
function Timeline({ events }: Readonly<{ events: Flow['events'] }>) {
  if (!events || events.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No events recorded for this flow.
      </Text>
    );
  }
  return (
    <Stack gap="sm">
      {events.map((e) => (
        <Stack key={e.seq} gap="xs">
          <Group gap="xs">
            <Text size="xs">{e.ts}</Text>
            <StatusBadge>{e.kind}</StatusBadge>
          </Group>
          {e.detail ? <EventDetail detail={e.detail} /> : null}
        </Stack>
      ))}
    </Stack>
  );
}

/** The flow itself: its state, its facts and its timeline. */
function FlowContent({ f }: Readonly<{ f: Flow }>) {
  return (
    <Stack gap="lg">
      <Group gap="xs">
        <FlowStateBadge state={f.state} />
        <StatusBadge>{f.replyKind === 'TEMPORARY' ? 'temp reply queue' : 'shared reply queue'}</StatusBadge>
      </Group>

      <FlowFacts f={f} />

      <Section headingLevel={3} title="Timeline">
        <Timeline events={f.events} />
      </Section>
    </Stack>
  );
}

/** What stands in for the flow while it loads or fails to load. */
function flowNotice(detail: ReturnType<typeof useRrFlow>): ReactNode {
  if (detail.isPending) return <LoadingState label="Loading the flow" blockSize="12rem" />;
  if (detail.isError) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;
  return null;
}

/** The `rr_event` timeline and any captured payload for one flow, mirroring {@code MessageDetailPanel}. */
export function FlowDetail({
  clusterId,
  flowId,
  onClose,
}: Readonly<{
  clusterId: string;
  flowId: string | null;
  onClose: () => void;
}>) {
  const detail = useRrFlow(clusterId, flowId ?? undefined);
  const f = detail.data;

  return (
    <Drawer
      opened={flowId !== null}
      onClose={onClose}
      position="right"
      size="lg"
      closeButtonProps={{ 'aria-label': 'Close the flow' }}
      title={f ? `Flow on ${f.requestAddress}` : 'Flow'}
    >
      {flowNotice(detail) ?? (f ? <FlowContent f={f} /> : null)}
    </Drawer>
  );
}
