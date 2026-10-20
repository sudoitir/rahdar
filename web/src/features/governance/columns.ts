import { createElement } from 'react';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { FindingView, RuleView } from './api.ts';
import {
  FindingDecision,
  FindingStatus,
  RuleChanges,
  RuleEnabled,
  SelectorCell,
  type FindingControls,
  type RuleControls,
} from './cells.tsx';
import { actionWords, fieldOf, statusLabel } from './words.ts';

/** What the rules table needs from its view: what its row controls may do, gated and busy per row. */
export interface RuleRows {
  controls: RuleControls;
}

/**
 * The masking rules' columns. What a rule matches identifies it; the switch and the changes are never
 * hidden, so a rule can always be switched off. The class and the action go first when the table is narrow.
 */
export function ruleColumns({ controls }: RuleRows): Column<RuleView>[] {
  return [
    {
      id: 'selector',
      header: 'Matches',
      accessor: (r) => r.selector,
      cell: (r) => createElement(SelectorCell, { rule: r }),
      kind: 'code',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'destinations',
      header: 'Destinations',
      accessor: (r) => r.destinationPattern ?? 'All destinations',
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    { id: 'class', header: 'Class', accessor: (r) => r.dataClassLabel, kind: 'text', priority: 'low' },
    { id: 'action', header: 'Action', accessor: actionWords, kind: 'text', priority: 'low' },
    {
      id: 'enabled',
      header: 'Enabled',
      accessor: (r) => (r.enabled ? 'on' : 'off'),
      cell: (r) => createElement(RuleEnabled, { rule: r, controls }),
      kind: 'status',
      priority: 'essential',
    },
    {
      id: 'changes',
      header: 'Changes',
      accessor: (r) => (r.builtin ? 'Can be disabled, not deleted.' : 'Edit Delete'),
      cell: (r) => createElement(RuleChanges, { rule: r, controls }),
      kind: 'status',
      wrap: true,
      priority: 'essential',
    },
  ];
}

/** What the findings table needs from its view: what its decision controls may do, gated and busy per row. */
export interface FindingRows {
  /** The display zone, which the time column states. */
  zone: string;
  controls: FindingControls;
}

/**
 * The classification inbox's columns. The field and the address identify a finding and are never hidden,
 * and neither is the decision. The times are written in the display zone `zone`, so a view builds the
 * columns again when it changes.
 */
export function findingColumns({ zone, controls }: FindingRows): Column<FindingView>[] {
  return [
    { id: 'field', header: 'Field', accessor: fieldOf, kind: 'identifier', priority: 'essential' },
    {
      id: 'destination',
      header: 'Destination',
      accessor: (f) => f.destination,
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'class', header: 'Class', accessor: (f) => f.dataClassLabel, kind: 'text', priority: 'high' },
    {
      id: 'seen',
      header: 'Seen',
      accessor: (f) => f.hitCount.toLocaleString(),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'lastSeen',
      header: 'Last seen',
      accessor: (f) => absoluteLabel(Date.parse(f.lastSeenAt)),
      description: `When the detectors last saw it, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'low',
    },
    {
      id: 'status',
      header: 'Status',
      accessor: (f) => statusLabel(f.status),
      cell: (f) => createElement(FindingStatus, { finding: f }),
      kind: 'status',
      badge: true,
      priority: 'high',
    },
    {
      id: 'decision',
      header: 'Decision',
      accessor: (f) => (f.status === 'OPEN' ? 'Confirm Dismiss' : ''),
      cell: (f) => createElement(FindingDecision, { finding: f, controls }),
      kind: 'status',
      wrap: true,
      priority: 'essential',
    },
  ];
}
