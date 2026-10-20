import type { ReactNode, RefObject } from 'react';
import { Button, Switch, Text } from '@mantine/core';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { Toolbar } from '../../ui/Toolbar.tsx';
import type { SqlPlanView } from './api.ts';
import type { CostVerdict } from './costVerdict.ts';
import { ExplainStrip } from './ExplainStrip.tsx';
import { QueryEditor, type QueryError } from './QueryEditor.tsx';
import classes from './SqlConsoleView.module.css';

/** How many subscription names the editor's completion is offered. Enough to cover a cluster. */
export const QUEUE_COMPLETION_LIMIT = 500;

/**
 * The Query toolbar: Run and Cancel side by side, the live tail, and the history and syntax help at its
 * end. It sits above the split, outside the editor's pane, so however small the operator makes the
 * editor, Run and Cancel never scroll out of reach.
 */
export function QueryToolbar({
  toolbarRef,
  text,
  onRun,
  onCancel,
  gate,
  live,
  onLive,
  running,
  cancellable,
  history,
  costId,
}: Readonly<{
  toolbarRef: RefObject<HTMLDivElement | null>;
  text: string;
  onRun: () => void;
  onCancel: () => void;
  gate: GateVerdict;
  live: boolean;
  onLive: (next: boolean) => void;
  running: boolean;
  /** A query is reading, or a tail is following: there is something to cancel. */
  cancellable: boolean;
  /** The History menu and the syntax help button, which sit at the end of the toolbar. */
  history: ReactNode;
  /** The id of the cost line, which describes Run. */
  costId: string;
}>) {
  const blocked = gate.kind === 'blocked';
  return (
    <div ref={toolbarRef}>
      <Toolbar
        label="Query"
        start={
          <>
            {/* The reason a control is disabled hangs off the gate, not off the control: a disabled
                input takes no focus, so a hover-only explanation is unreachable for an operator on a
                keyboard. */}
            <CapabilityGate verdict={gate} what="Run">
              <Button
                size="xs"
                onClick={onRun}
                loading={running}
                disabled={blocked || !text.trim()}
                aria-describedby={costId}
              >
                {live ? 'Run and tail' : 'Run'}
              </Button>
            </CapabilityGate>
            <Button size="xs" variant="default" onClick={onCancel} disabled={!cancellable}>
              Cancel
            </Button>
            <CapabilityGate verdict={gate} what="Live tail">
              <Switch
                size="xs"
                label="Live tail"
                checked={live}
                onChange={(e) => onLive(e.currentTarget.checked)}
                disabled={blocked}
              />
            </CapabilityGate>
          </>
        }
        end={history}
      />
    </div>
  );
}

/**
 * Writing a query: the editor and what the query will cost. The editor takes the height the split gives
 * the pane and the hint and the cost line keep theirs, so the cost line stays in view under the editor.
 *
 * <p>It is a named, focusable region because, in a window too short for even the editor's floor (200%
 * zoom), its content is taller than its pane, and a scroll area the keyboard cannot reach is a defect.
 * Escape in the editor, with nothing left to close or collapse, calls `onEscape`, which moves focus to the
 * toolbar; it never cancels, since cancelling is Mod+. and a key that leaves the editor must not also stop
 * a query that is reading brokers.
 */
export function QueryPane({
  text,
  onText,
  onRun,
  onCancel,
  onMaximise,
  onEscape,
  subscriptionNames,
  completionCapped,
  costId,
  verdict,
  plan,
  queryError,
}: Readonly<{
  text: string;
  onText: (next: string) => void;
  onRun: () => void;
  onCancel: () => void;
  onMaximise: () => void;
  onEscape: () => void;
  subscriptionNames: string[];
  completionCapped: boolean;
  /** The id of the cost line, which describes the editor and Run. */
  costId: string;
  verdict: CostVerdict;
  plan: SqlPlanView | undefined;
  queryError: QueryError | null;
}>) {
  return (
    <section className={classes.region} aria-label="Query and cost" tabIndex={0}>
      <QueryEditor
        value={text}
        onChange={onText}
        onRun={onRun}
        onCancel={onCancel}
        onMaximise={onMaximise}
        onEscape={onEscape}
        subscriptions={subscriptionNames}
        describedBy={costId}
        error={queryError}
      />

      <Text size="xs" c="dimmed" className={classes.fixed}>
        ⌘/Ctrl-Enter runs · ⌘/Ctrl-. cancels · Ctrl-Space completes · Escape leaves the editor
        {completionCapped
          ? ` · completion lists the first ${QUEUE_COMPLETION_LIMIT.toLocaleString()} subscriptions on this cluster, not all of them`
          : ''}
      </Text>

      {/* Last in the pane: the estimate arrives after the editor and grows with its notices, and nothing
          below it is there to be pushed down. */}
      <div className={classes.fixed}>
        <ExplainStrip id={costId} verdict={verdict} plan={plan} />
      </div>
    </section>
  );
}
