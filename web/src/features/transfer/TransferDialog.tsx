import { useState } from 'react';
import { Button, Checkbox, Code, Group, Modal, SegmentedControl, Select, Stack, Text } from '@mantine/core';
import { useForm, type UseFormReturnType } from '@mantine/form';
import { useNavigate } from '@tanstack/react-router';

import { useCluster, useClusters, useRoleIds } from '../clusters/index.ts';
import { DestinationPicker } from '../resources/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { nameOf } from '../../kernel/api/resourceId.ts';
import type { MessageSelection } from '../../kernel/slots.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { HoldToConfirm } from '../../ui/HoldToConfirm.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import {
  useTransferExecute,
  useTransferPreview,
  type Finding,
  type TransferMode,
  type TransferRunView,
} from './api.ts';
import { endpointsOf, nodeOptions, serving } from './nodes.ts';
import { bytes, MODE, plural } from './words.ts';

const PERMISSION: Record<TransferMode, { id: string; label: string }> = {
  MOVE: { id: 'message:move', label: 'Move or retry messages' },
  COPY: { id: 'message:read', label: 'Browse messages' },
};

function selectionWords(selection: MessageSelection, subscriptionName: string, total: number | null): string {
  switch (selection.kind) {
    case 'ids':
      return `the ${plural(selection.ids.length, 'selected message')} of ${subscriptionName}`;
    case 'filter':
      return `every message of ${subscriptionName} matching ${selection.filter}`;
    case 'all':
      return total == null
        ? `every message of ${subscriptionName}`
        : `all ${plural(total, 'message')} of ${subscriptionName}`;
  }
}

type Gate = ReturnType<typeof gateFor>;

/** The first gate that blocks, in order: the source's, then the target's. */
function firstBlocked(source: Gate, target: Gate): Gate | null {
  if (source.kind === 'blocked') return source;
  return target.kind === 'blocked' ? target : null;
}

const isUncertain = (gate: Gate) => gate.kind === 'allowed' && gate.uncertain;

/** What the destination step asks for. */
interface Destination {
  mode: TransferMode;
  targetClusterId: string;
  targetNodeId: string | null;
  targetSubscription: string;
}

/** The selection as the API takes it: ids, a filter, or the whole subscription. */
function selectionRequest(selection: MessageSelection) {
  if (selection.kind === 'ids') return { kind: 'IDS' as const, ids: selection.ids, filter: null };
  if (selection.kind === 'filter') return { kind: 'FILTER' as const, ids: null, filter: selection.filter };
  return { kind: 'ALL' as const, ids: null, filter: null };
}

/** The action the confirm button names: the verb and how many messages it covers. */
function confirmWords(run: TransferRunView): string {
  return `${MODE[run.mode].verb} ${run.estimate == null ? 'messages' : plural(run.estimate, 'message')}`;
}

/** What the start toast names: "the move of 1,200 messages". */
function startedSubject(run: TransferRunView): string {
  const what = run.estimate == null ? 'the selected messages' : plural(run.estimate, 'message');
  return `the ${MODE[run.mode].verb.toLowerCase()} of ${what}`;
}

/** Whether the target can accept the transfer, in one sentence. */
function acceptanceWords(refused: boolean, warnings: number): string {
  if (refused) return 'The target cannot accept this transfer. The reasons are below; nothing will run.';
  if (warnings > 0) return `The target can accept it, with ${plural(warnings, 'warning')} to acknowledge.`;
  return 'The target can accept it.';
}

/** What the run would do, as one sentence an operator can check before arming it. */
function blastRadius(run: TransferRunView, clusterName: (id: string) => string): string {
  const { source, target } = run;
  const what =
    run.estimate == null
      ? 'the selected messages (how many is not known until the run counts them)'
      : `${plural(run.estimate, 'message')} (${bytes(run.estimateBytes)})`;
  const where =
    `from ${nameOf(source.subscription)} on ${source.nodeName} (${clusterName(source.clusterId)}) ` +
    `to ${nameOf(target.subscription)} on ${target.nodeName} (${clusterName(target.clusterId)})`;
  return run.mode === 'MOVE'
    ? `Move ${what} ${where}. They leave the source subscription.`
    : `Copy ${what} ${where}. The source subscription is unchanged.`;
}

const START = { verb: 'Start', past: 'Started', progressive: 'Starting' } as const;

const GROUPS: { kind: Finding['kind']; title: string }[] = [
  { kind: 'REFUSE', title: 'Refused' },
  { kind: 'WARN', title: 'Needs your acknowledgement' },
  { kind: 'UNKNOWN', title: 'Could not be checked, so not counted as passing' },
];

function Snippet({ snippet }: Readonly<{ snippet?: string | null }>) {
  if (!snippet) return null;
  return (
    <>
      <Text size="xs" fw={600}>
        Add this to <Code>broker.xml</Code>:
      </Text>
      <Code block>{snippet}</Code>
    </>
  );
}

/** The first step: where the messages go — mode, target cluster, node and subscription — before anything is previewed. */
function DestinationForm({
  subscriptionName,
  redistribute,
  form,
  clusterOptions,
  targetPending,
  targetNodes,
  chosenNode,
  noSourceNode,
  uncertain,
  blocked,
  canPreview,
  previewing,
  onCancel,
  onPreview,
}: Readonly<{
  subscriptionName: string;
  redistribute: boolean;
  form: UseFormReturnType<Destination>;
  clusterOptions: { value: string; label: string; disabled: boolean }[];
  targetPending: boolean;
  targetNodes: ReturnType<typeof nodeOptions>;
  chosenNode: string | null;
  noSourceNode: boolean;
  uncertain: boolean;
  blocked: Gate | null;
  canPreview: boolean;
  previewing: boolean;
  onCancel: () => void;
  onPreview: () => void;
}>) {
  const { targetClusterId } = form.values;
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        onPreview();
      }}
    >
      <Stack gap="sm">
        {noSourceNode ? (
          <EmptyState
            kind="empty"
            title="No source node to read from"
            description="No node of this cluster is live and managed by Rahdar now, so there is nothing to transfer from. Wait for a node to come back, then open this again."
          />
        ) : null}
        {redistribute ? (
          <Text size="sm">
            The messages move to {subscriptionName} on the node you choose, and land on that node&rsquo;s own
            subscription.
          </Text>
        ) : (
          <>
            <Stack gap="xs">
              <Text size="sm" fw={500} id="transfer-mode">
                Mode
              </Text>
              <SegmentedControl
                aria-labelledby="transfer-mode"
                {...form.getInputProps('mode')}
                data={[
                  { value: 'MOVE', label: 'Move: the messages leave the source' },
                  { value: 'COPY', label: 'Copy: the source is unchanged' },
                ]}
              />
            </Stack>
            <Select
              label="Target cluster"
              description="A cluster you may not send to is listed, and says so."
              data={clusterOptions}
              {...form.getInputProps('targetClusterId')}
              allowDeselect={false}
              onChange={(v) => {
                if (!v) return;
                form.setFieldValue('targetClusterId', v);
                form.setFieldValue('targetNodeId', null);
              }}
            />
          </>
        )}
        <Select
          label="Target node"
          description="Every node is listed; one that cannot take messages says why."
          placeholder={targetPending ? 'Reading the cluster’s nodes…' : 'Choose a node'}
          data={targetNodes}
          {...form.getInputProps('targetNodeId')}
          value={chosenNode}
          nothingFoundMessage="This cluster has no nodes Rahdar knows of."
        />
        {redistribute ? null : (
          <DestinationPicker
            clusterId={targetClusterId}
            label="Target subscription"
            permission="message:send"
            description="The subscription on the target node. A subscription that does not exist is checked in the preview."
            {...form.getInputProps('targetSubscription')}
            value={form.values.targetSubscription}
            unknownHint="No subscription by that name on this cluster yet. The preview says whether the broker would create it."
          />
        )}
        {uncertain && !blocked ? (
          <Text size="sm">
            Whether these brokers allow Rahdar&rsquo;s management operations has not been established yet. The preview
            is offered anyway, and states what it could not check.
          </Text>
        ) : null}
        <Group justify="flex-end">
          <Button variant="default" size="xs" onClick={onCancel}>
            Cancel
          </Button>
          <CapabilityGate verdict={blocked ?? { kind: 'allowed', uncertain: false }} what="previewing this transfer">
            <Button type="submit" size="xs" loading={previewing} disabled={blocked !== null || !canPreview}>
              Preview
            </Button>
          </CapabilityGate>
        </Group>
      </Stack>
    </form>
  );
}

/** The run's estimate against the safety cap, in words. */
function overCapWords(run: TransferRunView): string {
  if (run.estimate == null) {
    return `How many messages this selects is not known, so it cannot be held to the safety cap of ${run.cap.toLocaleString()}.`;
  }
  return `This selects ${run.estimate.toLocaleString()} messages, over the safety cap of ${run.cap.toLocaleString()}.`;
}

/** What the acceptance checks found, what must be acknowledged, and the control that arms the transfer. */
function FindingsPanel({
  data,
  findings,
  refused,
  unacked,
  typed,
  confirmLabel,
  acked,
  onAck,
  execute,
  onPreviewAgain,
  onStart,
  onChangeDestination,
}: Readonly<{
  data: TransferRunView;
  findings: Finding[];
  refused: boolean;
  unacked: number;
  typed: boolean;
  confirmLabel: string;
  acked: Set<string>;
  onAck: (code: string, on: boolean) => void;
  execute: ReturnType<typeof useTransferExecute>;
  onPreviewAgain: () => void;
  onStart: () => void;
  onChangeDestination: () => void;
}>) {
  return (
    <Stack gap="md">
      {GROUPS.map((group) => {
        const rows = findings.filter((f) => f.kind === group.kind);
        if (rows.length === 0) return null;
        return (
          <Section key={group.kind} title={group.title} headingLevel={3}>
            {rows.map((f) => (
              <Stack key={f.code} gap="xs">
                {f.kind === 'WARN' ? (
                  <Checkbox
                    label={f.words}
                    checked={acked.has(f.code)}
                    onChange={(e) => onAck(f.code, e.currentTarget.checked)}
                  />
                ) : (
                  <Text size="sm">{f.words}</Text>
                )}
                <Snippet snippet={f.snippet} />
              </Stack>
            ))}
          </Section>
        );
      })}

      {data.notes.length > 0 ? (
        <Section title="Good to know" headingLevel={3}>
          {data.notes.map((n) => (
            <Text key={n} size="sm">
              {n}
            </Text>
          ))}
        </Section>
      ) : null}

      {!refused && data.overCap ? (
        <Section title="Over the safety cap" headingLevel={3} variant="card">
          <Text size="sm">
            {overCapWords(data)} Typing the subscription name below overrides the cap for this run, and the override is
            recorded in the audit log.
          </Text>
        </Section>
      ) : null}

      {execute.isError ? (
        <ErrorState
          error={execute.error}
          next="Nothing was run. Preview again to confirm the transfer as it is now."
          actions={
            <Button size="xs" variant="default" onClick={onPreviewAgain}>
              Preview again
            </Button>
          }
        />
      ) : null}

      {refused ? null : (
        <Stack gap="xs">
          {unacked > 0 ? (
            <Text size="sm">
              Acknowledge {unacked === 1 ? 'the warning' : `the ${unacked} warnings`} above to run this.
            </Text>
          ) : null}
          {typed ? (
            <HoldToConfirm
              label={confirmLabel}
              tone={data.mode === 'MOVE' ? 'danger' : 'default'}
              loading={execute.isPending}
              disabled={unacked > 0 || execute.isPending}
              onConfirm={onStart}
            />
          ) : (
            <Group>
              <Button size="xs" loading={execute.isPending} disabled={unacked > 0} onClick={onStart}>
                {confirmLabel}
              </Button>
            </Group>
          )}
        </Stack>
      )}

      <Group>
        <Button size="xs" variant="subtle" onClick={onChangeDestination}>
          Change the destination
        </Button>
      </Group>
    </Stack>
  );
}

/** A cluster's name from the list, else the fallback, else its id. */
function nameOfCluster(
  list: readonly { id: string; name: string }[] | undefined,
  id: string,
  fallback?: string,
): string {
  return list?.find((c) => c.id === id)?.name ?? fallback ?? id;
}

/** The clusters the operator may send to; one they may not is listed, disabled, with the reason. */
function sendOptions(
  list: readonly { id: string; name: string }[] | undefined,
  maySend: (clusterId: string) => boolean,
) {
  return (list ?? []).map((c) => {
    const allowed = maySend(c.id);
    return {
      value: c.id,
      label: allowed ? c.name : `${c.name}: you do not have the "Send messages" permission here`,
      disabled: !allowed,
    };
  });
}

/** What the preview, once taken, asks of the operator: its findings, the warnings left to acknowledge, how it is armed. */
function previewSummary(data: TransferRunView | undefined, acked: ReadonlySet<string>) {
  const findings = data?.findings ?? [];
  const warnings = findings.filter((f) => f.kind === 'WARN');
  return {
    findings,
    warnings,
    refused: findings.some((f) => f.kind === 'REFUSE'),
    unacked: warnings.filter((f) => !acked.has(f.code)).length,
    typed: data ? data.mode === 'MOVE' || data.overCap : false,
    confirmLabel: data ? confirmWords(data) : '',
  };
}

/** The acknowledged codes with one added or removed. */
function withAck(acked: ReadonlySet<string>, code: string, on: boolean): Set<string> {
  const next = new Set(acked);
  if (on) next.add(code);
  else next.delete(code);
  return next;
}

/**
 * Destination → preview → confirm for one message transfer (ADR-0097). Nothing touches a broker
 * until the frozen plan is confirmed: the preview states the blast radius and every acceptance
 * finding, a warning is acknowledged one by one, and a move, or anything over the safety cap, is
 * armed by typing the source subscription's name. Once the run is accepted the operator is taken to it.
 *
 * `redistribute` fixes the target to the same subscription in the same cluster, on another node.
 */
export function TransferDialog({
  clusterId,
  subscriptionName,
  node,
  selection,
  total,
  redistribute = false,
  opened,
  onClose,
  onStarted,
}: Readonly<{
  clusterId: string;
  subscriptionName: string;
  node?: string;
  selection: MessageSelection;
  total: number | null;
  redistribute?: boolean;
  opened: boolean;
  onClose: () => void;
  onStarted: () => void;
}>) {
  const navigate = useNavigate();
  const { canAnywhere, loading } = useCan();
  const clusters = useClusters();
  const source = useCluster(clusterId);
  const preview = useTransferPreview(clusterId);
  const execute = useTransferExecute(clusterId);

  const form = useForm<Destination>({
    initialValues: {
      mode: 'MOVE',
      targetClusterId: clusterId,
      targetNodeId: null,
      targetSubscription: subscriptionName,
    },
    validateInputOnBlur: true,
    validate: {
      // The node the operator sees may be the one chosen for them, so it is checked as shown (see `chosenNode`).
      targetNodeId: () => (chosenNode ? null : 'Choose the node the messages go to.'),
      targetSubscription: (v) => (redistribute || v.trim() ? null : 'Name the subscription the messages go to.'),
    },
  });
  const { mode, targetClusterId, targetNodeId } = form.values;
  const [acked, setAcked] = useState<Set<string>>(new Set());

  const target = useCluster(targetClusterId);
  const sourceIds = useRoleIds(clusterId);
  const targetIds = useRoleIds(redistribute ? clusterId : targetClusterId);
  const sourceEndpoints = endpointsOf(source.data?.topology);
  const sourceNode = sourceEndpoints.find((e) => e.id === node) ?? sourceEndpoints.find(serving);
  const clusterName = (id: string) => nameOfCluster(clusters.data, id, source.data?.name);

  const close = () => {
    preview.reset();
    execute.reset();
    form.reset();
    setAcked(new Set());
    onClose();
  };

  const effectiveMode: TransferMode = redistribute ? 'MOVE' : mode;
  const sourceGate = gateFor(
    canAnywhere(PERMISSION[effectiveMode].id, clusterId),
    PERMISSION[effectiveMode].label,
    effectiveMode === 'MOVE' ? source.data?.capabilities.managementWrite : source.data?.capabilities.managementRead,
    loading || source.isPending,
  );
  const targetGate = gateFor(
    canAnywhere('message:send', targetClusterId),
    'Send messages',
    target.data?.capabilities.managementRead,
    loading || target.isPending,
  );
  const blocked = firstBlocked(sourceGate, targetGate);
  const uncertain = isUncertain(sourceGate) || isUncertain(targetGate);

  const targetNodes = nodeOptions(
    endpointsOf(target.data?.topology),
    targetClusterId === clusterId && sourceNode ? { id: sourceNode.id, reason: 'the source node' } : undefined,
  );
  // A redistribution with one other node has nothing to choose; it is chosen for the operator, visibly.
  const choosable = targetNodes.filter((o) => !o.disabled);
  const chosenNode: string | null =
    targetNodeId ?? (redistribute && choosable.length === 1 ? choosable[0].value : null);

  const takePreview = form.onSubmit((values) => {
    if (!sourceNode || !chosenNode) return;
    execute.reset();
    setAcked(new Set());
    preview.mutate({
      mode: effectiveMode,
      source: sourceIds.subscription(subscriptionName),
      sourceNodeId: sourceNode.id,
      selection: selectionRequest(selection),
      targetClusterId: redistribute ? clusterId : values.targetClusterId,
      targetNodeId: chosenNode,
      target: targetIds.subscription(redistribute ? subscriptionName : values.targetSubscription.trim()),
    });
  }, focusFirstInvalid(form.getInputNode));

  const data = preview.data;
  const { findings, warnings, refused, unacked, typed, confirmLabel } = previewSummary(data, acked);

  const start = () => {
    if (!data) return;
    execute.mutate(
      {
        runId: data.id,
        body: {
          planHash: data.planHash,
          // Typing the subscription name is the override: it is only armed where the preview said so.
          override: data.overCap,
          acknowledged: warnings.map((f) => f.code),
          confirmSubscription: nameOf(data.source.subscription),
        },
      },
      {
        onSuccess: (run) => {
          close();
          notify.succeeded({ action: START, subject: startedSubject(run) });
          void navigate({ to: `/clusters/${clusterId}/transfers/${run.id}` });
          onStarted();
        },
      },
    );
  };

  const clusterOptions = sendOptions(clusters.data, (id) => loading || canAnywhere('message:send', id));

  const title = redistribute
    ? `Redistribute from ${subscriptionName} to another node`
    : `Transfer messages from ${subscriptionName}`;

  return (
    <Modal opened={opened} onClose={close} title={title} size="xl">
      <Stack gap="md">
        <Text size="sm">
          From {selectionWords(selection, subscriptionName, total)}
          {sourceNode ? ` on ${sourceNode.name}` : ''}.
        </Text>

        {!data ? (
          <DestinationForm
            subscriptionName={subscriptionName}
            redistribute={redistribute}
            form={form}
            clusterOptions={clusterOptions}
            targetPending={target.isPending}
            targetNodes={targetNodes}
            chosenNode={chosenNode}
            noSourceNode={!sourceNode && Boolean(source.data)}
            uncertain={uncertain}
            blocked={blocked}
            canPreview={sourceNode !== undefined}
            previewing={preview.isPending}
            onCancel={close}
            onPreview={() => takePreview()}
          />
        ) : null}

        <div aria-live="polite">
          {preview.isPending ? (
            <LoadingState label="Reading the source and checking the target" blockSize="6rem" />
          ) : null}
          {preview.isError ? (
            <ErrorState error={preview.error} next="Nothing was moved. Change the destination, or preview again." />
          ) : null}
          {data ? (
            <Stack gap="xs">
              <Text size="sm" fw={600}>
                {blastRadius(data, clusterName)}
              </Text>
              <Text size="sm">{acceptanceWords(refused, warnings.length)}</Text>
            </Stack>
          ) : null}
        </div>

        {data ? (
          <FindingsPanel
            data={data}
            findings={findings}
            refused={refused}
            unacked={unacked}
            typed={typed}
            confirmLabel={confirmLabel}
            acked={acked}
            onAck={(code, on) => setAcked((prev) => withAck(prev, code, on))}
            execute={execute}
            onPreviewAgain={() => takePreview()}
            onStart={start}
            onChangeDestination={() => {
              preview.reset();
              execute.reset();
            }}
          />
        ) : null}
      </Stack>
    </Modal>
  );
}
