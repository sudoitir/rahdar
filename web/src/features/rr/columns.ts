import { createElement } from 'react';

import { elapsedLabel } from '../../kernel/time/time.ts';
import type { Column } from '../../ui/table/index.ts';
import type { ExpectationDiagnosticsView, ExpectationView, FlowView } from './api.ts';
import {
  ExpectationEnabled,
  ExpectationRemove,
  ExpectationStatus,
  FlowStateBadge,
  ReplyDestinationsCell,
  type ExpectationControls,
} from './cells.tsx';
import { stateLabel } from './rrState.ts';
import { replyDestinationsText, statusText } from './words.ts';

/**
 * The flows grid's columns. The state and the request destination identify a flow and are never hidden;
 * the age and the latency go next, and the correlation id is the first to be hidden when the table is
 * narrow. `now` ages the flow, so a view builds its columns again when it ticks.
 */
export function flowColumns(now: number): Column<FlowView>[] {
  return [
    {
      id: 'state',
      header: 'State',
      accessor: (f) => stateLabel(f.state),
      cell: (f) => createElement(FlowStateBadge, { state: f.state }),
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    {
      id: 'destination',
      header: 'Destination',
      accessor: (f) => f.requestDestination,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'correlation',
      header: 'Correlation id',
      accessor: (f) => f.correlationId ?? '—',
      kind: 'identifier',
      priority: 'low',
    },
    {
      id: 'age',
      header: 'Age',
      accessor: (f) => elapsedLabel(now - Date.parse(f.requestedAt)),
      kind: 'time',
      priority: 'high',
    },
    {
      id: 'latency',
      header: 'Latency',
      accessor: (f) => (f.latencyMs == null ? '—' : `${f.latencyMs}ms`),
      kind: 'number',
      priority: 'high',
    },
  ];
}

/** What a traced destination's table needs from its view: the clock, each destination's last tick and what its two controls may do. */
export interface ExpectationRows {
  now: number;
  statusOf: (id: string) => ExpectationDiagnosticsView | undefined;
  controls: ExpectationControls;
}

/**
 * The traced destinations' columns. The request destination identifies a row, and the two controls (the
 * switch and Remove) are never hidden, so a row can always be switched or removed; the reply
 * destinations and the sampler's last tick wrap, and the plain settings go first when the table is narrow.
 */
export function expectationColumns({ now, statusOf, controls }: ExpectationRows): Column<ExpectationView>[] {
  return [
    {
      id: 'requestDestination',
      header: 'Request destination',
      accessor: (e) => e.requestDestination,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'replyDestinations',
      header: 'Reply destinations',
      accessor: replyDestinationsText,
      cell: (e) => createElement(ReplyDestinationsCell, { expectation: e }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'deadline',
      header: 'Deadline',
      accessor: (e) => (e.deadlineMs == null ? 'from message' : `${e.deadlineMs}ms`),
      kind: 'text',
      priority: 'low',
    },
    { id: 'samples', header: 'Samples/min', accessor: (e) => e.samplePerMin, kind: 'number', priority: 'low' },
    {
      id: 'status',
      header: 'Status',
      accessor: (e) => statusText(statusOf(e.id), now),
      cell: (e) => createElement(ExpectationStatus, { status: statusOf(e.id), now }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'payload',
      header: 'Payload',
      accessor: (e) => (e.capturePayload ? 'yes' : 'no'),
      kind: 'status',
      priority: 'low',
    },
    {
      id: 'enabled',
      header: 'Enabled',
      accessor: (e) => (e.enabled ? 'on' : 'off'),
      cell: (e) => createElement(ExpectationEnabled, { expectation: e, controls }),
      kind: 'status',
      priority: 'essential',
    },
    {
      id: 'remove',
      header: 'Remove',
      accessor: (e) => `Remove ${e.requestDestination}`,
      cell: (e) => createElement(ExpectationRemove, { expectation: e, controls }),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** The diagnostics table's columns: what the sampler did for each traced destination. */
export function diagnosticsColumns(now: number): Column<ExpectationDiagnosticsView>[] {
  return [
    {
      id: 'requestDestination',
      header: 'Request destination',
      accessor: (e) => e.requestDestination,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'lastTick',
      header: 'Last tick',
      accessor: (e) => statusText(e, now),
      cell: (e) => createElement(ExpectationStatus, { status: e, now }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    { id: 'browsed', header: 'Browsed', accessor: (e) => e.messagesBrowsed, kind: 'number', priority: 'high' },
    { id: 'observed', header: 'Observed', accessor: (e) => e.observations, kind: 'number', priority: 'high' },
  ];
}
