import { useState } from 'react';
import { Button, Modal, Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { BridgeRow, DivertRow } from './api.ts';
import { BrokerXmlRemedy, DRIFT_SENTENCE } from './DivertActions.tsx';
import classes from './RoutingView.module.css';
import { MiddleTruncate } from '../../ui/table/MiddleTruncate.tsx';

/**
 * A Rahdar-created divert's ownership, as a control rather than a hover title: it opens the
 * broker.xml that would make the deployed configuration carry it, reachable from the keyboard.
 */
function RahdarOwned({ divert }: Readonly<{ divert: DivertRow }>) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="compact-xs" variant="default" onClick={() => setOpen(true)}>
        Rahdar — not in broker.xml
      </Button>
      <Modal opened={open} onClose={() => setOpen(false)} title={`"${divert.name}" is not in broker.xml`} size="lg">
        <Stack gap="sm">
          <Text size="sm">{DRIFT_SENTENCE}</Text>
          {divert.brokerXml ? <BrokerXmlRemedy xml={divert.brokerXml} /> : null}
        </Stack>
      </Modal>
    </>
  );
}

/**
 * A divert's direction as one object: source, arrow, destination.
 *
 * <p>The question this view exists to answer is "where does traffic on this
 * address go", and reconstructing that from two separate columns is exactly the
 * work the routing spec says an operator should not have to do. The accessible
 * name spells the relationship out, because the arrow is a glyph.
 */
function Direction({ from, to }: Readonly<{ from: string; to: string }>) {
  return (
    <div className={classes.direction} aria-label={`from ${from} to ${to}`}>
      <Text size="xs" className={classes.endpoint} title={from}>
        <MiddleTruncate text={from} />
      </Text>
      <span className={classes.arrow} aria-hidden="true">
        →
      </span>
      <Text size="xs" className={classes.endpoint} title={to}>
        <MiddleTruncate text={to} />
      </Text>
    </div>
  );
}

/** Who created the divert: message capture, an operator through Rahdar, or nobody Rahdar knows of. */
export function OwnerCell({ divert: r }: Readonly<{ divert: DivertRow }>) {
  if (r.owner === 'MESSAGE_CAPTURE') {
    return (
      <span title="Serves a message capture subscription">
        <StatusBadge>message capture</StatusBadge>
      </span>
    );
  }
  if (r.owner === 'OPERATOR') return <RahdarOwned divert={r} />;
  return (
    <Text
      size="xs"
      c="dimmed"
      title="Rahdar has no record of creating this divert. That is not a claim about where it came from."
    >
      not recorded
    </Text>
  );
}

/** A divert's route: where traffic on its address goes. */
export function DivertRoute({ divert: r }: Readonly<{ divert: DivertRow }>) {
  return <Direction from={r.address} to={r.forwardingAddress} />;
}

/** A bridge's route: the queue it drains and the address it forwards to, named when unrecorded. */
export function BridgeRoute({ bridge: r }: Readonly<{ bridge: BridgeRow }>) {
  return <Direction from={r.queueName ?? '(unnamed queue)'} to={r.forwardingAddress ?? '(the target broker)'} />;
}

// In words, never by colour alone: the difference between traffic being duplicated and traffic being
// taken away is the most consequential fact in the diverts table.
export function DivertEffect({ divert: r }: Readonly<{ divert: DivertRow }>) {
  return (
    <Text size="xs" title={r.exclusive ? 'Exclusive divert' : 'Non-exclusive divert'}>
      {r.exclusive ? 'takes the message' : 'copies the message'}
    </Text>
  );
}

// Started and connected are different facts. A bridge that is started and cannot reach its target is
// the state "is this bridge running" is asking about, and collapsing the two would answer the wrong question.
export function BridgeStateBadge({ words, halfUp }: Readonly<{ words: string; halfUp: boolean }>) {
  return <StatusBadge tone={halfUp ? 'warning' : 'neutral'}>{words}</StatusBadge>;
}

/** How many nodes carry the divert or bridge, naming them on hover. */
export function NodesPresent({ row: r }: Readonly<{ row: DivertRow | BridgeRow }>) {
  return (
    <Text size="xs" title={r.perNode.map((n) => n.nodeName).join(', ')}>
      {r.nodesPresent}/{r.nodesTotal}
    </Text>
  );
}
