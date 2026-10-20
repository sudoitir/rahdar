import { useMemo, useState } from 'react';
import { Button, Code, Collapse, Group, NumberInput, SegmentedControl, Stack, Text, TextInput } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import {
  useCreateIndexSubscription,
  useIndexSubscriptions,
  usePreviewIndexSubscription,
  type SqlCapturePreviewView,
  type SqlIndexSubscriptionRequest,
} from './api.ts';
import { nameOf } from '../../kernel/api/resourceId.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { HoldToConfirm } from '../../ui/HoldToConfirm.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Notice } from '../../ui/Notice.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { bytes } from './subscriptionFormat.ts';
import { subscriptionColumns } from './subscriptionColumns.ts';

/**
 * What a capture tap creates on every live node, in the words the operator needs
 * before agreeing to it. Not documentation: this is the last screen before Rahdar
 * starts mutating broker routing on a schedule, so the whole blast radius is here.
 */
function CaptureBlastRadius({ pattern, retentionDays }: Readonly<{ pattern: string; retentionDays: number }>) {
  const target = pattern.trim() || 'these subscriptions';
  return (
    <Notice tone="warning" title="This changes routing on every live node">
      <Stack gap={6}>
        <Text size="sm">
          On each live node Rahdar will create a <strong>non-exclusive divert</strong> from {target}, a{' '}
          <strong>ring-bounded, non-durable subscription</strong> to hold the copies, an{' '}
          <strong>address setting</strong> that stops the broker paging or blocking on Rahdar&apos;s account, and a{' '}
          <strong>security setting</strong> restricting that subscription to Rahdar&apos;s own role. Production routing
          is untouched — the divert copies, it does not take.
        </Text>
        <Text size="sm">
          None of those objects disappears when the broker restarts. Removing this subscription is what removes them;
          nothing else will.
        </Text>
        <Text size="sm">
          Every message the destination routes is stored — headers, properties and the body — for {retentionDays} day
          {retentionDays === 1 ? '' : 's'} or until the size bound is reached, whichever comes first, and is searchable
          by anyone who can read messages on this cluster.
        </Text>
        <Text size="sm">
          The equivalent, for an estate that deploys from <Code>broker.xml</Code>, is a <Code>divert</Code> onto{' '}
          <Code>rahdar.capture.…</Code> with <Code>&lt;exclusive&gt;false&lt;/exclusive&gt;</Code>, plus an{' '}
          <Code>address-setting</Code> for <Code>rahdar.capture.&lt;instance&gt;.#</Code> with <Code>DROP</Code>, a{' '}
          <Code>ring-size</Code>, a <Code>max-size-bytes</Code> and an <Code>expiry-delay</Code>. The preview below
          shows the exact configuration.
        </Text>
      </Stack>
    </Notice>
  );
}

/** A bound the server enforces, checked on blur with the same limits so a refusal is seen beside its field. */
interface Limit {
  label: string;
  description: string;
  min: number;
  max: number;
}

const LIMITS = {
  ringSize: {
    label: 'Ring size (messages per node)',
    description: 'How many copies each capture subscription holds before the broker drops the oldest.',
    min: 100,
    max: 1_000_000,
  },
  maxMegabytes: {
    label: 'Stored payload limit (MB)',
    description: "Payload this subscription may keep in Rahdar's database before it reports itself degraded.",
    min: 1,
    max: 1_000_000,
  },
  maxRate: {
    label: 'Rate limit (messages per second)',
    description: 'Capture slows to this rate; the capture subscription holds the backlog up to its bound.',
    min: 1,
    max: 1_000_000,
  },
  bodyCapKilobytes: {
    label: 'Body stored per message (KB)',
    description: 'A longer body is stored truncated, and marked so.',
    min: 1,
    max: 16 * 1024,
  },
} satisfies Record<string, Limit>;

type BoundField = keyof typeof LIMITS;

function rangeError(limit: Limit, value: number | string): string | null {
  if (value === '') return null;
  const n = Number(value);
  return n < limit.min || n > limit.max
    ? `Must be between ${limit.min.toLocaleString()} and ${limit.max.toLocaleString()}.`
    : null;
}

/** The optional bounds of a capture, behind a disclosure; defaults apply where they are left empty. */
function CaptureBounds({
  open,
  onToggle,
  bounds,
  errors,
  frozen,
  onChange,
  onBlur,
}: Readonly<{
  open: boolean;
  onToggle: () => void;
  bounds: Record<BoundField, number | string>;
  errors: Partial<Record<string, string | null>>;
  frozen: boolean;
  onChange: (field: BoundField, value: number | string) => void;
  onBlur: (field: BoundField) => void;
}>) {
  return (
    <>
      <Button size="compact-xs" variant="subtle" aria-expanded={open} onClick={onToggle}>
        {open ? 'Hide capture bounds' : 'Capture bounds (defaults apply when left empty)'}
      </Button>
      <Collapse expanded={open}>
        <Stack gap="xs">
          {(Object.keys(LIMITS) as BoundField[]).map((field) => (
            <NumberInput
              key={field}
              label={LIMITS[field].label}
              description={LIMITS[field].description}
              min={LIMITS[field].min}
              max={LIMITS[field].max}
              value={bounds[field]}
              readOnly={frozen}
              error={errors[field]}
              onChange={(v) => onChange(field, v)}
              onBlur={() => onBlur(field)}
              size="xs"
            />
          ))}
        </Stack>
      </Collapse>
    </>
  );
}

/** What capture would do, from the server's dry run — the same objects it will create. */
function CapturePreview({ preview }: Readonly<{ preview: SqlCapturePreviewView }>) {
  if (preview.refusal) {
    return (
      <Notice tone="danger" title="Capture would be refused">
        {preview.refusal}
      </Notice>
    );
  }
  const destinations = (preview.destinations ?? []).map(nameOf);
  const nodes = preview.nodes ?? [];
  return (
    <Stack gap={6} role="status" aria-live="polite">
      <Text size="sm">
        Covers {destinations.length} destination{destinations.length === 1 ? '' : 's'}: {destinations.join(', ')}
      </Text>
      <Text size="sm">
        Installed on {nodes.length} live node{nodes.length === 1 ? '' : 's'}: {nodes.join(', ')}
      </Text>
      <Text size="sm">
        Each capture subscription holds at most {(preview.ringMessages ?? 0).toLocaleString()} messages or{' '}
        {bytes(preview.ringBytes ?? 0)}, whichever is reached first. Past that the broker drops the oldest copy and
        Rahdar reports it as missed.
      </Text>
      <Text size="xs" fw={600}>
        Created on every listed node
      </Text>
      <Stack gap={2}>
        {(preview.brokerObjects ?? []).map((object) => (
          <Code key={object}>{object}</Code>
        ))}
      </Stack>
      {preview.brokerXml ? (
        <>
          <Text size="xs" fw={600}>
            The equivalent broker.xml
          </Text>
          <Code block>{preview.brokerXml}</Code>
        </>
      ) : null}
    </Stack>
  );
}

const SAMPLE: ActionVerb = { verb: 'Start sampling', past: 'Started sampling', progressive: 'Starting sampling' };
const CAPTURE: ActionVerb = { verb: 'Start capturing', past: 'Started capturing', progressive: 'Starting capturing' };

const optional = (value: number | string, scale = 1) => (value === '' ? undefined : Number(value) * scale);

/** The request the form makes; the capture bounds are only sent for a capture. */
function subscriptionBody(form: {
  pattern: string;
  retention: number;
  intervalMs: number | string;
  mode: 'SAMPLE' | 'CAPTURE';
  filterString: string;
  bounds: Record<BoundField, number | string>;
}): SqlIndexSubscriptionRequest {
  const { pattern, retention, intervalMs, mode, filterString, bounds } = form;
  const base = {
    subscriptionPattern: pattern.trim(),
    retentionDays: retention,
    intervalMs: Number(intervalMs) || 5000,
    enabled: true,
    mode,
  };
  if (mode !== 'CAPTURE') return base;
  return {
    ...base,
    filterString: filterString.trim() || undefined,
    ringSize: optional(bounds.ringSize),
    maxBytes: optional(bounds.maxMegabytes, 1024 * 1024),
    maxRate: optional(bounds.maxRate),
    bodyCapBytes: optional(bounds.bodyCapKilobytes, 1024),
  };
}

/** Why the form cannot be submitted yet, in words; null when it can. */
function blockedReason(permitted: boolean, capture: boolean, pattern: string, invalid: boolean): string | null {
  if (!permitted) {
    return capture
      ? 'Turning capture on needs the capture write permission.'
      : 'Creating a subscription needs the settings write permission.';
  }
  if (pattern.trim().length === 0) return 'Enter a subscription or pattern first.';
  return invalid ? 'Correct the highlighted fields first.' : null;
}

/** What will be kept, and for how long — stated on the form, because the operator cannot consent to what they were never told. */
function StoresBodiesNotice({
  pattern,
  retention,
  capture,
}: Readonly<{ pattern: string; retention: number; capture: boolean }>) {
  return (
    <Notice tone="warning" title="This stores message bodies">
      <Text size="sm">
        Rahdar will keep a copy of every message it observes on {pattern.trim() || 'these subscriptions'} — headers,
        application properties and the body — in its own database for {retention} day
        {retention === 1 ? '' : 's'}, and then delete it. That copy is searchable by anyone who can read messages on
        this cluster.{' '}
        {capture
          ? 'Capture records everything the destination routed, up to its bounds; anything past them is counted as missed, never silently dropped.'
          : 'Sampling records what was seen, not everything that passed through.'}{' '}
        Sensitive values are stored masked and credentials are never stored; the originals of other masked values are
        sealed, and only users with <code>message:clear</code> can see them.
      </Text>
    </Notice>
  );
}

/** Sampling starts at once; capture is previewed first, then armed by typing the pattern. */
function SubmitControls({
  capture,
  preview,
  body,
  blocked,
  canCapture,
  creating,
  previewing,
  onEdit,
  onStart,
  onPreview,
}: Readonly<{
  capture: boolean;
  preview: SqlCapturePreviewView | null;
  body: SqlIndexSubscriptionRequest;
  blocked: string | null;
  canCapture: boolean;
  creating: boolean;
  previewing: boolean;
  onEdit: () => void;
  onStart: () => void;
  onPreview: () => void;
}>) {
  if (capture && preview) {
    return (
      <Stack gap="xs">
        <CapturePreview preview={preview} />
        {preview.refusal ? null : (
          <HoldToConfirm
            label={`Start capturing ${body.subscriptionPattern ?? ''}`.trim()}
            loading={creating}
            disabled={!canCapture}
            onConfirm={onStart}
          />
        )}
        <Group>
          <Button size="compact-xs" variant="subtle" disabled={creating} onClick={onEdit}>
            Edit
          </Button>
        </Group>
      </Stack>
    );
  }
  return (
    <Group>
      <Button
        size="xs"
        disabled={blocked !== null}
        loading={capture ? previewing : creating}
        onClick={capture ? onPreview : onStart}
      >
        {capture ? 'Preview capture' : 'Start sampling'}
      </Button>
      {blocked ? (
        <Text size="xs" c="dimmed">
          {blocked}
        </Text>
      ) : null}
    </Group>
  );
}

/**
 * The form that starts storing message payload.
 *
 * <p>What will be kept, and for how long, is stated on the form itself rather than
 * in documentation. An operator cannot consent to storing bodies they were never
 * told were being stored, and this is the last screen before it starts.
 *
 * <p>Capture is armed from its dry run: the form freezes on what was previewed and
 * the pattern is typed to confirm, so what the operator agreed to is what is created.
 */
function CreateSubscription({
  clusterId,
  canWrite,
  canCapture,
}: Readonly<{
  clusterId: string;
  canWrite: boolean;
  canCapture: boolean;
}>) {
  const create = useCreateIndexSubscription(clusterId);
  const previewCapture = usePreviewIndexSubscription(clusterId);
  const [pattern, setPattern] = useState('');
  const [retentionDays, setRetentionDays] = useState<number | string>(7);
  const [intervalMs, setIntervalMs] = useState<number | string>(5000);
  const [mode, setMode] = useState<'SAMPLE' | 'CAPTURE'>('SAMPLE');
  const [filterString, setFilterString] = useState('');
  const [showBounds, setShowBounds] = useState(false);
  const [bounds, setBounds] = useState<Record<BoundField, number | string>>({
    ringSize: '',
    maxMegabytes: '',
    maxRate: '',
    bodyCapKilobytes: '',
  });
  const [errors, setErrors] = useState<Partial<Record<string, string | null>>>({});
  const [preview, setPreview] = useState<SqlCapturePreviewView | null>(null);

  const frozen = preview !== null;
  const capture = mode === 'CAPTURE';
  const retention = Number(retentionDays) || 7;
  const permitted = capture ? canCapture : canWrite;
  const invalid = Object.values(errors).some(Boolean);

  const check = (field: string, error: string | null) => setErrors((prev) => ({ ...prev, [field]: error }));

  const body = subscriptionBody({ pattern, retention, intervalMs, mode, filterString, bounds });

  const start = () =>
    create.mutate(body, {
      onSuccess: () => {
        notify.succeeded({
          action: capture ? CAPTURE : SAMPLE,
          subject: capture
            ? `${body.subscriptionPattern} — the tap is installed on the next pass`
            : `${body.subscriptionPattern}`,
        });
        setPattern('');
        setPreview(null);
      },
    });

  const blocked = blockedReason(permitted, capture, pattern, invalid);

  return (
    <Stack gap="xs" maw={520}>
      <TextInput
        label="Subscription or pattern"
        description="Artemis wildcards: * is one level, # is many. ORDER.# captures every ORDER subscription."
        placeholder="ORDER.IN"
        value={pattern}
        readOnly={frozen}
        onChange={(e) => setPattern(e.currentTarget.value)}
        size="xs"
      />
      <SegmentedControl
        size="xs"
        value={mode}
        readOnly={frozen}
        onChange={(v) => setMode(v as 'SAMPLE' | 'CAPTURE')}
        data={[
          { value: 'SAMPLE', label: 'Sample' },
          { value: 'CAPTURE', label: 'Capture everything' },
        ]}
      />
      <Text size="xs" c="dimmed">
        {mode === 'SAMPLE'
          ? 'Just sampling: Rahdar polls these subscriptions and records what it saw. A message that arrives and is consumed between two polls is never recorded.'
          : "Rahdar installs a divert-fed tap on every live node and records everything the destination routed, whether or not anything consumed it. It changes the broker's routing configuration."}
      </Text>
      {capture ? (
        <TextInput
          label="Capture filter (optional)"
          description="An Artemis filter expression. Narrows both what the broker copies and what Rahdar stores."
          placeholder="tenant = 'acme'"
          value={filterString}
          readOnly={frozen}
          onChange={(e) => setFilterString(e.currentTarget.value)}
          size="xs"
        />
      ) : null}
      <Group grow>
        <NumberInput
          label="Keep for (days)"
          min={1}
          max={90}
          value={retentionDays}
          readOnly={frozen}
          error={errors.retentionDays}
          onChange={setRetentionDays}
          onBlur={() =>
            check('retentionDays', rangeError({ label: '', description: '', min: 1, max: 90 }, retentionDays))
          }
          size="xs"
        />
        <NumberInput
          label="Read every (ms)"
          min={1000}
          max={3_600_000}
          step={1000}
          value={intervalMs}
          readOnly={frozen}
          error={errors.intervalMs}
          onChange={setIntervalMs}
          onBlur={() =>
            check('intervalMs', rangeError({ label: '', description: '', min: 1000, max: 3_600_000 }, intervalMs))
          }
          size="xs"
        />
      </Group>
      {capture ? (
        <>
          <CaptureBounds
            open={showBounds}
            onToggle={() => setShowBounds((open) => !open)}
            bounds={bounds}
            errors={errors}
            frozen={frozen}
            onChange={(field, v) => setBounds((prev) => ({ ...prev, [field]: v }))}
            onBlur={(field) => check(field, rangeError(LIMITS[field], bounds[field]))}
          />
          <CaptureBlastRadius pattern={pattern} retentionDays={retention} />
        </>
      ) : null}
      <StoresBodiesNotice pattern={pattern} retention={retention} capture={capture} />

      {create.isError ? <ErrorState variant="inline" error={create.error} /> : null}
      {previewCapture.isError ? <ErrorState variant="inline" error={previewCapture.error} /> : null}

      <SubmitControls
        capture={capture}
        preview={preview}
        body={body}
        blocked={blocked}
        canCapture={canCapture}
        creating={create.isPending}
        previewing={previewCapture.isPending}
        onEdit={() => setPreview(null)}
        onStart={start}
        onPreview={() => previewCapture.mutate(body, { onSuccess: setPreview })}
      />
    </Stack>
  );
}

/**
 * Index subscriptions for this cluster (ADR-0059). The index is opt-in, per subscription,
 * retention-bounded and disposable, and this screen is where all four of those are
 * true or not.
 */
export function IndexSubscriptions() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const subscriptions = useIndexSubscriptions(clusterId);
  const { can, loading } = useCan();
  // While grants are still loading the control is offered: refusing before the
  // answer has arrived is a claim that was never checked.
  const canWrite = loading || can('settings:write', clusterId);
  // Capture mutates broker routing, so it is a different authority from changing how
  // often Rahdar polls. Offered while grants are still loading, like everything else.
  const canCapture = loading || can('capture:write', clusterId);
  const columns = useMemo(
    () => subscriptionColumns({ clusterId, canWrite, canCapture }),
    [clusterId, canWrite, canCapture],
  );

  if (subscriptions.isError) {
    return <ErrorState error={subscriptions.error} onRetry={() => void subscriptions.refetch()} />;
  }
  if (subscriptions.isPending) {
    return <LoadingState label="Loading index subscriptions" blockSize="8rem" />;
  }

  return (
    <Stack gap="lg">
      <Section title="Subscriptions" headingLevel={3}>
        <DataTable
          variant="static"
          label="Index subscriptions"
          storageKey="sql.index"
          columns={columns}
          data={subscriptions.data}
          rowKey={(subscription) => subscription.id ?? ''}
          empty={
            <EmptyState
              kind="empty"
              title="Nothing is being indexed"
              description="No subscription on this cluster is captured, so the SQL Console answers every query from the live brokers and cannot find a message that has already been consumed. Index a subscription below to change that — it stores message payload, so it is a deliberate choice rather than a default."
            />
          }
        />
      </Section>

      <Section title="Index a subscription" headingLevel={3}>
        <CreateSubscription clusterId={clusterId} canWrite={canWrite} canCapture={canCapture} />
      </Section>
    </Stack>
  );
}
