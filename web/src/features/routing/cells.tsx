import { useState } from 'react';
import { Button, Modal, Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { RouteView } from './api.ts';
import { BrokerXmlRemedy, DRIFT_SENTENCE } from './DivertActions.tsx';
import { attribute, routeEnds, routeState } from './routeWords.ts';
import classes from './RoutesView.module.css';
import { MiddleTruncate } from '../../ui/table/MiddleTruncate.tsx';

/**
 * A Rahdar-created route's ownership, as a control rather than a hover title: it opens the
 * broker.xml that would make the deployed configuration carry it, reachable from the keyboard.
 */
function RahdarOwned({ route }: Readonly<{ route: RouteView }>) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="compact-xs" variant="default" onClick={() => setOpen(true)}>
        Rahdar — not in broker.xml
      </Button>
      <Modal opened={open} onClose={() => setOpen(false)} title={`"${route.name}" is not in broker.xml`} size="lg">
        <Stack gap="sm">
          <Text size="sm">{DRIFT_SENTENCE}</Text>
          {route.drift ? <BrokerXmlRemedy xml={route.drift} /> : null}
        </Stack>
      </Modal>
    </>
  );
}

/**
 * A route's direction as one object: source, arrow, target.
 *
 * <p>The question this view exists to answer is "where does traffic on this
 * destination go", and reconstructing that from two separate columns is exactly the
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

/** Who created the route: message capture, an operator through Rahdar, or nobody Rahdar knows of. */
export function OwnerCell({ route: r }: Readonly<{ route: RouteView }>) {
  if (r.owner === 'MESSAGE_CAPTURE') {
    return (
      <span title="Serves a message capture subscription">
        <StatusBadge>message capture</StatusBadge>
      </span>
    );
  }
  if (r.owner === 'OPERATOR') return <RahdarOwned route={r} />;
  return (
    <Text
      size="xs"
      c="dimmed"
      title="Rahdar has no record of creating this route. That is not a claim about where it came from."
    >
      not recorded
    </Text>
  );
}

/** A route's direction: where traffic on its source goes. */
export function RouteDirection({ route: r }: Readonly<{ route: RouteView }>) {
  const { from, to } = routeEnds(r);
  return <Direction from={from} to={to} />;
}

/** The state of a route; a started link that cannot reach its target is warned, since started and connected differ. */
export function RouteState({ route: r }: Readonly<{ route: RouteView }>) {
  const words = routeState(r);
  if (r.routeClass === 'LINK') {
    const halfUp = attribute(r, 'started') === 'true' && attribute(r, 'connected') !== 'true';
    return <StatusBadge tone={halfUp ? 'warning' : 'neutral'}>{words}</StatusBadge>;
  }
  return <Text size="xs">{words}</Text>;
}

/** How many nodes carry the route, naming them on hover. */
export function NodesPresent({ route: r }: Readonly<{ route: RouteView }>) {
  return (
    <Text size="xs" title={r.perNode.map((n) => n.nodeName).join(', ')}>
      {r.nodesPresent}/{r.nodesTotal}
    </Text>
  );
}
