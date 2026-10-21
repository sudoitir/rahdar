import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react';
import {
  Background,
  MiniMap,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  useStoreApi,
  type Edge,
  type Node,
} from '@xyflow/react';
import { Stack, Text, Transition, useComputedColorScheme, useMantineTheme } from '@mantine/core';
import { useReducedMotion } from '@mantine/hooks';

import { AnchoredMenu } from '../../ui/AnchoredMenu.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { ViewControls } from '../../ui/graph/ViewControls.tsx';
import type { MenuAnchor } from '../../ui/table/menuAnchor.ts';
import type { FlowGraphView } from './api.ts';
import { FlowCanvasContext, type FlowCanvasState } from './canvasContext.ts';
import { allocateDots } from './edgeEncoding.ts';
import { FlowEdge } from './FlowEdge.tsx';
import { DENSE_NODES, layoutSignature, pathThrough, reuseUnchanged, toReactFlow } from './flowLayout.ts';
import { FlowLegend } from './FlowLegend.tsx';
import { hasActions } from './flowSearch.ts';
import { FlowNodeActions } from './rowActions.tsx';
import { DestinationNode, ClientNode, LaneNode, SubscriptionNode, RemoteNode } from './FlowNodes.tsx';
import { useFlowLayout } from './useFlowLayout.ts';
import classes from './FlowCanvas.module.css';

const nodeTypes = {
  client: ClientNode,
  destination: DestinationNode,
  subscription: SubscriptionNode,
  remote: RemoteNode,
  lane: LaneNode,
};
const edgeTypes = { flow: FlowEdge };

/** Labels and dots below this zoom would be unreadable specks; widths still carry the rate. */
const TEXT_ZOOM = 0.55;

function ZoomDetail({ onChange }: { onChange: (showText: boolean) => void }) {
  const showText = useStore((s) => s.transform[2] >= TEXT_ZOOM);
  useEffect(() => onChange(showText), [showText, onChange]);
  return null;
}

/** The smallest zoom a fresh view opens at: above TEXT_ZOOM, so rates and dots are drawn. */
const OPEN_ZOOM = 0.7;
const MARGIN = 24;

/** How the canvas fits itself, and what its fit button does: never magnified past natural size. */
const FIT = { padding: 0.12, maxZoom: 1 };

/** How long a pointer may leave one node for the next before the emphasis clears, so crossing a gap does not flicker. */
const EMPHASIS_CLEAR_MS = 80;

/** A reveal settles quickly and never overshoots: ease-out, in a straight line, at the zoom the operator has. */
const REVEAL_MS = 200;
const easeOut = (t: number) => 1 - (1 - t) ** 3;

const MINIMAP_KEY = 'rahdar.flow.minimap';

/**
 * The overview map, in the product's own colours and foldable away.
 *
 * <p>React Flow draws the minimap with its own light palette, which on a dark
 * canvas is a white rectangle sitting over the graph — the brightest thing on
 * the screen, for the least important. It is painted from the flow tokens
 * instead, and on a graph the size of a real estate it is often in the way, so
 * it folds to its own button. The choice is per viewer and survives a reload;
 * browser storage can throw, and a minimap that will not fold is a smaller
 * problem than a canvas that will not render.
 */
function FlowMiniMap({ needed }: Readonly<{ needed: boolean }>) {
  // The viewer's own choice wins; without one, the map is open only where the graph needs it.
  const [chosen, setChosen] = useState<boolean | null>(() => {
    try {
      const stored = globalThis.localStorage.getItem(MINIMAP_KEY);
      return stored === null ? null : stored !== 'off';
    } catch {
      return null;
    }
  });
  const open = chosen ?? needed;

  const toggle = () => {
    const next = !open;
    setChosen(next);
    try {
      globalThis.localStorage.setItem(MINIMAP_KEY, next ? 'on' : 'off');
    } catch {
      /* a preference that cannot be stored is still honoured for this session */
    }
  };

  return (
    <>
      <Panel position="bottom-right" className={classes.minimapToggle}>
        <button type="button" onClick={toggle} aria-expanded={open} className={classes.minimapButton}>
          {open ? 'Hide overview' : 'Show overview'}
        </button>
      </Panel>
      {open ? (
        <MiniMap
          pannable
          zoomable
          className={classes.minimap}
          nodeClassName={classes.minimapNode}
          maskColor="var(--as-flow-minimap-mask)"
          maskStrokeColor="var(--as-border)"
          bgColor="transparent"
          ariaLabel="Overview of the whole graph"
        />
      ) : null}
    </>
  );
}

/**
 * Fit on the first layout, and again only when the operator asks for a different view (the focus, the
 * bound, the ranking or the layers) and its graph has been laid out. A client joining or leaving changes
 * the layout but keeps the operator's pan and zoom. A graph too large to fit legibly opens at a readable
 * zoom, from its first row, rather than shrunk to specks; it says so through `onFit`, and the minimap and
 * panning reach the rest. `onFitted` follows once the view has moved.
 */
function RefitOnLayout({
  signature,
  viewKey,
  onFit,
  onFitted,
}: Readonly<{
  signature: string | null;
  viewKey: string;
  onFit: (overflowing: boolean) => void;
  onFitted: () => void;
}>) {
  const flow = useReactFlow();
  const store = useStoreApi();
  const latestKey = useRef(viewKey);
  const fittedKey = useRef<string | null>(null);
  useLayoutEffect(() => {
    latestKey.current = viewKey;
  });
  useEffect(() => {
    if (!signature || fittedKey.current === latestKey.current) return;
    const frame = requestAnimationFrame(() => {
      const { width, height } = store.getState();
      if (width === 0 || height === 0) return;
      fittedKey.current = latestKey.current;
      const bounds = flow.getNodesBounds(flow.getNodes());
      const fit = Math.min(width / (bounds.width + 2 * MARGIN), height / (bounds.height + 2 * MARGIN));
      onFit(fit < OPEN_ZOOM);
      const zoom = Math.min(1, Math.max(OPEN_ZOOM, width / (bounds.width + 2 * MARGIN)));
      const x = Math.max(MARGIN, (width - bounds.width * zoom) / 2) - bounds.x * zoom;
      const moved = fit >= OPEN_ZOOM ? flow.fitView(FIT) : flow.setViewport({ x, y: MARGIN - bounds.y * zoom, zoom });
      void moved.then(onFitted);
    });
    return () => cancelAnimationFrame(frame);
  }, [flow, store, signature, onFit, onFitted]);
  return null;
}

/**
 * A selection the operator cannot see at all — restored from the address, or chosen by keyboard or from
 * the find box — is brought into view at the current zoom, centred in the part of the canvas the
 * inspector leaves uncovered. The rest of the graph dims around a selection, so one off-screen would
 * leave nothing bright to look at. A keyboard choice and reduced motion jump; anything else glides.
 */
function RevealSelected({
  id,
  ready,
  fits,
  instant,
  covered,
}: Readonly<{
  id: string | null;
  ready: boolean;
  /** Counts the canvas's own fits; a fit can move the selection out of view, so each one checks again. */
  fits: number;
  /** Set when the choice came from the keyboard; read and cleared by the reveal. */
  instant: RefObject<boolean>;
  /** The element covering the canvas's inline-end edge, whose width is not part of the view. */
  covered: RefObject<HTMLElement | null>;
}>) {
  const flow = useReactFlow();
  const store = useStoreApi();
  const reducedMotion = useReducedMotion();
  useEffect(() => {
    if (!id || !ready) return;
    const frame = requestAnimationFrame(() => {
      const jump = instant.current || reducedMotion;
      instant.current = false;
      const node = flow.getInternalNode(id);
      if (!node) return;
      const { width: frameWidth, height, transform } = store.getState();
      const width = Math.max(0, frameWidth - (covered.current?.offsetWidth ?? 0));
      const [tx, ty, zoom] = transform;
      const { x, y } = node.internals.positionAbsolute;
      const w = node.measured.width ?? 0;
      const h = node.measured.height ?? 0;
      const left = x * zoom + tx;
      const top = y * zoom + ty;
      if (left + w * zoom > 0 && top + h * zoom > 0 && left < width && top < height) return;
      void flow.setViewport(
        { x: width / 2 - (x + w / 2) * zoom, y: height / 2 - (y + h / 2) * zoom, zoom },
        jump ? { duration: 0 } : { duration: REVEAL_MS, ease: easeOut, interpolate: 'linear' },
      );
    });
    return () => cancelAnimationFrame(frame);
  }, [flow, store, id, ready, fits, reducedMotion, instant, covered]);
  return null;
}

/** Reduced motion turns the animation off; a paused or hidden canvas holds it; otherwise it runs. */
function motionOf(reducedMotion: boolean, held: boolean): FlowCanvasState['motion'] {
  if (reducedMotion) return 'off';
  return held ? 'paused' : 'running';
}

/**
 * The flow graph (flow-visualization spec, ADR-0080): four columns laid out by ELK in a worker,
 * rates as width, labels and moving dots, faults in words, and the path through whatever is hovered,
 * focused or selected emphasised. `inspector`, when given, explains the selected node over the canvas's
 * inline-end edge, so opening it leaves the canvas's size and the operator's view alone.
 */
export function FlowCanvas({
  clusterId,
  graph,
  selectedId,
  onSelect,
  paused,
  viewKey = '',
  inspector,
}: Readonly<{
  clusterId: string;
  graph: FlowGraphView;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  paused: boolean;
  /** What the operator asked to see (focus, bound, ranking, layers); a change refits the next layout. */
  viewKey?: string;
  /** Draws the inspector for a node. */
  inspector?: (id: string) => ReactNode;
}>) {
  const layout = useFlowLayout(graph);
  const wrapper = useRef<HTMLDivElement>(null);
  const covering = useRef<HTMLDivElement>(null);
  const reducedMotion = useReducedMotion();
  const { motion: durations } = useMantineTheme().other;
  // The canvas takes the computed scheme, so its controls and minimap follow the page, not the browser.
  const colorMode = useComputedColorScheme('dark', { getInitialValueInEffect: false });
  const [hovered, setHovered] = useState<string | null>(null);
  const [showText, setShowText] = useState(true);
  const [visible, setVisible] = useState(true);
  const [overflowing, setOverflowing] = useState(false);
  const [fits, setFits] = useState(0);
  const fitted = useCallback(() => setFits((n) => n + 1), []);
  const [menu, setMenu] = useState<{ id: string; anchor: MenuAnchor; opener: HTMLElement } | null>(null);
  const openMenu = useCallback((id: string, anchor: MenuAnchor, opener: HTMLElement) => {
    setMenu({ id, anchor, opener });
  }, []);
  // The inspector keeps the last node it showed while it leaves, so it does not empty as it fades.
  const [inspected, setInspected] = useState(selectedId);
  if (selectedId && selectedId !== inspected) setInspected(selectedId);
  const instantReveal = useRef(false);
  const select = useCallback(
    (id: string, fromKeyboard: boolean) => {
      instantReveal.current = fromKeyboard;
      onSelect(id);
    },
    [onSelect],
  );
  const clearEmphasis = useRef<ReturnType<typeof setTimeout>>(undefined);
  const emphasize = useCallback((id: string | null) => {
    clearTimeout(clearEmphasis.current);
    if (id) setHovered(id);
    else clearEmphasis.current = setTimeout(() => setHovered(null), EMPHASIS_CLEAR_MS);
  }, []);
  useEffect(() => () => clearTimeout(clearEmphasis.current), []);
  // Dots hold still while the operator pans or zooms, which would otherwise redraw every one each frame.
  const userMoving = useRef(false);
  const menuNode = menu ? (graph.nodes ?? []).find((n) => n.id === menu.id) : undefined;

  useEffect(() => {
    let onScreen = true;
    const update = () => setVisible(onScreen && document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', update);
    let observer: IntersectionObserver | undefined;
    if (typeof IntersectionObserver !== 'undefined' && wrapper.current) {
      observer = new IntersectionObserver(([entry]) => {
        onScreen = entry.isIntersecting;
        update();
      });
      observer.observe(wrapper.current);
    }
    return () => {
      document.removeEventListener('visibilitychange', update);
      observer?.disconnect();
    };
  }, []);

  const motion = motionOf(reducedMotion, paused || !visible);

  const emphasisId = hovered ?? selectedId;
  const emphasis = useMemo(() => (emphasisId ? pathThrough(graph, emphasisId) : null), [graph, emphasisId]);
  const dots = useMemo(
    () =>
      allocateDots(
        (graph.edges ?? []).map((e) => ({
          id: e.id!,
          rate: e.rate,
          animatable: e.rateSource !== 'NONE' && !e.stale,
        })),
      ),
    [graph],
  );
  const previous = useRef<{ nodes: Node[]; edges: Edge[] }>({ nodes: [], edges: [] });
  const model = useMemo(() => {
    const next = toReactFlow(graph, layout.positions, dots, emphasis);
    return {
      nodes: reuseUnchanged(previous.current.nodes, next.nodes),
      edges: reuseUnchanged(previous.current.edges, next.edges),
    };
  }, [graph, layout.positions, dots, emphasis]);
  useLayoutEffect(() => {
    previous.current = model;
  }, [model]);

  // Pausing the SVG timeline freezes every dot where it is, which a reduced frame rate or a
  // removed element cannot do; it also costs nothing while paused.
  const animate = useCallback(
    (run: boolean) => {
      wrapper.current?.querySelectorAll('svg').forEach((svg) => {
        if (run && motion === 'running') svg.unpauseAnimations?.();
        else svg.pauseAnimations?.();
      });
    },
    [motion],
  );
  useEffect(() => {
    animate(!userMoving.current);
  }, [animate, model]);

  const context = useMemo<FlowCanvasState>(
    () => ({ select, openMenu, emphasize, showText, motion }),
    [select, openMenu, emphasize, showText, motion],
  );
  const dense = model.nodes.length > DENSE_NODES;
  const laidOut = Object.keys(layout.positions).length > 0;
  const inspecting = Boolean(inspector && selectedId);

  return (
    <div>
      {layout.error ? (
        <Stack gap="xs" mb="md">
          <Text size="sm">The graph could not be laid out. The table still lists every shown path.</Text>
          <ErrorState error={new Error(layout.error)} onRetry={layout.retry} />
        </Stack>
      ) : null}
      <div
        ref={wrapper}
        className={classes.wrapper}
        data-dense={dense || undefined}
        data-inspecting={inspecting || undefined}
        onKeyDown={(event) => {
          if (event.key === 'Escape') onSelect(null);
        }}
      >
        {!laidOut ? (
          <div className={classes.overlay}>
            <LoadingState label="Laying out the graph" />
          </div>
        ) : null}
        <FlowCanvasContext.Provider value={context}>
          <ReactFlowProvider>
            <ReactFlow
              nodes={model.nodes}
              edges={model.edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              colorMode={colorMode}
              minZoom={0.15}
              maxZoom={1.6}
              nodesDraggable={false}
              nodesConnectable={false}
              nodesFocusable={false}
              edgesFocusable={false}
              elementsSelectable={false}
              onlyRenderVisibleElements={dense}
              onPaneClick={() => onSelect(null)}
              onMoveStart={(event) => {
                // Only the operator's own pan or zoom: a reveal or a refit moves the view with no event.
                if (!event) return;
                // The menu is anchored to a point on screen; panning moves the node away from it.
                setMenu(null);
                userMoving.current = true;
                animate(false);
              }}
              onMoveEnd={() => {
                if (!userMoving.current) return;
                userMoving.current = false;
                animate(true);
              }}
              proOptions={{ hideAttribution: true }}
            >
              <Background gap={24} />
              <FlowMiniMap needed={dense || overflowing} />
              <ZoomDetail onChange={setShowText} />
              <RefitOnLayout
                signature={layout.pending ? null : layoutSignature(graph)}
                viewKey={viewKey}
                onFit={setOverflowing}
                onFitted={fitted}
              />
              <RevealSelected
                id={selectedId}
                ready={laidOut && !layout.pending}
                fits={fits}
                instant={instantReveal}
                covered={covering}
              />
            </ReactFlow>
            <ViewControls
              fit={FIT}
              subject="graph"
              className={classes.viewControls}
              onViewChange={() => setMenu(null)}
            />
          </ReactFlowProvider>
        </FlowCanvasContext.Provider>
        {inspector ? (
          <Transition
            mounted={inspecting}
            transition={{
              in: { opacity: 1, transform: 'translateX(0)' },
              out: { opacity: 0, transform: 'translateX(0.5rem)' },
              transitionProperty: 'opacity, transform',
            }}
            duration={durations.base}
            exitDuration={durations.fast}
            timingFunction="var(--as-ease)"
          >
            {(style) => (
              <div ref={covering} className={classes.inspector} style={style}>
                {inspected ? inspector(inspected) : null}
              </div>
            )}
          </Transition>
        ) : null}
      </div>
      <FlowLegend motion={motion} />
      <AnchoredMenu
        opened={menu !== null}
        anchor={menu?.anchor ?? null}
        label={`Actions for ${menuNode?.label ?? 'node'}`}
        onClose={() => {
          const opener = menu?.opener;
          setMenu(null);
          if (opener?.isConnected) opener.focus();
        }}
      >
        {menuNode && hasActions(menuNode) ? (
          <FlowNodeActions
            clusterId={clusterId}
            node={menuNode}
            restoreFocus={() => {
              if (menu?.opener.isConnected) menu.opener.focus();
            }}
          />
        ) : (
          <Text size="sm" c="dimmed" px="sm" py="xs">
            Nothing to open for this node.
          </Text>
        )}
      </AnchoredMenu>
    </div>
  );
}
