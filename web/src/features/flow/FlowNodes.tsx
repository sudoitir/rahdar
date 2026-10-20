import { memo, useContext, useId, useRef, type ReactNode } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';

import type { FlowNodeView } from './api.ts';
import { FlowCanvasContext } from './canvasContext.ts';
import { FAULT_LABELS, formatCount } from './flowFormat.ts';
import { outline, type FlowNodeData, type LaneData } from './flowLayout.ts';
import { anchorBelow, clampToViewport } from '../../ui/table/menuAnchor.ts';
import classes from './FlowCanvas.module.css';

const KIND_WORD: Record<string, string> = {
  PRODUCER: 'Producing client',
  CONSUMER: 'Consuming client',
  DESTINATION: 'Address',
  SUBSCRIPTION: 'Queue',
  REMOTE: 'Remote',
};

function faultWords(view: FlowNodeView): string[] {
  return (view.faults ?? []).map((f) => FAULT_LABELS[f] ?? f.toLowerCase());
}

/** The sentence a screen reader hears for a node: what it is, its figures, and what is wrong. */
function nodeSentence(view: FlowNodeView): string {
  const parts = [`${KIND_WORD[view.kind ?? ''] ?? 'Node'} ${view.label}`];
  if (view.kind === 'SUBSCRIPTION') {
    parts.push(
      view.messageCount === null || view.messageCount === undefined
        ? 'not swept yet'
        : `${formatCount(view.messageCount)} waiting, ${view.consumerCount ?? 0} consumers`,
    );
  }
  if ((view.members ?? 0) > 1) parts.push(`${view.members} connections`);
  const faults = faultWords(view);
  if (faults.length) parts.push(`fault: ${faults.join(', ')}`);
  return `${parts.join(', ')}.`;
}

type Shape = 'pill' | 'tag' | 'box' | 'hex';

/**
 * A tag or a hexagon, drawn rather than clipped: the outline carries the fill, the border (dashed for a
 * hexagon, the fault colour for a fault) and the focus ring, so all three follow the slanted sides.
 */
function ShapeOutline({ shape, width, height }: Readonly<{ shape: 'tag' | 'hex'; width: number; height: number }>) {
  const points = outline(shape, width, height);
  return (
    <svg
      className={classes.outline}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
      focusable="false"
    >
      <polygon className={classes.outlineRing} points={points} />
      <polygon className={classes.outlineGap} points={points} />
      <polygon className={classes.outlineShape} data-shape={shape} points={points} />
    </svg>
  );
}

function Frame({
  id,
  data,
  shape,
  width,
  height,
  inbound,
  outbound,
  children,
}: Readonly<{
  id: string;
  data: FlowNodeData;
  shape: Shape;
  width?: number;
  height?: number;
  inbound: boolean;
  outbound: boolean;
  children: ReactNode;
}>) {
  const { select, emphasize, openMenu } = useContext(FlowCanvasContext);
  // Shift+F10 and the menu key are followed by the browser's own contextmenu event, which would
  // reopen the menu at the pointer and close the one the keyboard opened.
  const suppressContextMenuUntil = useRef(0);
  const faults = faultWords(data.view);
  const descriptionId = useId();
  return (
    <div
      className={classes.node}
      data-shape={shape}
      data-fault={faults.length > 0 || undefined}
      data-dimmed={data.dimmed || undefined}
      role="button"
      tabIndex={0}
      aria-describedby={descriptionId}
      onClick={() => select(id, false)}
      onContextMenu={(event) => {
        event.preventDefault();
        if (performance.now() < suppressContextMenuUntil.current) return;
        openMenu(id, clampToViewport({ x: event.clientX, y: event.clientY }), event.currentTarget);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          select(id, true);
        } else if ((event.key === 'F10' && event.shiftKey) || event.key === 'ContextMenu') {
          event.preventDefault();
          suppressContextMenuUntil.current = performance.now() + 500;
          openMenu(id, anchorBelow(event.currentTarget), event.currentTarget);
        }
      }}
      onMouseEnter={() => emphasize(id)}
      onMouseLeave={() => emphasize(null)}
      onFocus={() => emphasize(id)}
      onBlur={() => emphasize(null)}
    >
      {/* Hidden from the name (which is the node's visible lines) but still its description. */}
      <span id={descriptionId} hidden>
        {nodeSentence(data.view)}
      </span>
      {(shape === 'tag' || shape === 'hex') && width && height ? (
        <ShapeOutline shape={shape} width={width} height={height} />
      ) : null}
      {inbound ? (
        <Handle type="target" position={Position.Left} className={classes.handle} isConnectable={false} />
      ) : null}
      {children}
      {/* A fault takes the place of the last line, so the node keeps the height its layout reserved. */}
      {faults.length ? (
        <span className={classes.fault} title={faults.join(', ')}>
          {faults.join(', ')}
        </span>
      ) : null}
      {outbound ? (
        <Handle type="source" position={Position.Right} className={classes.handle} isConnectable={false} />
      ) : null}
    </div>
  );
}

export const ClientNode = memo(function ClientNode({ id, data }: NodeProps) {
  const d = data as FlowNodeData;
  const v = d.view;
  const producing = v.kind === 'PRODUCER';
  const meta = [producing ? 'produces' : 'consumes', ...(v.protocols ?? []), ...(v.brokerNodes ?? [])];
  return (
    <Frame id={id} data={d} shape="pill" inbound={!producing} outbound={producing}>
      <div className={classes.head}>
        <span className={classes.label} title={v.label}>
          {v.label}
        </span>
        {(v.members ?? 0) > 1 ? <span className={classes.count}>×{v.members}</span> : null}
      </div>
      <span className={classes.meta}>{meta.join(' · ')}</span>
    </Frame>
  );
});

const ADDRESS_ROLE: Record<string, string> = {
  ANONYMOUS: 'no address named — chosen per message',
  DEAD_LETTER: 'dead-letter address',
  EXPIRY: 'expiry address',
  CAPTURE: "Rahdar's message capture",
};

const QUEUE_ROLE: Record<string, string> = {
  STORE_AND_FORWARD: 'cluster redistribution',
  TEMPORARY: 'short-lived reply queues',
  CAPTURE: "Rahdar's message capture",
};

const DELIVERY: Record<string, string> = {
  MULTICAST: 'multicast · every queue gets a copy',
  ANYCAST: 'anycast · queues share',
};

export const AddressNode = memo(function AddressNode({ id, data, width, height }: NodeProps) {
  const d = data as FlowNodeData;
  const v = d.view;
  const routing = (v.routingTypes ?? []).map((t) => DELIVERY[t] ?? t.toLowerCase());
  const role = v.role ? ADDRESS_ROLE[v.role] : undefined;
  return (
    <Frame id={id} data={d} shape="tag" width={width} height={height} inbound outbound>
      <div className={classes.head}>
        <span className={classes.label} title={v.label}>
          {v.label}
        </span>
      </div>
      <span className={classes.meta}>{role ?? (routing.length ? routing.join(' / ') : 'no queue bound')}</span>
    </Frame>
  );
});

export const QueueNode = memo(function QueueNode({ id, data }: NodeProps) {
  const d = data as FlowNodeData;
  const v = d.view;
  const swept = v.messageCount !== null && v.messageCount !== undefined;
  const sweptLabel = swept
    ? `${formatCount(v.messageCount!)} waiting · ${v.consumerCount ?? 0} consumers`
    : 'not swept yet';
  return (
    <Frame id={id} data={d} shape="box" inbound outbound>
      <div className={classes.head}>
        <span className={classes.label} title={v.label}>
          {v.label}
        </span>
      </div>
      <span className={classes.depth} aria-hidden="true">
        <span className={classes.depthFill} style={{ inlineSize: `${Math.round(d.depth * 100)}%` }} />
      </span>
      <span className={classes.meta}>{(v.role && QUEUE_ROLE[v.role]) || sweptLabel}</span>
    </Frame>
  );
});

export const RemoteNode = memo(function RemoteNode({ id, data, width, height }: NodeProps) {
  const d = data as FlowNodeData;
  const v = d.view;
  return (
    <Frame id={id} data={d} shape="hex" width={width} height={height} inbound outbound={false}>
      <div className={classes.head}>
        <span className={classes.label} title={v.label}>
          {v.label}
        </span>
      </div>
      <span className={classes.meta}>
        {v.role === 'CLUSTER_NODE' ? 'another node of this cluster' : 'outside this cluster'}
      </span>
    </Frame>
  );
});

export const LaneNode = memo(function LaneNode({ data }: NodeProps) {
  const d = data as LaneData;
  return (
    <div className={classes.lane}>
      {d.title}
      <span className={classes.laneCount}>{d.count}</span>
    </div>
  );
});
