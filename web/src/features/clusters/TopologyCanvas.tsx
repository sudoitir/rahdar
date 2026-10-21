import {
  createContext,
  useCallback,
  useContext,
  useId,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FocusEvent,
  type KeyboardEvent,
} from 'react';
import {
  Background,
  BackgroundVariant,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useNodesInitialized,
  useReactFlow,
  useStoreApi,
  type NodeProps,
} from '@xyflow/react';
import { Button, Text, VisuallyHidden, useComputedColorScheme, useMantineTheme } from '@mantine/core';
import { useReducedMotion } from '@mantine/hooks';

import { Notice } from '../../ui/Notice.tsx';
import { ViewControls } from '../../ui/graph/ViewControls.tsx';
import { useSlot } from '../../kernel/slots.ts';
import {
  AXIS_Y,
  DENSE_COLUMNS,
  DENSE_THRESHOLD,
  EDGE_MARKS,
  NODE_H,
  NODE_MARKS,
  NODE_W,
  isBrokerNode,
  type BrokerNodeData,
  type PairGroupData,
  type TopologyLayout,
} from './layout.ts';
import styles from './TopologyCanvas.module.css';

/** How the canvas fits itself, and what its fit button does: never magnified past natural size. */
const FIT = { padding: 0.15, maxZoom: 1 };

/** The shape, in px, the layout reserved for a box; the style sheet reads it, so the two cannot drift apart. */
const GEOMETRY = { '--topology-node-w': `${NODE_W}px`, '--topology-node-h': `${NODE_H}px` } as CSSProperties;

/** What a box needs from the canvas that holds it. */
interface CanvasValue {
  /** The live cluster the canvas draws; absent on a preview, which shows no features' marks. */
  clusterId?: string;
  /** Whether the boxes are controls. A preview draws the same boxes as plain, inert ones. */
  interactive: boolean;
  /** The box that is the canvas's one tab stop. */
  tabStop: string | null;
  /** The box that stands for the chosen node. */
  selectedBox: string | null;
  register: (id: string, element: HTMLButtonElement | null) => void;
  /** A box took focus; `fromKeyboard` is false for a pointer press, which leaves the view where it is. */
  focus: (id: string, fromKeyboard: boolean) => void;
  select: (id: string) => void;
}

const CanvasContext = createContext<CanvasValue>({
  interactive: false,
  tabStop: null,
  selectedBox: null,
  register: () => {},
  focus: () => {},
  select: () => {},
});

function PairGroup({ data }: NodeProps) {
  const d = data as PairGroupData;
  return (
    <div className={styles.group} data-status={d.axisStatus}>
      <span className={styles.groupId}>id {d.shortId}</span>
      <div className={styles.groupAxis} style={{ insetBlockStart: AXIS_Y }} aria-hidden="true">
        <span className={styles.axisNote}>{d.axisNote}</span>
      </div>
    </div>
  );
}

/** Whether focus arrived by keyboard: a pointer press focuses the box too, but the box is already in view. */
function isKeyboardFocus(event: FocusEvent<HTMLElement>): boolean {
  try {
    return event.currentTarget.matches(':focus-visible');
  } catch {
    return true;
  }
}

/** What the enabled features mark on a box of a live cluster (`topology.node.marks`), such as a firing alert. */
function NodeMarks({ nodeIds }: Readonly<{ nodeIds: string[] }>) {
  const { clusterId } = useContext(CanvasContext);
  const marks = useSlot('topology.node.marks');
  if (!clusterId) return null;
  return (
    <>
      {marks.map(({ id, Component }) => (
        <Component key={id} clusterId={clusterId} nodeIds={nodeIds} />
      ))}
    </>
  );
}

/** The four lines of a box: its name and version, its liveness, its role and pair, its address or error. */
function BoxLines({ d }: Readonly<{ d: BrokerNodeData }>) {
  return (
    <>
      <span className={styles.head}>
        <span className={styles.name}>{d.name}</span>
        <NodeMarks nodeIds={d.nodeIds} />
        {d.version ? (
          <span className={styles.version}>
            {d.version}
            {d.versionFlag ? <span className={styles.flag}> · {d.versionFlag}</span> : null}
          </span>
        ) : null}
      </span>
      <span className={styles.line}>
        <span className={styles.mark} data-kind={d.kind} aria-hidden="true" />
        <span className={styles.liveness} data-kind={d.kind}>
          {d.liveness}
        </span>
      </span>
      <span className={styles.line}>{d.roleLine}</span>
      <span className={styles.detail} data-error={d.detailIsError || undefined}>
        {d.detail}
      </span>
    </>
  );
}

function BrokerNode({ id, data }: NodeProps) {
  const d = data as BrokerNodeData;
  const { interactive, tabStop, selectedBox, register, focus } = useContext(CanvasContext);
  const selected = selectedBox === id;
  return (
    <>
      <Handle type="target" position={Position.Top} className={styles.handle} isConnectable={false} />
      {interactive ? (
        <button
          ref={(el) => register(id, el)}
          type="button"
          className={styles.node}
          data-kind={d.kind}
          data-selected={selected || undefined}
          tabIndex={tabStop === id ? 0 : -1}
          aria-pressed={selected}
          title={d.sentence}
          onFocus={(event) => focus(id, isKeyboardFocus(event))}
        >
          <BoxLines d={d} />
        </button>
      ) : (
        <div className={styles.node} data-kind={d.kind}>
          <BoxLines d={d} />
        </div>
      )}
      <Handle type="source" position={Position.Bottom} className={styles.handle} isConnectable={false} />
    </>
  );
}

const nodeTypes = { broker: BrokerNode, pair: PairGroup };

/** Each box's place in the pane, as `[id, "x,y"]` entries in a string, so an unchanged layout compares equal. */
function positionsOf(nodes: TopologyLayout['nodes']): string {
  const groups = new Map(nodes.filter((n) => !isBrokerNode(n)).map((n) => [n.id, n.position]));
  return JSON.stringify(
    nodes.filter(isBrokerNode).map((n) => {
      const parent = n.parentId ? groups.get(n.parentId) : undefined;
      return [n.id, `${n.position.x + (parent?.x ?? 0)},${n.position.y + (parent?.y ?? 0)}`];
    }),
  );
}

function Legend() {
  return (
    <div className={styles.legend}>
      {NODE_MARKS.map((m) => (
        <span key={m.kind} className={styles.legendItem}>
          <span className={styles.mark} data-kind={m.kind} aria-hidden="true" />
          {m.label}
        </span>
      ))}
      {EDGE_MARKS.map((e) => (
        <span key={e.kind} className={styles.legendItem}>
          <span className={styles.legendEdge} data-kind={e.kind} aria-hidden="true" />
          {e.label}
        </span>
      ))}
      <span className={styles.legendItem}>
        <span className={styles.legendAxis} aria-hidden="true" />
        {/* The flex gap spaces the mark from its label. */}
        shared NodeID — serving above, standby below
      </span>
      <span className={styles.legendItem}>
        <span className={styles.legendBox} data-offset aria-hidden="true" />
        box set off to the side: replication behind
      </span>
      <span className={styles.legendItem}>
        <span className={styles.legendBox} data-kind="unmanaged" aria-hidden="true" />
        dashed box: no management URL
      </span>
    </div>
  );
}

function EmptyCanvas({ height }: Readonly<{ height?: string }>) {
  return (
    <div className={styles.wrapper} style={height ? { blockSize: height } : undefined}>
      <div className={styles.empty}>
        <Text fw={600}>No nodes yet</Text>
        <Text size="sm" c="dimmed">
          Rahdar learns the topology from the first broker it reaches. Nothing has answered on this cluster's seed
          address yet; Rahdar keeps looking and shows the nodes here as they answer.
        </Text>
      </div>
    </div>
  );
}

/** The box that holds the chosen node: the one whose endpoints include it. */
function boxHolding(model: TopologyLayout, nodeId: string | null | undefined): string | null {
  if (!nodeId) return null;
  return model.nodes.find((n) => isBrokerNode(n) && n.data.nodeIds.includes(nodeId))?.id ?? null;
}

/** Where `id` sits among the keyboard columns, or null when it is not in one. */
function locate(columns: string[][], id: string): { column: number; row: number } | null {
  for (const [column, ids] of columns.entries()) {
    const row = ids.indexOf(id);
    if (row >= 0) return { column, row };
  }
  return null;
}

/** The box a key moves to from `id`: across columns with ←/→, within a column with ↑/↓, the ends with Home/End. */
function moveTo(columns: string[][], key: string, id: string): string | null {
  const order = columns.flat();
  if (key === 'Home') return order[0] ?? null;
  if (key === 'End') return order.at(-1) ?? null;
  const at = locate(columns, id);
  if (!at) return null;
  const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[key];
  if (!step) return null;
  const column = Math.min(columns.length - 1, Math.max(0, at.column + step[0]));
  const ids = columns[column];
  const row = Math.min(ids.length - 1, Math.max(0, step[0] === 0 ? at.row + step[1] : at.row));
  return ids[row] ?? null;
}

/** Whether a box present in both position signatures (see {@link positionsOf}) is at a different place in the second. */
function anyBoxMoved(beforeSignature: string, afterSignature: string): boolean {
  const before = new Map<string, string>(JSON.parse(beforeSignature) as [string, string][]);
  const after = JSON.parse(afterSignature) as [string, string][];
  return after.some(([id, at]) => before.has(id) && before.get(id) !== at);
}

/**
 * The boxes and arrows, in React Flow's pane, with the canvas's own keyboard model. It is one tab stop:
 * arrow keys move between the boxes in their columns, Home and End go to the first and the last, Enter and
 * Space choose the focused box and announce it, and Escape clears the choice and keeps focus where it is.
 * A box focused from the keyboard is kept in view with `setCenter` and no animation; a click on the pane
 * clears the choice.
 */
function Flow({
  model,
  interactive,
  clusterId,
  height,
  selectedId,
  onSelect,
  onShowTable,
}: Readonly<{
  model: TopologyLayout;
  interactive: boolean;
  clusterId?: string;
  height?: string;
  selectedId?: string | null;
  onSelect?: (nodeId: string | null) => void;
  onShowTable?: () => void;
}>) {
  const flow = useReactFlow();
  const store = useStoreApi();
  // React Flow's own chrome follows the scheme Mantine resolved.
  const colorMode = useComputedColorScheme('dark', { getInitialValueInEffect: false });
  const proOptions = useMemo(() => ({ hideAttribution: true }), []);
  // The pairs drawn, and in the reduced-detail grid the boxes, since it has no pairs.
  const signature = useMemo(
    () =>
      model.nodes
        .filter((n) => !isBrokerNode(n) || n.id.startsWith('collapsed:'))
        .map((n) => n.id)
        .join('|'),
    [model.nodes],
  );
  const positions = useMemo(() => positionsOf(model.nodes), [model.nodes]);
  const order = useMemo(() => model.columns.flat(), [model.columns]);
  const selectedBox = boxHolding(model, selectedId);
  const [focused, setFocused] = useState<string | null>(null);
  const [announce, setAnnounce] = useState('');
  const keysId = useId();
  const elements = useRef(new Map<string, HTMLButtonElement>());
  const wrapper = useRef<HTMLDivElement>(null);
  // Whether the operator has moved the view since the canvas last fitted itself, which a refit would undo.
  const operatorMoved = useRef(false);
  const measured = useNodesInitialized();
  const [settled, setSettled] = useState(false);
  const reducedMotion = useReducedMotion();
  const { motion } = useMantineTheme().other;
  const tabStop = [focused, selectedBox].find((id) => id && order.includes(id)) ?? order[0] ?? null;

  /** Brings a box fully into view, at the zoom the operator has, only when it is not already. */
  const keepInView = useCallback(
    (id: string) => {
      const node = flow.getInternalNode(id);
      if (!node) return;
      const { x, y } = node.internals.positionAbsolute;
      const { width, height: frame, transform } = store.getState();
      const [tx, ty, zoom] = transform;
      const left = x * zoom + tx;
      const top = y * zoom + ty;
      if (left >= 0 && top >= 0 && left + NODE_W * zoom <= width && top + NODE_H * zoom <= frame) return;
      operatorMoved.current = true;
      // setCenter would otherwise zoom to the instance's maximum.
      void flow.setCenter(x + NODE_W / 2, y + NODE_H / 2, { zoom, duration: 0 });
    },
    [flow, store],
  );

  // The boxes glide only once the first layout has been measured and painted, so they do not slide in
  // from wherever React Flow put them before it knew their size.
  useEffect(() => {
    if (!measured || settled) return;
    const frame = requestAnimationFrame(() => setSettled(true));
    return () => cancelAnimationFrame(frame);
  }, [measured, settled]);

  // Re-fit when the set of logical nodes changes, so a failover leaves no stale viewport, unless the
  // operator has moved the view since. Only once React Flow has measured the boxes: fitting unmeasured
  // ones drew a frame at the wrong zoom, with the boxes over one another.
  useEffect(() => {
    if (measured && !operatorMoved.current) void flow.fitView(FIT);
  }, [flow, signature, measured]);

  // Edges are drawn at a box's new place at once while the box glides there, so they are hidden for the
  // glide and fade back when the boxes arrive.
  const previousPositions = useRef(positions);
  useLayoutEffect(() => {
    const before = previousPositions.current;
    previousPositions.current = positions;
    const frame = wrapper.current;
    if (!frame || !settled || reducedMotion || !anyBoxMoved(before, positions)) return;
    frame.dataset.moving = '';
    const timer = setTimeout(() => delete frame.dataset.moving, motion.slow);
    return () => {
      clearTimeout(timer);
      delete frame.dataset.moving;
    };
  }, [positions, settled, reducedMotion, motion.slow]);

  const value = useMemo<CanvasValue>(
    () => ({
      clusterId,
      interactive,
      tabStop,
      selectedBox,
      register: (id, element) => {
        if (element) elements.current.set(id, element);
        else elements.current.delete(id);
      },
      focus: (id, fromKeyboard) => {
        setFocused(id);
        if (fromKeyboard) keepInView(id);
      },
      select: (id) => {
        const box = model.nodes.find((n) => n.id === id);
        if (!box || !isBrokerNode(box)) return;
        setAnnounce(`Selected ${box.data.sentence}`);
        onSelect?.(box.data.nodeIds[0] ?? null);
      },
    }),
    [clusterId, interactive, tabStop, selectedBox, model.nodes, onSelect, keepInView],
  );

  const clear = () => {
    if (!selectedId) return;
    onSelect?.(null);
    setAnnounce('Selection cleared');
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = [...elements.current].find(([, element]) => element === event.target)?.[0];
    if (!current) return;
    if (event.key === 'Escape') {
      if (selectedId) event.preventDefault();
      clear();
      elements.current.get(current)?.focus({ preventScroll: true });
      return;
    }
    const next = moveTo(model.columns, event.key, current);
    if (!next) return;
    event.preventDefault();
    elements.current.get(next)?.focus({ preventScroll: true });
  };

  return (
    <>
      <div
        ref={wrapper}
        className={styles.wrapper}
        style={{ ...GEOMETRY, ...(height ? { blockSize: height } : {}) }}
        data-settled={settled || undefined}
        role={interactive ? 'group' : undefined}
        aria-label={interactive ? 'Cluster topology' : undefined}
        aria-describedby={interactive ? keysId : undefined}
        onKeyDown={interactive ? onKeyDown : undefined}
      >
        <CanvasContext.Provider value={value}>
          <ReactFlow
            nodes={model.nodes}
            edges={model.edges}
            nodeTypes={nodeTypes}
            colorMode={colorMode}
            fitView
            fitViewOptions={FIT}
            // React Flow's own floor is 0.5, below which a wide topology could not be fitted at all.
            minZoom={0.1}
            nodesDraggable={false}
            nodesConnectable={false}
            nodesFocusable={false}
            edgesFocusable={false}
            elementsSelectable={false}
            disableKeyboardA11y
            // A box's click (a mouse press, or Enter or Space on its button) reaches here. Without a handler
            // React Flow gives its node wrapper `pointer-events: none`, which the button inherits.
            onNodeClick={interactive ? (_, node) => value.select(node.id) : undefined}
            onPaneClick={interactive ? clear : undefined}
            onMoveStart={(event) => {
              if (event) operatorMoved.current = true;
            }}
            panOnScroll={interactive}
            zoomOnScroll={interactive}
            proOptions={proOptions}
          >
            <Background variant={BackgroundVariant.Dots} gap={20} />
          </ReactFlow>
        </CanvasContext.Provider>
        {interactive ? (
          <ViewControls
            fit={FIT}
            subject="topology"
            onViewChange={(change) => {
              operatorMoved.current = change === 'zoom';
            }}
          />
        ) : null}
      </div>
      {interactive ? (
        <VisuallyHidden id={keysId}>
          Arrow keys move between nodes, Home and End go to the first and the last, Enter or Space selects one and
          Escape clears the selection.
        </VisuallyHidden>
      ) : null}
      {model.dense ? <ReducedDetail count={model.logicalCount} onShowTable={onShowTable} /> : null}
      {interactive ? <Legend /> : null}
      <VisuallyHidden role="status">{announce}</VisuallyHidden>
      <VisuallyHidden>{model.summary}</VisuallyHidden>
    </>
  );
}

/** States the bound of the full drawing, and offers the table, which lists every node. */
function ReducedDetail({ count, onShowTable }: Readonly<{ count: number; onShowTable?: () => void }>) {
  return (
    <Notice
      title="One box per pair"
      action={
        onShowTable ? (
          <Button size="xs" variant="default" onClick={onShowTable}>
            Show as table
          </Button>
        ) : undefined
      }
    >
      This cluster has {count} logical nodes, more than the {DENSE_THRESHOLD} drawn in full. Each pair is one box in a
      grid {DENSE_COLUMNS} wide, saying which endpoint serves, how many stand behind it, and whether replication is
      behind or a split brain is present. The table lists every node.
    </Notice>
  );
}

/**
 * The identity-axis grammar, rendered from an already-computed {@link TopologyLayout}
 * (extracted out of the page so the registration preview and its example cards can
 * draw the exact same graph a live cluster uses — no forked visual language,
 * design.md Decision 6). Purely presentational: no data hooks.
 *
 * <p>`interactive={false}` draws the preview: the same boxes as plain, inert elements, with no controls,
 * no legend and no keyboard model.
 */
export function TopologyCanvas({
  model,
  interactive = true,
  height,
  clusterId,
  selectedId,
  onSelect,
  onShowTable,
}: Readonly<{
  model: TopologyLayout;
  interactive?: boolean;
  /** Override the frame height. Embedders that already constrain the box pass `100%`. */
  height?: string;
  /** The live cluster the canvas draws, so enabled features can mark its boxes. */
  clusterId?: string;
  /** The endpoint chosen, whose box is marked as selected. */
  selectedId?: string | null;
  /** A box was chosen (an endpoint's id), or the choice was cleared (null). */
  onSelect?: (nodeId: string | null) => void;
  /** The "Show as table" action of the reduced-detail notice; left out where there is no table to show. */
  onShowTable?: () => void;
}>) {
  if (model.nodes.length === 0) return <EmptyCanvas height={height} />;
  return (
    <div>
      <ReactFlowProvider>
        <Flow
          model={model}
          interactive={interactive}
          clusterId={clusterId}
          height={height}
          selectedId={selectedId}
          onSelect={onSelect}
          onShowTable={onShowTable}
        />
      </ReactFlowProvider>
    </div>
  );
}
