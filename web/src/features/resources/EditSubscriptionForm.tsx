import { useCallback, useEffect, useState } from 'react';
import { Button, Group, Modal, NumberInput, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import {
  destinationOf,
  routingTypeOf,
  useSubscriptionConfiguration,
  useUpdateSubscription,
  type LifecycleOutcomeView,
  type SubscriptionConfiguration,
  type SubscriptionView,
  type UpdateResourceRequest,
} from './api.ts';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid, serverFieldErrors } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';

/**
 * The configuration the nodes agree on, or the first node's when they differ —
 * the form says so above the fields, because seeding from one node silently is
 * how an operator applies one node's value to all of them without knowing.
 */
function currentValues(config: SubscriptionConfiguration | undefined): Record<string, unknown> | undefined {
  return config?.nodes?.find((n) => !n.unavailableReason)?.values;
}

/** A configuration value as the text input wants it. */
function str(value: unknown): string {
  if (value === null || value === undefined) return '';
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : JSON.stringify(value);
}

/** A configuration value as the number input wants it. */
function num(value: unknown): number | '' {
  return typeof value === 'number' ? value : '';
}

interface FormState {
  filter: string;
  maxConsumers: number | string;
  purgeOnNoConsumers: boolean;
  exclusive: boolean;
  ringSize: number | string;
}

const EMPTY: FormState = { filter: '', maxConsumers: '', purgeOnNoConsumers: false, exclusive: false, ringSize: '' };

const FIELDS = Object.keys(EMPTY);

/**
 * Change the configuration of a live subscription.
 *
 * <p>The form opens on what the subscription runs now, read from the nodes that have it,
 * and sends only the fields the operator changed. It used to open blank under
 * "leave blank to keep the current value", which could not say what the subscription ran
 * and — reopened after an update — could not say whether the update had taken.
 *
 * <p>Only the fields the broker will actually accept are offered. Routing type,
 * destination, name and durability are fixed for the life of the subscription and are shown
 * as such with the reason — presented as *immutable*, which is a different thing
 * from disabled: one can never change, the other is merely unavailable right now,
 * and an operator who cannot tell them apart will keep looking for the way to
 * enable it.
 *
 * <p>The filter <em>is</em> editable. That was verified against a live broker
 * rather than assumed; see ADR-0049 D7.
 */
export function EditSubscriptionForm({
  clusterId,
  subscription,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  subscription: SubscriptionView;
  opened: boolean;
  onClose: () => void;
}>) {
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  // Stable, so the form's `setValues` is too and the seeding effect below runs only when the
  // broker's configuration changes.
  const clearPreview = useCallback(() => setPreview(null), []);
  const form = useForm<FormState>({
    initialValues: EMPTY,
    validateInputOnBlur: true,
    // What was previewed no longer describes the form once a field changes.
    onValuesChange: clearPreview,
  });
  const { setValues, resetDirty } = form;

  const update = useUpdateSubscription(clusterId, subscription.name);
  // What the subscription runs right now, per node. The form is seeded from it, so the
  // operator edits the real configuration rather than guessing at blank inputs —
  // and after an update the reopened form shows what was written.
  const configuration = useSubscriptionConfiguration(clusterId, subscription.name, opened);
  const current = currentValues(configuration.data);
  const seeded = configuration.data?.nodes?.find((n) => !n.unavailableReason);
  const seededFrom = seeded?.nodeName ?? '';
  // Every other node whose configuration differs from the one the form shows.
  const disagreeing = (configuration.data?.nodes ?? [])
    .filter((n) => !n.unavailableReason && n.nodeId !== seeded?.nodeId)
    .filter((n) => JSON.stringify(n.values) !== JSON.stringify(seeded?.values))
    .map((n) => n.nodeName);

  useEffect(() => {
    if (!current) return;
    const seed: FormState = {
      filter: str(current['filter-string']),
      maxConsumers: num(current['max-consumers']),
      purgeOnNoConsumers: current['purge-on-no-consumers'] === true,
      exclusive: current['exclusive'] === true,
      ringSize: num(current['ring-size']),
    };
    setValues(seed);
    resetDirty(seed);
    // Seeding is keyed by the values themselves, so an applied update reseeds the
    // form from what the broker now reports rather than from the operator's typing.
  }, [configuration.dataUpdatedAt, current, setValues, resetDirty]);

  // Only what the operator actually changed is sent. The server reads the subscription's
  // current configuration and merges this over it, because the broker's update
  // replaces rather than merges and would otherwise clear every omitted field.
  const changes = ({ filter, maxConsumers, purgeOnNoConsumers, exclusive, ringSize }: FormState) => ({
    filter: current && str(current['filter-string']) === filter.trim() ? undefined : filter.trim(),
    maxConsumers:
      maxConsumers === '' || (current && num(current['max-consumers']) === maxConsumers)
        ? undefined
        : Number(maxConsumers),
    purgeOnNoConsumers:
      current && (current['purge-on-no-consumers'] === true) === purgeOnNoConsumers ? undefined : purgeOnNoConsumers,
    exclusive: current && (current['exclusive'] === true) === exclusive ? undefined : exclusive,
    nonDestructive: undefined,
    ringSize: ringSize === '' || (current && num(current['ring-size']) === ringSize) ? undefined : Number(ringSize),
  });
  const body = (values: FormState): UpdateResourceRequest => ({ attributes: changes(values) });

  const changed = Object.values(changes(form.values)).some((v) => v !== undefined);

  const close = () => {
    form.reset();
    setPreview(null);
    setResult(null);
    update.reset();
    onClose();
  };

  const submit = (dryRun: boolean) =>
    form.onSubmit(
      (values) =>
        update.mutate(
          { body: body(values), dryRun },
          {
            onSuccess: (o) => (o.dryRun ? setPreview(o) : setResult(o)),
            onError: (error) => form.setErrors(serverFieldErrors(error, FIELDS)),
          },
        ),
      focusFirstInvalid(form.getInputNode),
    );

  return (
    <Modal opened={opened} onClose={close} title={`Edit ${subscription.name}`} size="lg">
      <form noValidate onSubmit={submit(false)}>
        <Stack gap="md">
          {/* Immutable, not disabled — stated as facts about the subscription rather than
            as inputs that happen to be switched off. */}
          <DescriptionList
            label="Fixed for the life of the subscription"
            items={[
              { term: 'Destination', value: destinationOf(subscription) },
              { term: 'Routing type', value: routingTypeOf(subscription) },
              { term: 'Durable', value: subscription.durable ? 'yes' : 'no' },
            ]}
          />
          <Text size="xs" c="dimmed">
            These are fixed for the life of the subscription — the broker refuses to change them on a subscription that
            exists. To change one, delete this subscription and create a new one.
          </Text>

          {configuration.isError ? (
            <>
              <ErrorState error={configuration.error} variant="inline" onRetry={() => void configuration.refetch()} />
              <Text size="sm">The fields below start empty; applying one writes it to every live node.</Text>
            </>
          ) : null}
          {disagreeing.length > 0 ? (
            <Notice tone="warning" title="The nodes do not agree">
              {disagreeing.join(', ')} {disagreeing.length === 1 ? 'runs' : 'run'} a different configuration from{' '}
              {seededFrom}. The fields below show {seededFrom}; applying writes them to every live node.
            </Notice>
          ) : null}

          {configuration.isPending ? (
            <LoadingState label="Loading what this subscription runs" blockSize="18rem" />
          ) : (
            <>
              <TextInput
                label="Filter"
                description="A JMS selector. Empty means no filter."
                placeholder="colour = 'red'"
                {...form.getInputProps('filter')}
              />
              <NumberInput
                label="Max consumers"
                description="-1 for unlimited."
                {...form.getInputProps('maxConsumers')}
                allowDecimal={false}
                min={-1}
              />
              <NumberInput
                label="Ring size"
                description="-1 for unlimited."
                {...form.getInputProps('ringSize')}
                allowDecimal={false}
                min={-1}
              />
              <Switch
                label="Purge when the last consumer disconnects"
                {...form.getInputProps('purgeOnNoConsumers', { type: 'checkbox' })}
              />
              <Switch
                label="Exclusive"
                description="Route to one consumer at a time."
                {...form.getInputProps('exclusive', { type: 'checkbox' })}
              />
            </>
          )}

          {update.isError ? <ErrorState error={update.error} variant="inline" /> : null}

          <div aria-live="polite">
            {preview ? <NodeOutcomeSummary outcome={preview} /> : null}
            {result ? <NodeOutcomeSummary outcome={result} /> : null}
          </div>

          <Group justify="space-between">
            <Text size="xs" c="dimmed">
              {changed ? '' : 'Change at least one field to apply an update.'}
            </Text>
            <Group gap="xs">
              <Button variant="default" size="xs" onClick={close}>
                {result ? 'Close' : 'Cancel'}
              </Button>
              <Button
                variant="default"
                size="xs"
                loading={update.isPending && update.variables?.dryRun === true}
                disabled={!changed}
                onClick={() => submit(true)()}
              >
                Preview
              </Button>
              <Button
                type="submit"
                size="xs"
                loading={update.isPending && update.variables?.dryRun !== true}
                disabled={!changed || result !== null}
              >
                Apply
              </Button>
            </Group>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
