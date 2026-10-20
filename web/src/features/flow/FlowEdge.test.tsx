import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { Position, ReactFlowProvider, type EdgeProps } from '@xyflow/react';

import { FlowDots, FlowEdge } from './FlowEdge.tsx';
import { edgeText } from './flowFormat.ts';

describe('flow edge', () => {
  it('states delivery, rate and faults in words', () => {
    expect(
      edgeText({
        kind: 'ROUTE',
        delivery: 'COPY',
        rate: 12.5,
        rateSource: 'SUBSCRIPTION_METRIC',
        stale: false,
        faults: [],
        bypassed: false,
        rahdar: false,
      }),
    ).toBe('copy · 12.5 msg/s');
    expect(
      edgeText({
        kind: 'CONSUME',
        rateSource: 'SAMPLER',
        stale: true,
        faults: ['STALLED'],
        bypassed: false,
        rahdar: false,
      }),
    ).toBe('measuring… · stalled · stale');
  });

  it('draws the dots it was given, spread along the path', () => {
    const { container } = render(
      <svg>
        <FlowDots path="M0,0 L100,0" seconds={3} count={3} />
      </svg>,
    );

    const motions = container.querySelectorAll('animateMotion');
    expect(motions).toHaveLength(3);
    expect([...motions].map((m) => m.getAttribute('begin'))).toEqual(['0s', '-1s', '-2s']);
    expect(motions[0].getAttribute('dur')).toBe('3s');
  });

  it('draws the dots at the radius it was given', () => {
    const { container } = render(
      <svg>
        <FlowDots path="M0,0 L100,0" seconds={3} count={2} radius={4} />
      </svg>,
    );

    expect([...container.querySelectorAll('circle')].map((c) => c.getAttribute('r'))).toEqual(['4', '4']);
  });

  function drawEdge(view: Record<string, unknown>) {
    const props = {
      id: 'e',
      source: 'a',
      target: 'b',
      sourceX: 0,
      sourceY: 0,
      targetX: 200,
      targetY: 0,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: { view: { rateSource: 'SUBSCRIPTION_METRIC', stale: false, faults: [], ...view }, dots: 0, dimmed: false },
    } as unknown as EdgeProps;
    const { container } = render(
      <ReactFlowProvider>
        <svg>
          <FlowEdge {...props} />
        </svg>
      </ReactFlowProvider>,
    );
    return [...container.querySelectorAll('path')];
  }

  it('sets the line weight from the rate, and the dash from its state', () => {
    const [busy] = drawEdge({ kind: 'ROUTE', rate: 1000 });
    expect(busy.style.getPropertyValue('--edge-width')).toBe('10px');
    expect(busy.getAttribute('data-line')).toBe('flowing');

    const [idle] = drawEdge({ kind: 'ROUTE', rate: 0 });
    expect(idle.style.getPropertyValue('--edge-width')).toBe('2px');
    expect(idle.getAttribute('data-line')).toBe('idle');

    const [unknown] = drawEdge({ kind: 'ROUTE' });
    expect(unknown.style.getPropertyValue('--edge-width')).toBe('2px');
    expect(unknown.getAttribute('data-line')).toBe('unknown');
  });

  it('keeps the double line of a bridge in proportion', () => {
    const [outer, inner] = drawEdge({ kind: 'LINK', rate: 1000 });
    expect(outer.style.getPropertyValue('--edge-width')).toBe('12px');
    expect(inner.style.getPropertyValue('--edge-width')).toBe('12px');
  });
});
