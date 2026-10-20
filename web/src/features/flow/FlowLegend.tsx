import { useId, type CSSProperties, type ReactNode } from 'react';

import { MIN_WIDTH, RATE_CAP, widthPx } from './edgeEncoding.ts';
import { NODE_SIZE, outline } from './flowLayout.ts';
import classes from './FlowCanvas.module.css';

/**
 * Reference weights. 1, 100 and the cap rather than 50: on the square-root scale 1 and 50 msg/s
 * are only 1.5px apart, under the ~1.8px a reader can tell apart at a glance; 100 is 2.3px clear.
 */
const REFERENCE_RATES = [
  { rate: 1, label: '1 msg/s' },
  { rate: 100, label: '100 msg/s' },
  { rate: RATE_CAP, label: `${RATE_CAP}+ msg/s` },
];

/** One legend entry: a mark drawn with the canvas's own classes, then what it means. */
function LegendItem({ mark, children }: Readonly<{ mark?: ReactNode; children: ReactNode }>) {
  return (
    <span className={classes.legendItem}>
      {mark}
      {children}
    </span>
  );
}

/** A titled run of entries. */
function LegendGroup({ title, children }: Readonly<{ title: string; children: ReactNode }>) {
  const id = useId();
  return (
    <div className={classes.legendGroup} role="group" aria-labelledby={id}>
      <span id={id} className={classes.legendTitle}>
        {title}
      </span>
      {children}
    </div>
  );
}

/** A node's shape at the proportions of the real one, drawn as the canvas draws it. */
function node(shape: 'pill' | 'tag' | 'box' | 'hex') {
  const { width, height } = NODE_SIZE[shape === 'tag' ? 'DESTINATION' : 'REMOTE'];
  return (
    <svg
      className={classes.legendNode}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
      focusable="false"
    >
      {shape === 'tag' || shape === 'hex' ? (
        <polygon className={classes.outlineShape} data-shape={shape} points={outline(shape, width, height)} />
      ) : (
        <rect
          className={classes.outlineShape}
          x={0.5}
          y={0.5}
          width={width - 1}
          height={height - 1}
          rx={shape === 'pill' ? height / 2 : 6}
        />
      )}
    </svg>
  );
}

/** A short run of edge, with the edge's own class and attributes, so its dash and colour are the canvas's. */
function line(attributes: Record<string, string>, width = MIN_WIDTH) {
  const double = attributes['data-kind'] === 'LINK' || attributes['data-kind'] === 'CLUSTER_HOP';
  const style = { '--edge-width': `${double ? width + 2 : width}px` } as CSSProperties;
  return (
    <svg className={classes.legendLine} viewBox="0 0 28 12" aria-hidden="true" focusable="false">
      <path d="M1 6H27" fill="none" className={classes.edge} {...attributes} style={style} />
      {double ? <path d="M1 6H27" fill="none" className={classes.edgeInner} style={style} /> : null}
    </svg>
  );
}

/** Every mark and line the canvas can draw, docked under it rather than floating over nodes. */
export function FlowLegend({ motion }: Readonly<{ motion: 'running' | 'paused' | 'off' }>) {
  return (
    <div className={classes.legend} role="group" aria-label="Legend">
      <LegendGroup title="Nodes">
        <LegendItem mark={node('pill')}>client</LegendItem>
        <LegendItem mark={node('tag')}>address</LegendItem>
        <LegendItem mark={node('box')}>queue, bar = backlog</LegendItem>
        <LegendItem mark={node('hex')}>other node or broker</LegendItem>
      </LegendGroup>
      <LegendGroup title="Throughput">
        {REFERENCE_RATES.map(({ rate, label }) => (
          <LegendItem key={rate} mark={line({ 'data-line': 'flowing' }, widthPx(rate))}>
            {label}
          </LegendItem>
        ))}
        <LegendItem>
          Line width and dot speed both follow throughput (square-root scale, capped at {RATE_CAP} msg/s).
        </LegendItem>
        <LegendItem>
          {motion === 'off'
            ? 'Motion is off (reduced motion); width and labels carry the rate.'
            : 'Dots move left to right; faster and denser means busier.'}
        </LegendItem>
      </LegendGroup>
      <LegendGroup title="Line kinds">
        <LegendItem mark={line({ 'data-kind': 'FORWARD' })}>divert (not counted by the broker)</LegendItem>
        <LegendItem mark={line({ 'data-kind': 'WILDCARD' })}>wildcard</LegendItem>
        <LegendItem mark={line({ 'data-kind': 'LINK' })}>bridge</LegendItem>
        <LegendItem mark={line({ 'data-kind': 'CLUSTER_HOP' })}>cluster redistribution</LegendItem>
        <LegendItem mark={line({ 'data-kind': 'DEAD_LETTER' })}>dead letter or expiry</LegendItem>
      </LegendGroup>
      <LegendGroup title="State">
        <LegendItem mark={line({ 'data-line': 'idle' })}>idle (thinnest, dashed)</LegendItem>
        <LegendItem mark={line({ 'data-line': 'unknown' })}>measuring (thinnest, dotted)</LegendItem>
        <LegendItem mark={line({ 'data-line': 'stale' })}>stale (rate out of date)</LegendItem>
        <LegendItem mark={line({ 'data-line': 'flowing', 'data-fault': 'true' })}>fault, named in words</LegendItem>
      </LegendGroup>
    </div>
  );
}
