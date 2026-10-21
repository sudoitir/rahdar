import { useId, useMemo, useState, type ReactNode } from 'react';
import { Button, Switch, Text } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useMetrics, type MetricSeries } from './api.ts';
import dayjs from 'dayjs';
import duration from 'dayjs/plugin/duration';

import { useRoleIds } from '../clusters/index.ts';
import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { rangeSpec, type MetricRange } from './ranges.ts';
import { RangePicker } from './RangePicker.tsx';
import { ChartPanel } from '../../kernel/metrics/ChartPanel.tsx';
import { DepthChart } from './DepthChart.tsx';
import { ThroughputChart } from './ThroughputChart.tsx';
import { ConsumersChart } from './ConsumersChart.tsx';
import { MetricsTable, type WindowMetric } from './MetricsTable.tsx';
import { NodeSplitCharts } from './NodeSplitPanels.tsx';
import { StatRow, type Figure } from './StatRow.tsx';
import { earliest, formatCount, formatExact, formatRate, latest } from '../../kernel/metrics/axis.ts';
import { useSlot } from '../../kernel/slots.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';

const METRICS = ['openbroker.backlog', 'openbroker.consumer.count', 'openbroker.enqueued', 'openbroker.acknowledged'];

/** The controls for a subscription-scoped view: the way back to the cluster, and the per-node split. */
function SubscriptionScope({ split }: Readonly<{ split: boolean }>) {
  const navigate = useNavigate();
  return (
    <Toolbar
      label="Subscription scope"
      start={
        <>
          <Button
            size="xs"
            variant="default"
            onClick={() =>
              navigate({
                to: '.',
                search: (prev: Record<string, unknown>) => ({ ...prev, subject: undefined, split: undefined }),
              })
            }
          >
            Show the whole cluster
          </Button>
          <Switch
            label="Break down by broker node"
            checked={split}
            onChange={(event) => {
              const on = event.currentTarget.checked;
              void navigate({
                to: '.',
                search: (prev: Record<string, unknown>) => ({ ...prev, split: on ? 'node' : undefined }),
              });
            }}
          />
        </>
      }
    />
  );
}

/**
 * What the window is, in one line that is always present: so the line is there while the first read
 * is pending, and the charts beneath it do not move when a note arrives.
 */
dayjs.extend(duration);

/** A bucket width as the console writes durations ("5m"), from the ISO-8601 step the server sends ("PT5M"). */
function bucketLabel(step: string): string {
  return elapsedLabel(dayjs.duration(step).asMilliseconds());
}

function windowNote(pending: boolean, data: { truncated?: boolean; step?: string } | undefined): string {
  if (pending) return 'Reading the window…';
  if (data?.truncated) {
    // One line at desktop widths, so the toolbar keeps its height and the charts below do not move.
    return `Window adjusted to ${bucketLabel(data.step ?? '')} buckets, the finest this cluster's retention allows.`;
  }
  return data?.step ? `Each point is a ${bucketLabel(data.step)} bucket.` : '';
}

/** A table cell in the unit of its column: depth as a count, consumers exactly, the rest as rates. */
function formatCell(name: string, value: number): string {
  if (name === 'depth') return formatCount(value);
  return name === 'consumers' ? formatExact(value) : formatRate(value);
}

/**
 * Cluster-wide (or subscription-scoped) historical metrics: depth, throughput and consumers,
 * sharing one crosshair, then whatever the enabled features add (`metrics.panels`),
 * such as request-reply latency.
 *
 * A relative range advances as time passes, quantized to the bucket width, so the
 * right-hand edge is still the present an hour after the page was opened — and so
 * the query key changes once per bucket rather than once per second (ADR-0055).
 * An absolute range never advances and never polls: it is a link to a moment.
 */
export function MetricsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const panels = useSlot('metrics.panels');
  const search = useSearch({ strict: false }) as {
    range?: MetricRange;
    from?: string;
    to?: string;
    subject?: string;
    split?: 'node';
  };

  const live = !search.from && !search.to;
  const range = search.range ?? '1h';
  const spec = rangeSpec(range);
  const subject = search.subject;
  // The split is per subscription only (ADR-0110): a cluster total has no node series to add up to it.
  const split = Boolean(subject) && search.split === 'node';

  // Ticks at the bucket width, not at a second: the window can only move when a
  // new bucket exists, and a key that changed every second would mint a cache
  // entry a second.
  const tick = useServerNow(spec.stepMs);

  const { from, to } = useMemo(() => {
    if (!live) return { from: search.from!, to: search.to! };
    const end = Math.floor(tick / spec.stepMs) * spec.stepMs;
    return {
      from: new Date(end - spec.windowMs).toISOString(),
      to: new Date(end).toISOString(),
    };
  }, [live, tick, spec.stepMs, spec.windowMs, search.from, search.to]);

  const ids = useRoleIds(clusterId);
  const metrics = useMetrics(
    clusterId,
    {
      metrics: METRICS,
      resource: subject && ids.loaded ? ids.subscription(subject) : undefined,
      from,
      to,
      step: live ? spec.step : undefined,
      splitBy: split ? 'NODE' : undefined,
    },
    live ? Math.max(15_000, spec.stepMs) : false,
    ids.loaded,
  );

  const byName = (name: string): MetricSeries | undefined => metrics.data?.series.find((s) => s.metric === name);
  const depth = byName('openbroker.backlog');
  const added = byName('openbroker.enqueued');
  const acked = byName('openbroker.acknowledged');
  const consumers = byName('openbroker.consumer.count');

  const syncId = `cluster-metrics-${clusterId}`;
  // The axis domain is the *requested* window, so a series that stops halfway
  // renders as a half-empty chart rather than one silently rescaled to fit.
  const fromMs = Date.parse(from);
  const toMs = Date.parse(to);

  // `isPending` alone is wrong here: `placeholderData` keeps the previous window's
  // data while a new one loads, and a skeleton over data an operator is reading is
  // worse than a slightly stale chart.
  const isPending = metrics.isPending && !metrics.data;
  const error = metrics.isError ? metrics.error : null;
  const empty = (s: MetricSeries | undefined) => !error && !isPending && !s?.points?.length;
  const scope = subject ? `subscription ${subject}` : 'this cluster';

  const figures: Figure[] = [
    { label: 'Depth', unit: 'messages', value: latest(depth), since: earliest(depth), format: formatCount },
    { label: 'Added', unit: 'msg/s', value: latest(added), since: earliest(added), format: formatRate },
    { label: 'Acked', unit: 'msg/s', value: latest(acked), since: earliest(acked), format: formatRate },
    {
      label: 'Consumers',
      unit: 'connected',
      value: latest(consumers),
      since: earliest(consumers),
      format: formatExact,
    },
  ];

  const windowMetrics = useMemo(
    () => [
      { name: 'depth', label: 'Depth', series: depth },
      { name: 'added', label: 'Added (msg/s)', series: added },
      { name: 'acked', label: 'Acked (msg/s)', series: acked },
      { name: 'consumers', label: 'Consumers', series: consumers },
    ],
    [depth, added, acked, consumers],
  );

  return (
    <Page>
      <PageHeader
        title="Metrics"
        description={
          subject ? (
            <>
              These series cover <strong>{subject}</strong> only.
            </>
          ) : (
            "Depth, throughput and consumers over time, from Rahdar's own samples of this cluster."
          )
        }
        meta={subject ? <StatusBadge tone="info">{subject}</StatusBadge> : undefined}
      />

      {subject ? <SubscriptionScope split={split} /> : null}

      <Toolbar
        label="Metrics window"
        start={<RangePicker />}
        end={
          <Text size="sm" role="status">
            {windowNote(isPending, metrics.data)}
          </Text>
        }
      />

      <StatRow figures={figures} loading={isPending} />

      <ChartPanel
        title="Depth"
        unit="messages"
        isPending={isPending}
        error={error}
        isEmpty={empty(depth)}
        emptyLabel={`No depth samples for ${scope} in this window. Depth is recorded on the subscription sweep, so a cluster registered within the last few minutes has none yet.`}
      >
        <DepthChart series={depth} range={range} from={fromMs} to={toMs} syncId={syncId} total={!subject} />
      </ChartPanel>

      <ChartPanel
        title="Throughput"
        unit="messages per second"
        isPending={isPending}
        error={error}
        isEmpty={empty(added) && empty(acked)}
        emptyLabel={`No throughput samples for ${scope} in this window. A rate needs two samples in the window to exist at all, so a very narrow range on a newly registered cluster is empty rather than zero.`}
      >
        <ThroughputChart added={added} acked={acked} range={range} from={fromMs} to={toMs} syncId={syncId} />
      </ChartPanel>

      <ChartPanel
        title="Consumers"
        unit="connected consumers"
        isPending={isPending}
        error={error}
        isEmpty={empty(consumers)}
        emptyLabel={`No consumer samples for ${scope} in this window.`}
      >
        <ConsumersChart series={consumers} range={range} from={fromMs} to={toMs} syncId={syncId} />
      </ChartPanel>

      {split && metrics.data && !error ? (
        <Section
          title="Per broker node"
          description="One chart per node, on one scale, so a node's share is read by comparing heights. The nodes add up to the totals above."
        >
          <NodeSplitCharts response={metrics.data} range={range} from={fromMs} to={toMs} syncId={syncId} />
        </Section>
      ) : null}

      <WindowTable pending={isPending} failed={error !== null} metrics={windowMetrics} />

      {panels.map(({ id, Component }) => (
        <Component key={id} clusterId={clusterId} />
      ))}
    </Page>
  );
}

/**
 * The window as a table, behind a disclosure: the plots are the way in, and the table is for the
 * operator who needs an exact value or cannot read a plot. Its section is always there, so opening
 * it never moves what is below.
 */
function WindowTable({
  pending,
  failed,
  metrics,
}: Readonly<{ pending: boolean; failed: boolean; metrics: WindowMetric[] }>) {
  const [shown, setShown] = useState(false);
  const bodyId = useId();
  let body: ReactNode = null;
  if (shown) {
    if (pending) body = <LoadingState label="Loading the window" blockSize="12rem" />;
    else if (failed) body = <Text size="sm">The window could not be read, so there are no buckets to list.</Text>;
    else body = <MetricsTable metrics={metrics} format={formatCell} />;
  }
  return (
    <Section
      title="This window as a table"
      actions={
        <Button
          size="xs"
          variant="default"
          aria-expanded={shown}
          aria-controls={bodyId}
          onClick={() => setShown((on) => !on)}
        >
          {shown ? 'Hide the table' : 'Show this window as a table'}
        </Button>
      }
    >
      <div id={bodyId}>{body}</div>
    </Section>
  );
}
