import { Button, Code, Paper, Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useActionHost } from '../../kernel/actions/hostContext.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useRevokeRisk, type SetupFindingView } from './api.ts';
import { evidenceColumns } from './evidenceColumns.ts';
import classes from './SetupReview.module.css';
import { SEVERITY_WORDS, severityTone } from './words.ts';

const REVOKE: ActionVerb = { verb: 'Revoke', past: 'Revoked', progressive: 'Revoking' };

const evidenceKey = (e: SetupFindingView['evidence'][number]) => `${e.node}|${e.key}`;

const EVIDENCE_COLUMNS = evidenceColumns();

/** Who accepted the risk and why, or that an earlier acceptance has expired. */
function AcceptanceNote({ finding: f }: Readonly<{ finding: SetupFindingView }>) {
  const accepted = f.acceptance?.active ? f.acceptance : null;
  const expired = f.acceptance && !f.acceptance.active ? f.acceptance : null;
  return (
    <>
      {accepted ? (
        <Text size="sm">
          <Text span fw={600}>
            Accepted as a known risk
          </Text>{' '}
          by {accepted.acceptedBy} on {absoluteLabel(accepted.createdAt)}
          {accepted.expiresAt ? `, until ${absoluteLabel(accepted.expiresAt)}` : ', until revoked'}: “{accepted.reason}”
        </Text>
      ) : null}
      {expired ? (
        <Text size="sm">
          <StatusBadge tone="warning">acceptance expired</StatusBadge> An acceptance by {expired.acceptedBy} expired on{' '}
          {absoluteLabel(expired.expiresAt)}; the finding is open again.
        </Text>
      ) : null}
    </>
  );
}

/** What each node reported for the finding, key by key. */
function Evidence({ finding: f }: Readonly<{ finding: SetupFindingView }>) {
  if (f.evidence.length === 0) return null;
  return (
    <DataTable
      variant="static"
      label={`Evidence for ${f.code}`}
      columns={EVIDENCE_COLUMNS}
      data={f.evidence}
      rowKey={evidenceKey}
      height={{ maxRows: f.evidence.length }}
      empty={null}
    />
  );
}

/** The broker.xml that fixes the finding, with a copy button. */
function SnippetBlock({ finding: f }: Readonly<{ finding: SetupFindingView }>) {
  const host = useActionHost();
  if (!f.snippet) return null;
  return (
    <Stack gap={4}>
      <div className={classes.snippetHead}>
        <Text size="xs" c="dimmed">
          broker.xml
        </Text>
        <Button
          size="compact-xs"
          variant="subtle"
          onClick={() => host.copy(f.snippet ?? '', 'broker.xml fix')}
          aria-label={`Copy the broker.xml fix for ${f.code}`}
        >
          Copy
        </Button>
      </div>
      <Code block tabIndex={0} role="region" aria-label={`broker.xml fix for ${f.code}`} className={classes.snippet}>
        {f.snippet}
      </Code>
    </Stack>
  );
}

/**
 * One finding: what is wrong, what it costs, what each node reported, and the fix to
 * copy. An accepted risk stays visible with who accepted it and why.
 */
export function FindingCard({
  finding: f,
  clusterId,
  canAccept,
  onAccept,
}: Readonly<{
  finding: SetupFindingView;
  clusterId: string;
  canAccept: boolean;
  onAccept: () => void;
}>) {
  const revoke = useRevokeRisk(clusterId);
  const accepted = f.acceptance?.active ? f.acceptance : null;

  const revokeAcceptance = () => {
    const subject = `the acceptance of ${f.code}`;
    revoke.mutate(
      { code: f.code, subject: f.subject },
      {
        onSuccess: () => notify.succeeded({ action: REVOKE, subject }),
        onError: (e) =>
          notify.settle(e, {
            action: REVOKE,
            subject,
            cause: e.message,
            next: 'The finding is still accepted. Try again.',
          }),
      },
    );
  };

  return (
    <Paper withBorder p="sm" component="article" aria-label={f.title}>
      <Stack gap="xs">
        <div className={classes.heading}>
          <StatusBadge tone={severityTone(f.severity)}>{SEVERITY_WORDS[f.severity] ?? f.severity}</StatusBadge>
          <div className={classes.title}>
            <Text fw={600} size="sm">
              {f.title}
            </Text>
            <div className={classes.meta}>
              {f.subject === 'cluster' ? 'Whole cluster' : `Node ${f.subjectLabel}`} · {f.code} · first seen{' '}
              {absoluteLabel(f.firstSeenAt)}
              {f.stale ? ' · not re-checked by the last review: its node did not answer' : ''}
            </div>
          </div>
        </div>

        <AcceptanceNote finding={f} />

        <Text size="sm">{f.impact}</Text>

        <Evidence finding={f} />

        <Text size="sm">
          <Text span fw={600}>
            Fix:
          </Text>{' '}
          {f.recommendation}
        </Text>
        <SnippetBlock finding={f} />
        {f.caveats.length > 0 ? (
          <Stack gap={2}>
            {f.caveats.map((c) => (
              <Text size="xs" c="dimmed" key={c}>
                Note: {c}
              </Text>
            ))}
          </Stack>
        ) : null}

        <div className={classes.controls}>
          {f.appliable ? (
            <Link to={`/clusters/${clusterId}/artemis/configuration`} className={linkClasses.link}>
              Apply it in Broker configuration
            </Link>
          ) : null}
          {accepted ? (
            <Button
              size="compact-sm"
              variant="default"
              disabled={!canAccept}
              loading={revoke.isPending}
              onClick={revokeAcceptance}
            >
              Revoke acceptance
            </Button>
          ) : (
            <Button size="compact-sm" variant="default" disabled={!canAccept} onClick={onAccept}>
              Accept as a known risk…
            </Button>
          )}
        </div>
      </Stack>
    </Paper>
  );
}
