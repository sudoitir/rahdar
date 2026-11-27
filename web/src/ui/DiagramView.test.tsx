import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';

import { DiagramView } from './DiagramView.tsx';
import { layoutSignature, nodeName, type DiagramEdge, type DiagramNode } from './diagram.ts';

const nodes: DiagramNode[] = [
  { id: 'q', kind: 'Queue', label: 'orders' },
  { id: 'a', kind: 'Call a service', label: 'reserve', detail: 'inventory POST /reserve' },
  { id: 'b', kind: 'Call a service', label: 'charge', state: 'error', reason: 'Choose a connection.' },
  { id: 'u', kind: 'Undo', label: 'release' },
];
const edges: DiagramEdge[] = [
  { id: 'q-a', source: 'q', target: 'a' },
  { id: 'a-b', source: 'a', target: 'b' },
  { id: 'a-u', source: 'a', target: 'u', label: 'undo 1', dashed: true },
];

function draw(onSelect = vi.fn(), selectedId: string | null = null) {
  render(
    <MantineProvider>
      <DiagramView nodes={nodes} edges={edges} selectedId={selectedId} onSelect={onSelect} aria-label="Flow orders" />
    </MantineProvider>,
  );
  return onSelect;
}

function drawEditable({
  onInsert = vi.fn(),
  onNodeAction = vi.fn(),
}: {
  onInsert?: (e: string, v: string) => void;
  onNodeAction?: (n: string, a: string) => void;
}) {
  render(
    <MantineProvider>
      <DiagramView
        nodes={nodes}
        edges={edges.map((e) => (e.id === 'a-b' ? { ...e, insertable: true } : e))}
        aria-label="Flow orders"
        insertChoices={[
          { value: 'call', label: 'Call a service', group: 'Steps' },
          { value: 'send', label: 'Send a message', group: 'Steps' },
        ]}
        onInsert={onInsert}
        nodeActions={(n) =>
          n.kind === 'Queue'
            ? []
            : [
                {
                  id: 'up',
                  label: 'Move up',
                  disabledReason: n.id === 'a' ? 'It is already the first step.' : undefined,
                },
                { id: 'remove', label: 'Remove', danger: true },
              ]
        }
        onNodeAction={onNodeAction}
      />
    </MantineProvider>,
  );
}

describe('DiagramView', () => {
  it('names each box with its kind, label and problem in words', async () => {
    draw();
    expect(
      await screen.findByRole('button', { name: 'Call a service, charge. Invalid: Choose a connection.' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Invalid')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Flow orders' })).toBeInTheDocument();
    // An arrow's label reaches a screen reader through the box it points at.
    expect(screen.getByRole('button', { name: 'Undo, release. Via undo 1' })).toBeInTheDocument();
  });

  it('is one tab stop, moves with the arrow keys and selects with Enter', async () => {
    const onSelect = draw();
    const user = userEvent.setup();
    await screen.findByRole('button', { name: /^Queue, orders/ });
    await waitFor(() =>
      expect(
        screen.getAllByRole('button', { name: /^(Queue|Call|Undo)/ }).filter((b) => b.tabIndex === 0),
      ).toHaveLength(1),
    );
    await user.tab();
    expect(screen.getByRole('button', { name: /^Queue, orders/ })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('button', { name: /reserve/ })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith('a');
    expect(screen.getByText(/^Selected Call a service, reserve/)).toBeInTheDocument();
  });

  it("hands React Flow the scheme Mantine resolved, so its own controls follow the console's", async () => {
    const { container } = render(
      <MantineProvider forceColorScheme="light">
        <DiagramView nodes={nodes} edges={edges} aria-label="Flow orders" />
      </MantineProvider>,
    );
    await screen.findByRole('button', { name: /^Queue, orders/ });
    expect(container.querySelector('.react-flow')).toHaveClass('light');
  });

  it('marks the selected box as pressed', async () => {
    draw(vi.fn(), 'b');
    expect(await screen.findByRole('button', { name: /charge/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('keeps its layout when only words change', () => {
    const renamed = nodes.map((n) => ({ ...n, label: `${n.label}!`, state: undefined }));
    expect(layoutSignature(renamed, edges, 'DOWN')).toBe(layoutSignature(nodes, edges, 'DOWN'));
    expect(layoutSignature(nodes, edges.slice(1), 'DOWN')).not.toBe(layoutSignature(nodes, edges, 'DOWN'));
  });

  it('draws a weight as line thickness and does not let it move the layout', async () => {
    const weighted = edges.map((e) => (e.id === 'a-u' ? { ...e, weight: 12 } : e));
    expect(layoutSignature(nodes, weighted, 'DOWN')).toBe(layoutSignature(nodes, edges, 'DOWN'));
    const { container } = render(
      <MantineProvider>
        <DiagramView nodes={nodes} edges={weighted} aria-label="Flow orders" />
      </MantineProvider>,
    );
    await screen.findByRole('button', { name: /^Queue, orders/ });
    const thick = container.querySelector<SVGElement>('[style*="--edge-weight"]');
    expect(thick).not.toBeNull();
    expect(thick?.style.getPropertyValue('--edge-weight')).toBe('12');
  });

  it('shows no editing controls unless the caller offers them', async () => {
    draw();
    await screen.findByRole('button', { name: /^Queue, orders/ });
    expect(screen.queryByRole('button', { name: /^Insert between/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Zoom in' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zoom out' })).toBeInTheDocument();
  });

  it('offers the insert choices on an insertable arrow and reports the choice', async () => {
    const onInsert = vi.fn();
    drawEditable({ onInsert });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Insert between reserve and charge' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Send a message' }));
    expect(onInsert).toHaveBeenCalledWith('a-b', 'send');
    // Only the arrow marked insertable offers it.
    expect(screen.queryByRole('button', { name: 'Insert between orders and reserve' })).toBeNull();
  });

  it('offers what can go on the arrows into a box with Insert', async () => {
    const onInsert = vi.fn();
    drawEditable({ onInsert });
    const user = userEvent.setup();
    const charge = await screen.findByRole('button', { name: /^Call a service, charge/ });
    charge.focus();
    await user.keyboard('{Insert}');
    await user.click(await screen.findByRole('menuitem', { name: 'Call a service' }));
    expect(onInsert).toHaveBeenCalledWith('a-b', 'call');
  });

  it("opens a box's actions with Shift+F10, keeps a disabled one listed with its reason, and reports the choice", async () => {
    const onNodeAction = vi.fn();
    drawEditable({ onNodeAction });
    const user = userEvent.setup();
    const reserve = await screen.findByRole('button', { name: /^Call a service, reserve/ });
    reserve.focus();
    await user.keyboard('{Shift>}{F10}{/Shift}');
    const up = await screen.findByRole('menuitem', { name: /Move up/ });
    expect(up).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('It is already the first step.')).toBeInTheDocument();
    await user.click(up);
    expect(onNodeAction).not.toHaveBeenCalled();
    await waitFor(() => expect(reserve).toHaveFocus());
    await user.keyboard('{Shift>}{F10}{/Shift}');
    await user.click(await screen.findByRole('menuitem', { name: /Remove/ }));
    expect(onNodeAction).toHaveBeenCalledWith('a', 'remove');
    await waitFor(() => expect(reserve).toHaveFocus());
  });

  it('opens the actions of a box from its "⋯" and from a right-click', async () => {
    const onNodeAction = vi.fn();
    drawEditable({ onNodeAction });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Actions for charge' }));
    await user.click(await screen.findByRole('menuitem', { name: /Remove/ }));
    expect(onNodeAction).toHaveBeenCalledWith('b', 'remove');
    await user.pointer({
      keys: '[MouseRight]',
      target: screen.getByRole('button', { name: /^Call a service, reserve/ }),
    });
    await user.click(await screen.findByRole('menuitem', { name: /Remove/ }));
    expect(onNodeAction).toHaveBeenLastCalledWith('a', 'remove');
    // A box without actions has no menu.
    expect(screen.queryByRole('button', { name: 'Actions for orders' })).toBeNull();
  });

  it('reads a warning as a warning', () => {
    expect(nodeName({ id: 'x', label: 'x', state: 'warning', reason: 'Slow.' })).toBe('x. Warning: Slow.');
  });
});
