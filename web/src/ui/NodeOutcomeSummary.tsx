import { Text } from '@mantine/core';

import type { components } from '../kernel/api/schema.d.ts';
import classes from './NodeOutcomeSummary.module.css';
import { appliedEverywhere } from './nodeOutcome.ts';

type LifecycleOutcomeView = components['schemas']['LifecycleOutcomeView'];
type NodeOutcomeView = components['schemas']['NodeOutcomeView'];

/**
 * One cluster-wide lifecycle result, rendered as a single object (ADR-0049 D2).
 *
 * <p>Used for both the dry-run preview and the result, so what the operator
 * confirmed and what actually happened are visually comparable — a preview that
 * looks nothing like its outcome makes the two impossible to check against each
 * other.
 *
 * <p>Every node's state is carried in words. Colour is redundant with the text
 * and appears only where something is wrong, so a fully applied command reads as
 * near-monochrome and the eye stops at the one row that did not.
 */
export function NodeOutcomeSummary({
  outcome,
  destructive = false,
  alreadyLabel,
  countNoun = 'message',
  verbFuture = 'would destroy',
  verbPast = 'destroyed',
}: Readonly<{
  outcome: LifecycleOutcomeView;
  /** A destroy shows the message counts; a pause has none worth a column. */
  destructive?: boolean;
  /**
   * What `ALREADY` means for this command. It defaults to the lifecycle wording,
   * but a connection close needs "already gone" — "already in this state" reads
   * as though the operator had asked for something else.
   */
  alreadyLabel?: string;
  /**
   * What the counts are counting, and the verb for them. Defaults to the subscription
   * delete's wording; a connection close affects consumers, not messages, and
   * saying "destroyed" there would overstate what happened.
   */
  countNoun?: string;
  verbFuture?: string;
  verbPast?: string;
}>) {
  const verdict = verdictFor(outcome);
  const verb = outcome.dryRun ? verbFuture : verbPast;
  const plural = outcome.totalAffected === 1 ? '' : 's';

  return (
    <OutcomeSummary
      verdict={verdict.text}
      verdictTone={verdict.tone}
      total={destructive ? `${verb} ${outcome.totalAffected.toLocaleString()} ${countNoun}${plural}` : undefined}
      rows={outcome.nodes.map((node) => {
        const { text, tone } = statusWords(node.status, alreadyLabel);
        return {
          key: node.nodeId,
          name: node.nodeName,
          count: destructive && node.affected != null ? node.affected.toLocaleString() : undefined,
          status: text,
          tone,
          detail: node.error,
        };
      })}
    />
  );
}

/** One node's contribution, already in the words the caller's command uses. */
export interface OutcomeRow {
  key: string;
  name: string;
  /** An end-aligned figure, when the command has one worth comparing between nodes. */
  count?: string;
  status: string;
  tone?: 'warning' | 'danger';
  /** A failure's reason, or anything else that needs a second line. */
  detail?: string | null;
}

/**
 * The per-node result as a shape, without an opinion about what the command was.
 *
 * <p>{@link NodeOutcomeSummary} is this with the lifecycle vocabulary; the SQL
 * Console is this with a query's. Both go through here so a fan-out result reads
 * the same wherever it appears — which is the point of the house rule, and is
 * lost the moment a second screen re-implements the layout with its own spacing.
 */
export function OutcomeSummary({
  verdict,
  verdictTone,
  total,
  rows,
}: Readonly<{
  verdict: string;
  verdictTone?: 'warning' | 'danger';
  total?: string;
  rows: OutcomeRow[];
}>) {
  return (
    // A live region, so a screen reader hears what a destructive command did without hunting for it.
    <div className={classes.summary} role="status" aria-live="polite">
      <div className={classes.headline}>
        <Text size="sm" className={classes.verdict} data-tone={verdictTone}>
          {verdict}
        </Text>
        {total ? (
          <Text size="xs" className={classes.total}>
            {total}
          </Text>
        ) : null}
      </div>

      <div className={classes.rows}>
        {rows.map((row) => (
          <div key={row.key}>
            <div className={classes.row}>
              <Text size="sm" className={classes.node}>
                {row.name}
              </Text>
              <div className={classes.state}>
                {row.count != null ? (
                  <Text size="xs" className={classes.count}>
                    {row.count}
                  </Text>
                ) : null}
                <Text size="xs" className={classes.status} data-tone={row.tone}>
                  {row.status}
                </Text>
              </div>
            </div>
            {row.detail ? (
              <Text size="xs" className={classes.error} data-tone={row.tone}>
                {row.detail}
              </Text>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * The one line that tells an operator the shape of the result. Stated before the
 * rows so a partial application is apparent without reading any of them.
 */
function verdictFor(outcome: LifecycleOutcomeView): { text: string; tone?: 'warning' | 'danger' } {
  const targets = outcome.nodes.filter((n) => n.status !== 'SKIPPED_NOT_LIVE').length;
  const skipped = outcome.nodes.length - targets;
  const failed = outcome.nodes.filter((n) => n.status === 'FAILED').length;

  if (outcome.dryRun) {
    // A node its preflight refused is not one the command would apply to; counting it there
    // would put "would apply" above a row that says "failed".
    const refused = failed > 0 ? `, ${failed} refused` : '';
    const suffix = skipped > 0 ? `, ${skipped} not live and will be skipped` : '';
    const tone = failed === targets ? 'danger' : 'warning';
    return {
      text: `Would apply to ${targets - failed} of ${outcome.nodes.length} nodes${refused}${suffix}`,
      tone: failed === 0 ? undefined : tone,
    };
  }
  if (outcome.partial) {
    return { text: 'Applied to some nodes and not others', tone: 'warning' };
  }
  if (failed > 0 && failed === targets) {
    return { text: 'Failed on every node', tone: 'danger' };
  }
  if (!appliedEverywhere(outcome)) {
    // No node was live, so nothing ran. "Applied to all 0 live nodes" reads as a
    // success; an operator acting on that believes the command happened.
    return { text: 'No node was live, so nothing was applied', tone: 'warning' };
  }
  if (skipped > 0) {
    return { text: `Applied to all ${targets} live nodes · ${skipped} not live`, tone: 'warning' };
  }
  return { text: `Applied to all ${targets} nodes` };
}

/**
 * A node's state as words. `ALREADY` is deliberately worded as a success — it
 * means the node is in the requested state, which is what was asked for.
 */
function statusWords(
  status: NodeOutcomeView['status'],
  alreadyLabel?: string,
): {
  text: string;
  tone?: 'warning' | 'danger';
} {
  switch (status) {
    case 'WOULD_APPLY':
      return { text: 'would apply' };
    case 'APPLIED':
      return { text: 'applied' };
    case 'ALREADY':
      return { text: alreadyLabel ?? 'already in this state' };
    case 'SKIPPED_NOT_LIVE':
      return { text: 'skipped — not live', tone: 'warning' };
    case 'UNSUPPORTED_VERSION':
      return { text: 'skipped — Artemis release too old', tone: 'warning' };
    case 'FAILED':
      return { text: 'failed', tone: 'danger' };
    default:
      return { text: status };
  }
}
