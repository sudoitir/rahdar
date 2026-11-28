import {
  createContext,
  Fragment,
  memo,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type MouseEvent,
} from 'react';
import {
  Background,
  BackgroundVariant,
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStoreApi,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import { ActionIcon, Loader, Menu, useComputedColorScheme } from '@mantine/core';
import { IconDots, IconPlus } from '@tabler/icons-react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { ActionMenuItem } from './ActionMenuItem.tsx';
import { AnchoredMenu } from './AnchoredMenu.tsx';
import { EmptyState } from './EmptyState.tsx';
import { runLayout } from './graph/elk.ts';
import { ViewControls } from './graph/ViewControls.tsx';
import {
  layoutSignature,
  nodeName,
  type DiagramAction,
  type DiagramChoice,
  type DiagramEdge,
  type DiagramNode,
} from './diagram.ts';
import classes from './DiagramView.module.css';
import { anchorBelow, clampToViewport, type MenuAnchor } from './table/menuAnchor.ts';

export type { DiagramAction, DiagramChoice, DiagramEdge, DiagramNode };

export interface DiagramViewProps {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  /** Which way the arrows run. Default: down. */
  direction?: 'DOWN' | 'RIGHT';
  /** The frame's height, as a CSS length such as '100%' to fill a sized parent. Default 30rem. */
  height?: string;
  /** Names the diagram for screen readers. */
  'aria-label': string;
  /** What can be inserted on an arrow marked `insertable`. With `onInsert`, each such arrow offers them. */
  insertChoices?: DiagramChoice[];
  /** A choice was made on an arrow. The diagram changes nothing itself. */
  onInsert?: (edgeId: string, value: string) => void;
  /** The actions a box offers in its menu. With `onNodeAction`, every box that has some offers them. */
  nodeActions?: (node: DiagramNode) => DiagramAction[];
  /** An action was chosen on a box. The diagram changes nothing itself. */
  onNodeAction?: (nodeId: string, actionId: string) => void;
}

const CARD = { width: 320, height: 68 };
const FIT = { padding: 0.12, maxZoom: 1 };

type Positions = Record<string, { x: number; y: number }>;

function toElk(nodes: DiagramNode[], edges: DiagramEdge[], direction: string): ElkNode {
  const ids = new Set(nodes.map((n) => n.id));
  return {
    id: 'diagram',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': direction,
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      // Wide enough that an edge chip sits clear of the boxes instead of under them.
      'elk.layered.spacing.nodeNodeBetweenLayers': '128',
      'elk.spacing.nodeNode': '32',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    },
    children: nodes.map((n) => ({ id: n.id, ...CARD })),
    edges: edges
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
}

function useLayout(nodes: DiagramNode[], edges: DiagramEdge[], direction: string) {
  const signature = layoutSignature(nodes, edges, direction);
  const latest = useRef({ nodes, edges });
  latest.current = { nodes, edges };
  const [state, setState] = useState<{ signature: string; positions: Positions }>({ signature: '', positions: {} });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    runLayout(toElk(latest.current.nodes, latest.current.edges, direction))
      .then((laidOut) => {
        if (cancelled) return;
        const positions: Positions = {};
        for (const c of laidOut.children ?? []) positions[c.id] = { x: c.x ?? 0, y: c.y ?? 0 };
        setState({ signature, positions });
        setError(null);
      })
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
    // The signature holds everything the layout depends on, the direction included.
  }, [signature, direction]);
  return { positions: state.positions, ready: state.signature === signature, error, signature };
}

/** A menu the diagram has open: a box's actions, or what can be inserted on the arrows into a box or on one arrow. */
type OpenMenu =
  | { kind: 'actions'; nodeId: string; anchor: MenuAnchor; opener: HTMLElement }
  | { kind: 'insert'; edgeIds: string[]; anchor: MenuAnchor; opener: HTMLElement };

interface Roving {
  tabStop: string | null;
  register: (id: string, el: HTMLButtonElement | null) => void;
  /** A box took focus; `fromKeyboard` is false for a pointer press, which needs no reveal. */
  focus: (id: string, fromKeyboard: boolean) => void;
  select: (id: string) => void;
  /** Opens a box's actions, or null when the diagram offers none. */
  openActions: ((id: string, anchor: MenuAnchor, opener: HTMLElement) => void) | null;
  /** Opens the insert choices of these arrows, or null when the diagram offers none. */
  openInsert: ((edgeIds: string[], anchor: MenuAnchor, opener: HTMLElement) => void) | null;
}
const RovingContext = createContext<Roving>({
  tabStop: null,
  register: () => {},
  focus: () => {},
  select: () => {},
  openActions: null,
  openInsert: null,
});

type CardData = {
  node: DiagramNode;
  name: string;
  selected: boolean;
  vertical: boolean;
  /** Whether the box has a menu of actions. */
  actions: boolean;
  /** The insertable arrows into the box, which Insert offers from the keyboard. */
  insertInto: string[];
};

type OpenActions = NonNullable<Roving['openActions']>;
type OpenInsert = NonNullable<Roving['openInsert']>;

const STATE_WORD = { error: 'Invalid', warning: 'Warning' } as const;

/** The small heading of a box: its kind, and the state it is in, said in words. */
function NodeKind({ node }: Readonly<{ node: DiagramNode }>) {
  if (!node.kind && !node.state) return null;
  return (
    <span className={classes.kind}>
      {node.kind}
      {node.kind && node.state ? ' ' : null}
      {node.state ? <span className={classes.state}>{STATE_WORD[node.state]}</span> : null}
    </span>
  );
}

function isActionsKey(event: KeyboardEvent): boolean {
  return event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey);
}

function isInsertKey(event: KeyboardEvent): boolean {
  return event.key === 'Insert' || event.key === '+';
}

/** The box itself: the diagram's one tab stop when it is the current one, and where its menus open. */
function CardButton({
  id,
  data,
  actions,
  insert,
}: Readonly<{ id: string; data: CardData; actions: OpenActions | null; insert: OpenInsert | null }>) {
  const { tabStop, register, focus, select } = useContext(RovingContext);
  // Shift+F10 and the menu key are followed by the browser's own contextmenu event, which would
  // reopen the menu at the pointer and close the one the keyboard opened.
  const suppressContextMenuUntil = useRef(0);
  const keys = [actions ? 'Shift+F10' : null, insert ? 'Insert' : null].filter(Boolean).join(' ');
  const onContextMenu = (event: MouseEvent<HTMLButtonElement>) => {
    if (!actions) return;
    event.preventDefault();
    if (performance.now() < suppressContextMenuUntil.current) return;
    actions(id, clampToViewport({ x: event.clientX, y: event.clientY }), event.currentTarget);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (actions && isActionsKey(event)) {
      event.preventDefault();
      suppressContextMenuUntil.current = performance.now() + 500;
      actions(id, anchorBelow(event.currentTarget), event.currentTarget);
    } else if (insert && isInsertKey(event)) {
      event.preventDefault();
      insert(data.insertInto, anchorBelow(event.currentTarget), event.currentTarget);
    }
  };
  const n = data.node;
  return (
    <button
      ref={(el) => register(id, el)}
      type="button"
      className={classes.node}
      data-state={n.state}
      data-selected={data.selected || undefined}
      tabIndex={tabStop === id ? 0 : -1}
      aria-label={data.name}
      aria-pressed={data.selected}
      aria-keyshortcuts={keys || undefined}
      title={data.name}
      onFocus={(event) => focus(id, event.currentTarget.matches(':focus-visible'))}
      onClick={() => select(id)}
      onContextMenu={onContextMenu}
      onKeyDown={onKeyDown}
    >
      <NodeKind node={n} /> <span className={classes.label}>{n.label}</span>
      {n.detail ? (
        <>
          {' '}
          <span className={classes.detail}>{n.detail}</span>
        </>
      ) : null}
    </button>
  );
}

const Card = memo(function Card({ id, data }: NodeProps<Node<CardData>>) {
  const { openActions, openInsert } = useContext(RovingContext);
  const actions = data.actions && openActions ? openActions : null;
  const insert = data.insertInto.length && openInsert ? openInsert : null;
  return (
    <div className={classes.card}>
      <Handle
        type="target"
        position={data.vertical ? Position.Top : Position.Left}
        className={classes.handle}
        isConnectable={false}
      />
      <CardButton id={id} data={data} actions={actions} insert={insert} />
      {actions ? (
        // The keyboard opens the same menu with Shift+F10 on the box, so this stays out of the tab order.
        <ActionIcon
          className={`${classes.more} nodrag nopan`}
          variant="subtle"
          size="sm"
          tabIndex={-1}
          aria-label={`Actions for ${data.node.label}`}
          aria-haspopup="menu"
          data-selected={data.selected || undefined}
          onClick={(event) => {
            event.stopPropagation();
            actions(id, anchorBelow(event.currentTarget), event.currentTarget);
          }}
        >
          <IconDots size="0.875rem" stroke={1.75} />
        </ActionIcon>
      ) : null}
      <Handle
        type="source"
        position={data.vertical ? Position.Bottom : Position.Right}
        className={classes.handle}
        isConnectable={false}
      />
    </div>
  );
});

type LineData = { dashed?: boolean; insert?: string; weight?: number };

/**
 * A line with its label as a chip. That label, or its longer spoken form, is in the target's
 * accessible name, so the chip is hidden from it. An insertable line carries a "+" beside the chip,
 * named after the boxes it joins.
 */
const Line = memo(function Line({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  label,
  data,
}: EdgeProps) {
  const { openInsert } = useContext(RovingContext);
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    borderRadius: 8,
  });
  const d = (data ?? {}) as LineData;
  const insert = d.insert && openInsert ? openInsert : null;
  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        className={d.dashed ? classes.dashed : classes.edge}
        markerEnd={markerEnd}
        interactionWidth={0}
        style={d.weight == null ? undefined : ({ '--edge-weight': d.weight } as CSSProperties)}
      />
      {label || insert ? (
        <EdgeLabelRenderer>
          <div
            className={`${classes.labels} nodrag nopan`}
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          >
            {label ? (
              <span className={classes.chip} aria-hidden="true">
                {label}
              </span>
            ) : null}
            {insert ? (
              // The keyboard reaches the same choices with Insert on the box this line leads to.
              <ActionIcon
                className={classes.insert}
                variant="default"
                radius="xl"
                size="sm"
                tabIndex={-1}
                aria-label={d.insert}
                aria-haspopup="menu"
                onClick={(event) => insert([id], anchorBelow(event.currentTarget), event.currentTarget)}
              >
                <IconPlus size="0.75rem" stroke={2} />
              </ActionIcon>
            ) : null}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
});

const nodeTypes = { card: Card };
const edgeTypes = { line: Line };

/**
 * Brings a box focused from the keyboard into view, at the operator's zoom and with no animation, when
 * any of it is outside the frame: arrow keys otherwise move focus to a box no one can see.
 */
function RevealFocused({ request }: Readonly<{ request: { id: string; count: number } | null }>) {
  const flow = useReactFlow();
  const store = useStoreApi();
  useEffect(() => {
    if (!request) return;
    const node = flow.getInternalNode(request.id);
    if (!node) return;
    const { width, height, transform } = store.getState();
    const [tx, ty, zoom] = transform;
    const { x, y } = node.internals.positionAbsolute;
    const left = x * zoom + tx;
    const top = y * zoom + ty;
    if (left >= 0 && top >= 0 && left + CARD.width * zoom <= width && top + CARD.height * zoom <= height) return;
    void flow.setCenter(x + CARD.width / 2, y + CARD.height / 2, { zoom, duration: 0 });
  }, [flow, store, request]);
  return null;
}

/**
 * Fits the view when the boxes or arrows change, and when the frame changes size: a diagram in a
 * panel that is resized, or shown again after being collapsed, would otherwise keep a view of where
 * its boxes were, or were laid out at no size at all.
 */
function FitOnLayout({
  signature,
  frame,
}: {
  signature: string | null;
  frame: React.RefObject<HTMLDivElement | null>;
}) {
  const flow = useReactFlow();
  useEffect(() => {
    if (!signature) return;
    const raf = requestAnimationFrame(() => void flow.fitView(FIT));
    return () => cancelAnimationFrame(raf);
  }, [flow, signature]);
  useEffect(() => {
    const el = frame.current;
    if (!signature || !el || typeof ResizeObserver === 'undefined') return;
    let last = { width: el.clientWidth, height: el.clientHeight };
    let raf = 0;
    const observer = new ResizeObserver(() => {
      const size = { width: el.clientWidth, height: el.clientHeight };
      if (!size.width || !size.height || (size.width === last.width && size.height === last.height)) return;
      last = size;
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => void flow.fitView({ ...FIT, duration: 0 }));
    });
    observer.observe(el);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(raf);
    };
  }, [flow, signature, frame]);
  return null;
}

/**
 * A diagram of boxes and arrows, laid out automatically (ELK layered, in a worker) and drawn in
 * Rahdar's theme (ADR-0117). Nothing is dragged, connected or deleted: it shows a structure and
 * reports which box was chosen. A caller may offer things to insert on arrows and actions on boxes
 * (ADR-0120); the diagram reports the choice and the caller changes the nodes.
 *
 * <p>The diagram is one tab stop. Arrow keys move between boxes in reading order, Enter or Space
 * selects one, and the selection is announced. A box with a problem says so in words, on the box
 * and in its accessible name, so colour is never the only signal. Positions change only when the
 * boxes or arrows do, never when a label does. A box's actions open from its "⋯", a right-click,
 * or Shift+F10; Insert on a box offers what can go on the arrows into it.
 */
export function DiagramView({
  nodes,
  edges,
  selectedId = null,
  onSelect,
  direction = 'DOWN',
  height = '30rem',
  'aria-label': ariaLabel,
  insertChoices,
  onInsert,
  nodeActions,
  onNodeAction,
}: Readonly<DiagramViewProps>) {
  const layout = useLayout(nodes, edges, direction);
  // React Flow's own chrome (controls, minimap, attribution) follows the scheme Mantine resolved.
  const colorMode = useComputedColorScheme('dark', { getInitialValueInEffect: false });
  const vertical = direction === 'DOWN';
  const order = useMemo(
    () =>
      [...nodes]
        .filter((n) => layout.positions[n.id])
        .sort((a, b) => {
          const pa = layout.positions[a.id];
          const pb = layout.positions[b.id];
          return vertical ? pa.y - pb.y || pa.x - pb.x : pa.x - pb.x || pa.y - pb.y;
        })
        .map((n) => n.id),
    [nodes, layout.positions, vertical],
  );
  const [focused, setFocused] = useState<string | null>(null);
  const [reveal, setReveal] = useState<{ id: string; count: number } | null>(null);
  const tabStop = pickTabStop(order, focused, selectedId);
  const elements = useRef(new Map<string, HTMLButtonElement>());
  const frameRef = useRef<HTMLDivElement>(null);
  const [announce, setAnnounce] = useState('');
  const [menu, setMenu] = useState<OpenMenu | null>(null);
  const inserting = !!(onInsert && insertChoices?.length);
  const acting = !!(onNodeAction && nodeActions);
  const actionsOf = useMemo(() => {
    const byId = new Map<string, DiagramAction[]>();
    if (acting) for (const n of nodes) byId.set(n.id, nodeActions(n));
    return byId;
  }, [acting, nodes, nodeActions]);

  const roving = useMemo<Roving>(
    () => ({
      tabStop,
      register: (id, el) => {
        if (el) elements.current.set(id, el);
        else elements.current.delete(id);
      },
      focus: (id, fromKeyboard) => {
        setFocused(id);
        if (fromKeyboard) setReveal((last) => ({ id, count: (last?.count ?? 0) + 1 }));
      },
      select: (id) => {
        const n = nodes.find((x) => x.id === id);
        if (n) setAnnounce(`Selected ${nodeName(n)}`);
        onSelect?.(id);
      },
      openActions: acting ? (nodeId, anchor, opener) => setMenu({ kind: 'actions', nodeId, anchor, opener }) : null,
      openInsert: inserting ? (edgeIds, anchor, opener) => setMenu({ kind: 'insert', edgeIds, anchor, opener }) : null,
    }),
    [tabStop, nodes, onSelect, acting, inserting],
  );

  const onKeyDown = (e: React.KeyboardEvent) => {
    const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
    if (!step || !tabStop) return;
    e.preventDefault();
    const next = order[Math.min(order.length - 1, Math.max(0, order.indexOf(tabStop) + step))];
    setFocused(next);
    elements.current.get(next)?.focus({ preventScroll: true });
  };

  const model = useMemo(() => {
    const rfNodes: Node<CardData>[] = nodes
      .filter((n) => layout.positions[n.id])
      .map((n) => ({
        id: n.id,
        type: 'card',
        position: layout.positions[n.id],
        data: {
          node: n,
          name: nodeName(
            n,
            edges.filter((e) => e.target === n.id && (e.spoken ?? e.label)).map((e) => (e.spoken ?? e.label) as string),
          ),
          selected: n.id === selectedId,
          vertical,
          actions: (actionsOf.get(n.id)?.length ?? 0) > 0,
          insertInto: inserting ? edges.filter((e) => e.insertable && e.target === n.id).map((e) => e.id) : [],
        },
        ...CARD,
      }));
    const rfEdges: Edge[] = edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      type: 'line',
      label: e.label,
      data: {
        dashed: e.dashed,
        weight: e.weight,
        insert: inserting && e.insertable ? insertName(e, nodes) : undefined,
      },
      markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
      focusable: false,
    }));
    return { nodes: rfNodes, edges: rfEdges };
  }, [nodes, edges, layout.positions, selectedId, vertical, actionsOf, inserting]);

  const closeMenu = () => {
    const opener = menu?.opener;
    setMenu(null);
    if (opener?.isConnected) opener.focus();
  };
  const menuNode = menu?.kind === 'actions' ? nodes.find((n) => n.id === menu.nodeId) : undefined;
  const menuEdges = menu?.kind === 'insert' ? edges.filter((e) => menu.edgeIds.includes(e.id)) : [];

  if (layout.error) {
    return (
      <EmptyState
        kind="empty"
        title="The diagram could not be laid out"
        description={`${layout.error.replace(/\.$/, '')}. Reload the page to try again.`}
      />
    );
  }

  return (
    <ReactFlowProvider>
      <div
        ref={frameRef}
        className={classes.frame}
        style={{ blockSize: height }}
        role="group"
        aria-label={ariaLabel}
        onKeyDown={onKeyDown}
      >
        {!layout.ready ? (
          <output className={classes.overlay} aria-busy="true" aria-label="Laying out the diagram">
            <Loader size="sm" />
          </output>
        ) : null}
        <RovingContext.Provider value={roving}>
          <ReactFlow
            nodes={model.nodes}
            edges={model.edges}
            colorMode={colorMode}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            minZoom={0.2}
            maxZoom={1.6}
            nodesDraggable={false}
            nodesConnectable={false}
            nodesFocusable={false}
            edgesFocusable={false}
            elementsSelectable={false}
            onNodeClick={(_, node) => roving.select(node.id)}
            // A menu is anchored to a point on screen; panning moves the box away from it. Only the
            // user's own pans count: fitting after a layout has no event, and must not close a menu.
            onMoveStart={(event) => {
              if (event) setMenu(null);
            }}
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} patternClassName={classes.dots} />
            <FitOnLayout signature={layout.ready ? layout.signature : null} frame={frameRef} />
            <RevealFocused request={reveal} />
          </ReactFlow>
        </RovingContext.Provider>
        <ViewControls fit={FIT} />
        <span className={classes.live} aria-live="polite">
          {announce}
        </span>
      </div>
      <AnchoredMenu
        opened={menu !== null}
        anchor={menu?.anchor ?? null}
        label={menuLabel(menuNode, menuEdges, nodes)}
        onClose={closeMenu}
      >
        {menuNode
          ? (actionsOf.get(menuNode.id) ?? []).map((a) => (
              <ActionMenuItem
                key={a.id}
                label={a.label}
                tone={a.danger ? 'danger' : undefined}
                verdict={a.disabledReason ? { kind: 'blocked', reason: a.disabledReason } : undefined}
                onSelect={() => {
                  closeMenu();
                  onNodeAction?.(menuNode.id, a.id);
                }}
              />
            ))
          : menuEdges.map((e) => (
              <Fragment key={e.id}>
                {menuEdges.length > 1 ? <Menu.Label>{insertName(e, nodes)}</Menu.Label> : null}
                {(insertChoices ?? []).map((c, i, all) => (
                  <Fragment key={c.value}>
                    {c.group && c.group !== all[i - 1]?.group ? <Menu.Label>{c.group}</Menu.Label> : null}
                    <Menu.Item
                      onClick={() => {
                        closeMenu();
                        onInsert?.(e.id, c.value);
                      }}
                    >
                      {c.label}
                    </Menu.Item>
                  </Fragment>
                ))}
              </Fragment>
            ))}
      </AnchoredMenu>
    </ReactFlowProvider>
  );
}

/** The box that takes the tab stop: the focused one, else the selected one, else the first. */
function pickTabStop(order: string[], focused: string | null, selectedId: string | null): string | null {
  if (focused && order.includes(focused)) return focused;
  if (selectedId && order.includes(selectedId)) return selectedId;
  return order[0] ?? null;
}

/** The name of the open menu: the box whose actions it lists, the one arrow it inserts on, or just "Insert". */
function menuLabel(menuNode: DiagramNode | undefined, menuEdges: DiagramEdge[], nodes: DiagramNode[]): string {
  if (menuNode) return `Actions for ${menuNode.label}`;
  return menuEdges.length === 1 ? insertName(menuEdges[0], nodes) : 'Insert';
}

/** "Insert between reserve and charge": what an insert control does, in the boxes' own words. */
function insertName(e: DiagramEdge, nodes: DiagramNode[]): string {
  const label = (id: string) => nodes.find((n) => n.id === id)?.label ?? id;
  return `Insert between ${label(e.source)} and ${label(e.target)}`;
}
