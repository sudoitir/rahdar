import { absoluteLabel } from '../../kernel/time/time.ts';
import { AUTO, localZone } from '../../kernel/time/timezone.ts';
import type { Column } from '../../ui/table/index.ts';
import type { BrokerEventView } from './api.ts';

/** UTC or the display zone, second precision: a broker event's useful comparison is to another one. */
export function occurredAt(e: BrokerEventView): string {
  return absoluteLabel(e.occurredAt);
}

export function subjectOf(e: BrokerEventView): string {
  return e.consumerName ?? e.sessionName ?? e.connectionName ?? e.routingName ?? '—';
}

/** The word for what an event is about, from its type's prefix, so the grid can be read by family. */
export function family(type: string): string {
  if (type.startsWith('CONSUMER')) return 'consumer';
  if (type.startsWith('SESSION')) return 'session';
  if (type.startsWith('CONNECTION')) return 'connection';
  if (type.startsWith('BINDING') || type.startsWith('ADDRESS')) return 'binding';
  if (type.startsWith('MESSAGE')) return 'message';
  if (type.startsWith('UNKNOWN')) return 'unknown';
  return 'other';
}

/**
 * The events grid's columns. The time identifies a row and is never hidden; the type and what the
 * event is about go next, and the family word and the remote address are the first to be hidden when
 * the table is narrow. The time is written in the display zone `zone`, which the header states, so a
 * view builds its columns again when the zone changes.
 */
export function eventColumns(zone: string): Column<BrokerEventView>[] {
  return [
    {
      id: 'time',
      header: 'Time',
      accessor: occurredAt,
      description: `When the broker raised the notification, in ${zone === AUTO ? localZone() : zone}`,
      kind: 'time',
      priority: 'essential',
    },
    { id: 'type', header: 'Type', accessor: (e) => e.type, kind: 'code', priority: 'high' },
    { id: 'family', header: 'Family', accessor: (e) => family(e.type), kind: 'status', priority: 'low' },
    { id: 'address', header: 'Address', accessor: (e) => e.destination ?? '—', kind: 'identifier', priority: 'high' },
    { id: 'subject', header: 'Subject', accessor: subjectOf, kind: 'identifier', priority: 'high' },
    { id: 'remote', header: 'Remote', accessor: (e) => e.remoteAddress ?? '—', kind: 'code', priority: 'low' },
  ];
}
