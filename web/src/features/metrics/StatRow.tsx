import { IconArrowDownRight, IconArrowUpRight, IconMinus } from '@tabler/icons-react';

import { Stat } from '../../ui/Stat.tsx';
import styles from './StatRow.module.css';

/**
 * The four numbers an operator arrives for, stated rather than inferred from the
 * right-hand edge of a line (ADR-0055).
 *
 * A metric with no recent sample says so. Rendering a dash as `0` is the
 * dangerous version: on this page a zero ingress rate and an unsampled ingress
 * rate mean entirely different things and lead to opposite actions.
 */
export interface Figure {
  label: string;
  unit: string;
  /** Current value, or `null` when nothing recent enough exists to state one. */
  value: number | null;
  /** Value at the start of the window, for stating movement. */
  since: number | null;
  format: (value: number) => string;
}

export function StatRow({ figures, loading }: Readonly<{ figures: Figure[]; loading: boolean }>) {
  return (
    <fieldset className={styles.row} aria-label="Current values">
      {figures.map((figure) => (
        <StatTile key={figure.label} figure={figure} loading={loading} />
      ))}
    </fieldset>
  );
}

function StatTile({ figure, loading }: Readonly<{ figure: Figure; loading: boolean }>) {
  const { label, unit, value, since, format } = figure;
  const delta = value !== null && since !== null ? value - since : null;

  return (
    <div className={styles.tile}>
      <Stat
        label={label}
        value={value === null ? null : format(value)}
        unit={unit}
        unavailableReason="Not sampled recently enough to state a value."
        loading={loading}
      />
      <Delta delta={loading ? undefined : delta} format={format} />
    </div>
  );
}

/** The arrow and the word for a change: none, up or down. */
function movement(flat: boolean, delta: number) {
  if (flat) return { Icon: IconMinus, word: 'unchanged' };
  return delta > 0 ? { Icon: IconArrowUpRight, word: 'up' } : { Icon: IconArrowDownRight, word: 'down' };
}

/**
 * Movement across the window. The direction is a word and an arrow, never the
 * colour alone — and it carries no colour at all, because a rising subscription depth is
 * not by itself something wrong. `undefined` is "still loading": the line is held, empty.
 */
function Delta({ delta, format }: Readonly<{ delta: number | null | undefined; format: (value: number) => string }>) {
  if (delta === undefined) return <div className={styles.movement} />;
  if (delta === null) return <div className={styles.movement}>no comparison in this window</div>;
  const flat = Math.abs(delta) < Number.EPSILON;
  const { Icon, word } = movement(flat, delta);
  return (
    <div className={styles.movement}>
      <Icon size="0.875rem" aria-hidden="true" />
      <span>
        {word}
        {flat ? '' : ` ${format(Math.abs(delta))}`} over the window
      </span>
    </div>
  );
}
