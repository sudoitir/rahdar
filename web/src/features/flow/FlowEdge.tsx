import { memo, useContext, type CSSProperties } from 'react';
import { EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';

import { FlowCanvasContext } from './canvasContext.ts';
import { crossingSeconds, lineState, widthPx } from './edgeEncoding.ts';
import { edgeText } from './flowFormat.ts';
import type { FlowEdgeData } from './flowLayout.ts';
import classes from './FlowCanvas.module.css';

/**
 * Dots travelling the edge. Memoised on primitives only, so a refresh that leaves the rounded
 * crossing time, count and radius alone re-renders nothing here and the running SMIL animation is
 * never restarted.
 */
export const FlowDots = memo(function FlowDots({
  path,
  seconds,
  count,
  radius = 2.6,
}: {
  path: string;
  seconds: number;
  count: number;
  radius?: number;
}) {
  return (
    <g aria-hidden="true">
      {Array.from({ length: count }, (_, i) => (
        <circle key={i} r={radius} className={classes.dot}>
          <animateMotion
            dur={`${seconds}s`}
            repeatCount="indefinite"
            // Negative begin spreads the dots along the path instead of stacking them at its start.
            begin={`${(-seconds * i) / count}s`}
            path={path}
          />
        </circle>
      ))}
    </g>
  );
});

export const FlowEdge = memo(function FlowEdge({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps) {
  const d = data as FlowEdgeData;
  const view = d.view;
  const { showText, motion } = useContext(FlowCanvasContext);
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  const fault = (view.faults?.length ?? 0) > 0;
  const width = widthPx(view.rate);
  const double = view.kind === 'LINK' || view.kind === 'CLUSTER_HOP';
  // The double line of a link or hop keeps its proportions: the canvas-coloured inner stroke is a
  // fixed fraction of the outer one (FlowCanvas.module.css), two px wider so the gap stays visible.
  const lineStyle = { '--edge-width': `${double ? width + 2 : width}px` } as CSSProperties;

  return (
    <>
      <path
        d={path}
        fill="none"
        className={classes.edge}
        data-line={lineState(view.rate, view.stale)}
        data-kind={view.kind}
        data-fault={fault || undefined}
        data-dimmed={d.dimmed || undefined}
        style={lineStyle}
      />
      {double ? (
        // A second, narrower stroke in the canvas colour turns one line into two: forwarding
        // between brokers reads differently from routing inside one.
        <path
          d={path}
          fill="none"
          className={classes.edgeInner}
          data-dimmed={d.dimmed || undefined}
          style={lineStyle}
        />
      ) : null}
      {showText && motion !== 'off' && d.dots > 0 && !d.dimmed ? (
        <FlowDots
          path={path}
          seconds={crossingSeconds(view.rate)}
          count={d.dots}
          radius={Math.round(Math.max(2.6, width * 0.4) * 10) / 10}
        />
      ) : null}
      {showText ? (
        <EdgeLabelRenderer>
          <div
            className={`${classes.edgeLabel} nopan nodrag`}
            data-fault={fault || undefined}
            data-dimmed={d.dimmed || undefined}
            style={{ '--label-x': `${labelX}px`, '--label-y': `${labelY}px` } as CSSProperties}
          >
            {edgeText(view)}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
});
