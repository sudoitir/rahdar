import { useCallback, useRef, useState } from 'react';
import type { MetricRange } from '../../kernel/time/ranges.ts';
import { Button, Chip, Group, SegmentedControl, Select, Splitter, Text, VisuallyHidden } from '@mantine/core';
import { useLocalStorage, useReducedMotion } from '@mantine/hooks';
import { IconPlayerPause, IconPlayerPlay } from '@tabler/icons-react';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useFlowGraph, type FlowGraphView, type FlowNodeView } from './api.ts';
import { BrokerNodeNotices } from './BrokerNodeNotices.tsx';
import { FlowCanvas } from './FlowCanvas.tsx';
import { FlowKpis } from './FlowKpis.tsx';
import { FlowMonitorPane } from './FlowMonitorPane.tsx';
import { GROUP_LABELS, RANK_LABELS, totalRateLabel, unreachableNodes } from './flowFormat.ts';
import {
  DEFAULT_LIMIT,
  FLOW_GROUPINGS,
  FLOW_LIMITS,
  FLOW_LAYERS,
  FLOW_RANKS,
  layersParam,
  parseLayers,
  focusOf,
  parseFocus,
  type FlowGroupBy,
  type FlowRank,
  type FlowSearch,
} from './flowSearch.ts';
import { FlowTable } from './FlowTable.tsx';
import classes from './FlowView.module.css';

const LAYER_LABELS: Record<string, string> = {
  ROUTES: 'Routes',
  CLUSTER: 'Cluster hops',
  DEAD_LETTER: 'Dead letter & expiry',
  TEMPORARY: 'Temporary subscriptions',
  CAPTURE: 'Rahdar capture',
};

const FIND_GROUPS: Array<{ group: string; kinds: string[] }> = [
  { group: 'Clients', kinds: ['PRODUCER', 'CONSUMER'] },
  { group: 'Destinations', kinds: ['DESTINATION'] },
  { group: 'Subscriptions', kinds: ['SUBSCRIPTION'] },
];

/**
 * Flow: which clients produce to which destinations, how those route into subscriptions, and who consumes
 * them — bounded to the busiest paths, focusable on any one resource, with every rate's source and
 * age stated (flow-visualization spec). Everything that describes the view lives in the URL; what is
 * hovered, selected or paused is local to this visit.
 */
export function FlowView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as FlowSearch;
  const navigate = useNavigate();
  const graph = useFlowGraph(clusterId, search);

  const setSearch = (patch: Partial<Record<keyof FlowSearch, unknown>>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  const focus = parseFocus(search.focus);
  const rank: FlowRank = search.rank ?? 'IN';
  const limit = search.limit ?? DEFAULT_LIMIT;

  return (
    <Page>
      <PageHeader
        title="Flow"
        description="Who produces to which destination, how it routes into subscriptions, and who consumes it."
      />

      <Toolbar
        label="Flow settings"
        start={
          <>
            <Select
              label="Rank paths by"
              size="xs"
              w="9.5rem"
              allowDeselect={false}
              data={FLOW_RANKS.map((value) => ({ value, label: RANK_LABELS[value] }))}
              value={rank}
              onChange={(value) => setSearch({ rank: value === 'IN' ? undefined : value })}
            />
            <Select
              label="Group clients by"
              size="xs"
              w="9.5rem"
              allowDeselect={false}
              data={FLOW_GROUPINGS.map((value) => ({ value, label: GROUP_LABELS[value] }))}
              value={search.groupBy ?? 'CLIENT_ID'}
              onChange={(value) => setSearch({ groupBy: value === 'CLIENT_ID' ? undefined : (value as FlowGroupBy) })}
            />
            <Select
              label="Show"
              size="xs"
              w="10.625rem"
              allowDeselect={false}
              data={FLOW_LIMITS.map((value) => ({ value: String(value), label: `${value} busiest paths` }))}
              value={String(limit)}
              onChange={(value) => setSearch({ limit: Number(value) === DEFAULT_LIMIT ? undefined : Number(value) })}
            />
          </>
        }
      />

      {focus ? (
        <Toolbar
          label="Focus"
          start={
            <>
              <Text size="sm">
                Focused on {focus.kind} {focus.name}
              </Text>
              <Button size="xs" variant="default" onClick={() => setSearch({ focus: undefined, hops: undefined })}>
                Clear focus
              </Button>
            </>
          }
          end={
            <>
              <Text size="xs" c="dimmed" id="flow-reach">
                Reach
              </Text>
              <SegmentedControl
                size="xs"
                aria-labelledby="flow-reach"
                data={[
                  { value: '1', label: 'Neighbours' },
                  { value: '2', label: '+1 hop' },
                  { value: '3', label: '+2 hops' },
                ]}
                value={String(search.hops ?? 1)}
                onChange={(value) => setSearch({ hops: value === '1' ? undefined : Number(value) })}
              />
            </>
          }
        />
      ) : null}

      <div className={classes.body}>
        <FlowResult clusterId={clusterId} graph={graph} search={search} rank={rank} setSearch={setSearch} />
      </div>
    </Page>
  );
}

type SetSearch = (patch: Partial<Record<keyof FlowSearch, unknown>>) => void;

/** The flow itself, or what stands in for it while it loads or fails to. */
function FlowResult({
  clusterId,
  graph,
  search,
  rank,
  setSearch,
}: Readonly<{
  clusterId: string;
  graph: ReturnType<typeof useFlowGraph>;
  search: FlowSearch;
  rank: FlowRank;
  setSearch: SetSearch;
}>) {
  if (graph.isError) {
    return <ErrorState error={graph.error} onRetry={() => void graph.refetch()} />;
  }
  if (graph.data === undefined) {
    // As tall as the totals and the graph that arrive in its place, so nothing below moves.
    return (
      <div className={classes.loading}>
        <LoadingState label="Loading flow" />
      </div>
    );
  }
  return (
    <FlowBody
      clusterId={clusterId}
      data={graph.data}
      breakdownPending={graph.isPlaceholderData}
      search={search}
      rank={rank}
      setSearch={setSearch}
    />
  );
}

/** The graph's and the pane's share of the Split layout, in %, remembered in this browser. */
const DEFAULT_SPLIT = [56, 44];
const PANE_MIN = 20;
const GRAPH_MIN = 35;

function validSplit(sizes: unknown): sizes is number[] {
  return (
    Array.isArray(sizes) &&
    sizes.length === 2 &&
    sizes.every((n) => typeof n === 'number' && Number.isFinite(n)) &&
    sizes[0] >= GRAPH_MIN &&
    sizes[1] >= PANE_MIN
  );
}

/** Values are focus strings, unique by construction: a destination and its subscription commonly share a name. */
function findGroups(nodes: FlowNodeView[]) {
  return FIND_GROUPS.map(({ group, kinds }) => {
    const seen = new Map<string, string>();
    for (const n of nodes) {
      const focus = focusOf(n);
      if (focus && kinds.includes(n.kind ?? '')) seen.set(focus, n.label ?? '');
    }
    return {
      group,
      items: [...seen].map(([value, label]) => ({ value, label })).sort((a, b) => a.label.localeCompare(b.label)),
    };
  }).filter((g) => g.items.length > 0);
}

function FlowBody({
  clusterId,
  data,
  breakdownPending,
  search,
  rank,
  setSearch,
}: Readonly<{
  clusterId: string;
  data: FlowGraphView;
  breakdownPending: boolean;
  search: FlowSearch;
  rank: FlowRank;
  setSearch: SetSearch;
}>) {
  const now = useServerNow();
  // The selection is in the address (flow-visualization spec): a reload or a shared link restores it.
  const selected = search.node ?? null;
  const opener = useRef<HTMLElement | null>(null);

  const totals = data.totals ?? { paths: 0, shown: 0, limit: DEFAULT_LIMIT, clamped: false };
  const kpis = data.kpis ?? { backlog: 0, clients: 0, faults: 0 };
  const nothingAtAll = (totals.paths ?? 0) === 0 && (kpis.clients ?? 0) === 0 && !data.focus;

  const select = useCallback(
    (id: string | null) => {
      if (id) opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setSearch({ node: id ?? undefined });
    },
    [setSearch],
  );

  const closeInspector = () => {
    setSearch({ node: undefined });
    const back = opener.current;
    if (back?.isConnected) back.focus();
  };

  const focusOn = (next: string) => {
    setSearch({ focus: next, hops: undefined, node: undefined });
  };

  return (
    <>
      <Section title="Totals across every path">
        <FlowKpis kpis={kpis} />
      </Section>
      <BrokerNodeNotices nodes={data.brokerNodes ?? []} />

      {data.measuring ? (
        <Text size="sm" c="dimmed" role="status">
          Measuring client rates. The first rates appear after two samples, about{' '}
          {(data.sampleIntervalSeconds ?? 15) * 2} seconds after this view opened.
        </Text>
      ) : null}

      <FlowMain
        clusterId={clusterId}
        data={data}
        nothingAtAll={nothingAtAll}
        search={search}
        setSearch={setSearch}
        breakdownPending={breakdownPending}
        selected={selected}
        onSelect={select}
        onCloseInspector={closeInspector}
        onFocusOn={focusOn}
      />

      <div className={classes.footer}>
        <Text size="xs" c="dimmed">
          Showing {totals.shown} of {totals.paths} {totals.paths === 1 ? 'path' : 'paths'}, ranked by{' '}
          {RANK_LABELS[rank]}.
        </Text>
        {(totals.shown ?? 0) < (totals.paths ?? 0) && !data.focus ? (
          <Text size="xs" c="dimmed">
            Raise the limit, or focus a client, destination or subscription to reach the rest.
          </Text>
        ) : null}
        {totals.clamped ? (
          <Text size="xs" c="dimmed">
            The server draws at most {totals.limit} paths.
          </Text>
        ) : null}
        <Text size="xs" c="dimmed">
          Totals cover every path.{' '}
          {data.sampledAt
            ? `Clients sampled ${elapsedLabel(now - Date.parse(data.sampledAt))} ago.`
            : 'Clients not sampled yet.'}
        </Text>
      </div>

      <VisuallyHidden role="status">
        {`${totals.shown} of ${totals.paths} paths shown. ${kpis.faults ?? 0} faults. Messages in ${totalRateLabel(
          kpis.inRate,
        )}.`}
      </VisuallyHidden>
    </>
  );
}

/** The body under the totals: why the focus matches nothing, why there is no flow, or the views. */
function FlowMain({
  clusterId,
  data,
  nothingAtAll,
  search,
  setSearch,
  breakdownPending,
  selected,
  onSelect,
  onCloseInspector,
  onFocusOn,
}: Readonly<{
  clusterId: string;
  data: FlowGraphView;
  nothingAtAll: boolean;
  search: FlowSearch;
  setSearch: SetSearch;
  breakdownPending: boolean;
  selected: string | null;
  onSelect: (id: string | null) => void;
  onCloseInspector: () => void;
  onFocusOn: (focus: string) => void;
}>) {
  if (data.focus && !data.focus.matched) {
    return (
      <EmptyState
        kind="filtered"
        title={`Nothing matches the focus ${data.focus.kind} ${data.focus.name}`}
        description="It may have been deleted, or its clients disconnected since the link was shared. Clear the focus to see the busiest paths again."
        onClearFilters={() => setSearch({ focus: undefined, hops: undefined })}
      />
    );
  }
  if (nothingAtAll) return <EmptyFlow unreachable={unreachableNodes(data)} />;
  return (
    <FlowViews
      clusterId={clusterId}
      data={data}
      search={search}
      setSearch={setSearch}
      breakdownPending={breakdownPending}
      selected={selected}
      onSelect={onSelect}
      onCloseInspector={onCloseInspector}
      onFocusOn={onFocusOn}
    />
  );
}

/** What the flow says when there is nothing to draw yet: nodes that did not answer, or nothing seen at all. */
function EmptyFlow({ unreachable }: Readonly<{ unreachable: string[] }>) {
  if (unreachable.length > 0) {
    return (
      <EmptyState
        kind="unreachable"
        title="No flow to show, and some nodes did not answer"
        description="Flow draws the clients producing to each destination, the subscriptions those destinations route into, and the clients consuming them. These nodes did not answer the last sweep, so this is an incomplete view rather than a cluster with no flow."
        nodes={unreachable}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No flow to show yet"
      description={
        <>
          Flow draws the clients producing to each destination, the subscriptions those destinations route into, and the
          clients consuming them. This cluster has no subscriptions Rahdar has seen and no connected producers or
          consumers. Clients appear here within one sampling interval of attaching, and new subscriptions appear once
          the subscription sweep has read them.
        </>
      }
    />
  );
}

/** The view switch, the find box, the layers, and the chosen view (graph, table or both). */
function FlowViews({
  clusterId,
  data,
  search,
  setSearch,
  breakdownPending,
  selected,
  onSelect,
  onCloseInspector,
  onFocusOn,
}: Readonly<{
  clusterId: string;
  data: FlowGraphView;
  search: FlowSearch;
  setSearch: SetSearch;
  breakdownPending: boolean;
  selected: string | null;
  onSelect: (id: string | null) => void;
  onCloseInspector: () => void;
  onFocusOn: (focus: string) => void;
}>) {
  const [paused, setPaused] = useState(false);
  const tab = search.tab ?? 'graph';

  return (
    <Section title="Paths">
      <Toolbar
        label="Flow view"
        start={
          <>
            <SegmentedControl
              size="xs"
              aria-label="View as"
              data={[
                { value: 'graph', label: 'Graph' },
                { value: 'table', label: 'Table' },
                { value: 'split', label: 'Split' },
              ]}
              value={tab}
              onChange={(value) => setSearch({ tab: value === 'graph' ? undefined : value })}
            />
            <Select
              label="Find in this view"
              placeholder="Client, destination or subscription"
              size="xs"
              w="16.25rem"
              searchable
              clearable
              limit={30}
              data={findGroups(data.nodes ?? [])}
              value={null}
              nothingFoundMessage="Not in the shown paths — raise the limit to reach more"
              onChange={(value) => {
                if (value) onFocusOn(value);
              }}
            />
          </>
        }
        end={tab === 'table' ? null : <MotionControl paused={paused} onToggle={() => setPaused((p) => !p)} />}
      />

      <Group gap="xs" align="center" wrap="wrap">
        <Text size="xs" c="dimmed" id="flow-layers">
          Layers
        </Text>
        <Chip.Group
          multiple
          value={parseLayers(search.layers)}
          onChange={(value) => setSearch({ layers: layersParam(value as never[]) })}
        >
          <Group gap="xs" wrap="wrap" role="group" aria-labelledby="flow-layers">
            {FLOW_LAYERS.map((layer) => (
              <Chip key={layer} value={layer} size="xs" variant="outline">
                {LAYER_LABELS[layer]}
              </Chip>
            ))}
          </Group>
        </Chip.Group>
      </Group>

      {(data.assumptions ?? []).map((assumption) => (
        <Text key={assumption} size="xs" c="dimmed">
          {assumption}
        </Text>
      ))}

      <FlowPane
        tab={tab}
        clusterId={clusterId}
        data={data}
        search={search}
        setSearch={setSearch}
        breakdownPending={breakdownPending}
        selected={selected}
        paused={paused}
        onSelect={onSelect}
        onCloseInspector={onCloseInspector}
        onFocusOn={onFocusOn}
      />
    </Section>
  );
}

/** Pause and resume for the motion on the graph, or the reason there is none. */
function MotionControl({ paused, onToggle }: Readonly<{ paused: boolean; onToggle: () => void }>) {
  const reducedMotion = useReducedMotion();
  if (reducedMotion) {
    return (
      <Text size="xs" c="dimmed">
        Motion off: your system asks for reduced motion.
      </Text>
    );
  }
  return (
    <Button
      size="xs"
      variant="default"
      // As wide as the longer label, so the toolbar does not shift when the label changes.
      className={classes.motionControl}
      aria-pressed={paused}
      leftSection={
        paused ? <IconPlayerPlay size="0.875rem" aria-hidden /> : <IconPlayerPause size="0.875rem" aria-hidden />
      }
      onClick={onToggle}
    >
      {paused ? 'Resume motion' : 'Pause motion'}
    </Button>
  );
}

/** What the operator asked the graph to show; a change gives the next layout a fresh fit. */
function viewKeyOf(search: FlowSearch): string {
  return [search.focus, search.hops, search.limit, search.rank, search.groupBy, search.layers].join('|');
}

/** The chosen view: the graph with its inspector, the table, or the two split with a monitoring pane. */
function FlowPane({
  tab,
  clusterId,
  data,
  search,
  setSearch,
  breakdownPending,
  selected,
  paused,
  onSelect,
  onCloseInspector,
  onFocusOn,
}: Readonly<{
  tab: string;
  clusterId: string;
  data: FlowGraphView;
  search: FlowSearch;
  setSearch: SetSearch;
  breakdownPending: boolean;
  selected: string | null;
  paused: boolean;
  onSelect: (id: string | null) => void;
  onCloseInspector: () => void;
  onFocusOn: (focus: string) => void;
}>) {
  const [split, setSplit] = useLocalStorage<number[]>({
    key: 'as:flow:split',
    defaultValue: DEFAULT_SPLIT,
    getInitialValueInEffect: false,
  });

  if (tab === 'split') {
    return (
      <Splitter
        onResizeEnd={(_, sizes) => {
          if (validSplit(sizes)) setSplit(sizes);
        }}
        attributes={{ handle: { 'aria-label': 'Resize the monitoring pane' } }}
      >
        <Splitter.Pane defaultSize={validSplit(split) ? split[0] : DEFAULT_SPLIT[0]} min={GRAPH_MIN}>
          <FlowCanvas
            clusterId={clusterId}
            graph={data}
            selectedId={selected}
            onSelect={onSelect}
            paused={paused}
            viewKey={viewKeyOf(search)}
          />
        </Splitter.Pane>
        <Splitter.Pane defaultSize={validSplit(split) ? split[1] : DEFAULT_SPLIT[1]} min={PANE_MIN} collapsible>
          <FlowMonitorPane
            clusterId={clusterId}
            graph={data}
            nodeId={selected}
            range={search.range ?? '1h'}
            breakdownPending={breakdownPending}
            onRangeChange={(range: MetricRange) => setSearch({ range: range === '1h' ? undefined : range })}
            onClear={onCloseInspector}
          />
        </Splitter.Pane>
      </Splitter>
    );
  }
  if (tab === 'graph') {
    return (
      <FlowCanvas
        clusterId={clusterId}
        graph={data}
        selectedId={selected}
        onSelect={onSelect}
        paused={paused}
        viewKey={viewKeyOf(search)}
        inspector={{ onClose: onCloseInspector, onFocus: onFocusOn }}
      />
    );
  }
  return (
    <div className={classes.tableFrame}>
      <FlowTable
        clusterId={clusterId}
        graph={data}
        sort={search.sort}
        loading={breakdownPending}
        filtered={Boolean(search.layers) || Boolean(search.focus)}
        onSortChange={(sort) => setSearch({ sort })}
        onFocus={(next) => setSearch({ focus: next, hops: undefined })}
        onClearFilters={() => setSearch({ layers: undefined, focus: undefined, hops: undefined })}
      />
    </div>
  );
}
