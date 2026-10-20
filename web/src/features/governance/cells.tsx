import { ActionIcon, Button, Switch } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { FindingView, RuleView } from './api.ts';
import classes from './Governance.module.css';
import { fieldOf, statusLabel, targetLabel } from './words.ts';

/** Open findings call for a decision; decided ones are quiet. */
export function FindingStatus({ finding }: Readonly<{ finding: FindingView }>) {
  return <StatusBadge tone={finding.status === 'OPEN' ? 'info' : 'neutral'}>{statusLabel(finding.status)}</StatusBadge>;
}

/** A rule's selector, marked when it is built in or came from a dismissed finding, with what it matches. */
export function SelectorCell({ rule: r }: Readonly<{ rule: RuleView }>) {
  return (
    <span className={classes.lines}>
      <span className={classes.inline}>
        <code>{r.selector}</code>
        {r.builtin ? <StatusBadge>built-in</StatusBadge> : null}
        {r.exception ? <StatusBadge>dismissed finding</StatusBadge> : null}
      </span>
      <span className={classes.note}>{targetLabel(r.target)}</span>
    </span>
  );
}

/** What the masking rules' row controls need from their view: what is allowed and busy right now, and what a click does. */
export interface RuleControls {
  canWrite: boolean;
  /** The rule being saved, whose switch is locked until the write settles. */
  savingId: string | undefined;
  onToggle: (rule: RuleView, enabled: boolean) => void;
  onEdit: (rule: RuleView) => void;
  onDelete: (rule: RuleView) => void;
}

/** The enabled switch. */
export function RuleEnabled({ rule, controls }: Readonly<{ rule: RuleView; controls: RuleControls }>) {
  return (
    <Switch
      size="sm"
      checked={rule.enabled}
      disabled={!controls.canWrite || controls.savingId === rule.id}
      aria-label={`Enabled: ${rule.selector}`}
      onChange={(e) => controls.onToggle(rule, e.currentTarget.checked)}
    />
  );
}

/** Edit and Delete, or the statement that a built-in rule has neither. */
export function RuleChanges({ rule, controls }: Readonly<{ rule: RuleView; controls: RuleControls }>) {
  if (rule.builtin) return <span className={classes.note}>Can be disabled, not deleted.</span>;
  return (
    <span className={classes.controls}>
      <ActionIcon
        variant="subtle"
        disabled={!controls.canWrite}
        onClick={() => controls.onEdit(rule)}
        aria-label={`Edit the rule for ${rule.selector}`}
      >
        <IconPencil size="1rem" aria-hidden />
      </ActionIcon>
      <ActionIcon
        variant="subtle"
        disabled={!controls.canWrite}
        onClick={() => controls.onDelete(rule)}
        aria-label={`Delete the rule for ${rule.selector}`}
      >
        <IconTrash size="1rem" aria-hidden />
      </ActionIcon>
    </span>
  );
}

/** What the inbox's decision controls need from their view. */
export interface FindingControls {
  canWrite: boolean;
  /** The finding being decided and which way, or null: every other finding is locked meanwhile. */
  deciding: { id: string; decision: 'confirm' | 'dismiss' | undefined } | null;
  onDecide: (finding: FindingView, decision: 'confirm' | 'dismiss') => void;
}

/** Confirm and Dismiss for an open finding; a decided one has none. */
export function FindingDecision({
  finding: f,
  controls,
}: Readonly<{ finding: FindingView; controls: FindingControls }>) {
  if (f.status !== 'OPEN') return null;
  const { canWrite, deciding } = controls;
  const locked = !canWrite || (deciding !== null && deciding.id !== f.id);
  const decidingHere = deciding?.id === f.id ? deciding.decision : undefined;
  return (
    <span className={classes.controls}>
      <Button
        size="compact-xs"
        variant="default"
        disabled={locked}
        loading={decidingHere === 'confirm'}
        onClick={() => controls.onDecide(f, 'confirm')}
        aria-label={`Confirm ${fieldOf(f)} on ${f.destination} as ${f.dataClassLabel}`}
      >
        Confirm
      </Button>
      <Button
        size="compact-xs"
        variant="default"
        disabled={locked}
        loading={decidingHere === 'dismiss'}
        onClick={() => controls.onDecide(f, 'dismiss')}
        aria-label={`Dismiss ${fieldOf(f)} on ${f.destination} as not ${f.dataClassLabel}`}
      >
        Dismiss
      </Button>
    </span>
  );
}
