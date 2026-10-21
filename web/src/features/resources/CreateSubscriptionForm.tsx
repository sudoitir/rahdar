import { useState } from 'react';
import { Button, Collapse, Group, Modal, NumberInput, Radio, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useCreateRule } from '../../kernel/auth/useCreateRule.ts';
import { kindOfRole } from '../../kernel/api/resourceId.ts';
import { useCluster, useRoleIds } from '../clusters/index.ts';
import { useCreateSubscription, type CreateSubscriptionRequest, type LifecycleOutcomeView } from './api.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid, serverFieldErrors } from '../../ui/formErrors.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { DestinationPicker } from './DestinationPicker.tsx';

/** Only the fields that identify the subscription are required; the rest have broker defaults. */
interface FormState {
  destination: string;
  name: string;
  routingType: 'ANYCAST' | 'MULTICAST';
  durable: boolean;
  filter: string;
  maxConsumers: number | string;
  purgeOnNoConsumers: boolean;
  exclusive: boolean;
  ringSize: number | string;
}

const EMPTY: FormState = {
  destination: '',
  name: '',
  routingType: 'ANYCAST',
  durable: true,
  filter: '',
  maxConsumers: '',
  purgeOnNoConsumers: false,
  exclusive: false,
  ringSize: '',
};

const FIELDS = Object.keys(EMPTY);
const ADDRESS_HELP =
  'Messages are sent to an destination; the subscription binds to it. An destination that does not exist yet is created with the subscription.';

/**
 * Create a subscription across every live node of a cluster.
 *
 * <p>Previews before it acts: the operator sees which nodes will be targeted, in
 * the same summary component the result is rendered with, so the two are
 * comparable. Creation is not destructive, so there is no typed confirmation —
 * the preview is the safety step.
 */
export function CreateSubscriptionForm({
  clusterId,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  opened: boolean;
  onClose: () => void;
}>) {
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [advanced, setAdvanced] = useState(false);

  const create = useCreateSubscription(clusterId);
  const kinds = useCluster(clusterId).data?.kinds;
  const ids = useRoleIds(clusterId);
  const subscriptionRule = useCreateRule(clusterId, 'subscription');
  const destinationRule = useCreateRule(clusterId, 'destination');
  const form = useForm<FormState>({
    initialValues: EMPTY,
    validateInputOnBlur: true,
    // The names are checked against the patterns the caller may create under as they are typed.
    validateInputOnChange: ['destination', 'name'],
    validate: {
      destination: (value) =>
        value.trim() ? destinationRule.problem(value) : 'An destination is required — a subscription binds to one.',
      name: (value) => (value.trim() ? subscriptionRule.problem(value) : 'A subscription name is required.'),
    },
    // What was previewed or created no longer describes the form once a field changes.
    onValuesChange: () => {
      setPreview(null);
      setResult(null);
    },
  });
  const hasErrors = Object.keys(form.errors).length > 0;

  const body = (values: FormState): CreateSubscriptionRequest => ({
    kind: kindOfRole(kinds, 'SUBSCRIPTION'),
    name: values.name.trim(),
    destination: ids.destination(values.destination.trim()),
    attributes: {
      routingType: values.routingType,
      durable: values.durable,
      filter: values.filter.trim() || undefined,
      maxConsumers: values.maxConsumers === '' ? undefined : Number(values.maxConsumers),
      purgeOnNoConsumers: values.purgeOnNoConsumers,
      exclusive: values.exclusive,
      ringSize: values.ringSize === '' ? undefined : Number(values.ringSize),
      autoCreateDestination: true,
    },
  });

  const submit = (dryRun: boolean) =>
    form.onSubmit(
      (values) =>
        create.mutate(
          { body: body(values), dryRun },
          {
            onSuccess: (outcome) => {
              if (outcome.dryRun) setPreview(outcome);
              else setResult(outcome);
            },
            onError: (error) => form.setErrors(serverFieldErrors(error, FIELDS)),
          },
        ),
      focusFirstInvalid(form.getInputNode),
    );

  const close = () => {
    form.reset();
    setPreview(null);
    setResult(null);
    setAdvanced(false);
    create.reset();
    onClose();
  };

  return (
    <Modal opened={opened} onClose={close} title="New subscription" size="lg">
      <form noValidate onSubmit={submit(false)}>
        <Stack gap="md">
          <DestinationPicker
            clusterId={clusterId}
            {...form.getInputProps('destination')}
            value={form.values.destination}
            label="Destination"
            description={[ADDRESS_HELP, destinationRule.restriction].filter(Boolean).join(' ')}
          />

          <TextInput
            label="Subscription name"
            description={subscriptionRule.restriction ?? undefined}
            {...form.getInputProps('name')}
            required
          />

          <Radio.Group
            label="Routing type"
            description="ANYCAST delivers each message to one consumer; MULTICAST delivers to every subscriber. This cannot be changed once the subscription exists."
            {...form.getInputProps('routingType')}
          >
            <Group gap="lg" mt="xs">
              <Radio value="ANYCAST" label="Anycast" />
              <Radio value="MULTICAST" label="Multicast" />
            </Group>
          </Radio.Group>

          <Switch
            label="Durable"
            description="A durable subscription and its messages survive a broker restart."
            {...form.getInputProps('durable', { type: 'checkbox' })}
          />

          <div>
            <Button variant="subtle" size="xs" px={0} onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
              {advanced ? 'Hide advanced configuration' : 'Advanced configuration'}
            </Button>
            <Collapse expanded={advanced}>
              <Stack gap="sm" mt="sm">
                <TextInput
                  label="Filter"
                  description="A JMS selector; only matching messages are accepted. Leave empty to accept all."
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
                  description="Retain at most this many messages. -1 for unlimited."
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
              </Stack>
            </Collapse>
          </div>

          {create.isError ? <ErrorState error={create.error} variant="inline" /> : null}

          {/* Preview and result share one live region, so a screen reader is told
            the outcome rather than only the sighted operator seeing it. */}
          <div aria-live="polite">
            {preview ? (
              <Stack gap="xs">
                <Text size="xs" c="dimmed">
                  Nothing has been created yet. This is what would happen:
                </Text>
                <NodeOutcomeSummary outcome={preview} />
              </Stack>
            ) : null}
            {result ? <NodeOutcomeSummary outcome={result} /> : null}
          </div>

          <Group justify="space-between">
            {/* Never silently disabled: it stays enabled and validates on activation,
              so the operator finds out what is wrong by trying. */}
            <Text size="xs" c="dimmed">
              {hasErrors ? 'Fix the fields above to continue.' : ''}
            </Text>
            <Group gap="xs">
              <Button variant="default" size="xs" onClick={close}>
                {result ? 'Close' : 'Cancel'}
              </Button>
              <Button
                variant="default"
                size="xs"
                loading={create.isPending && create.variables?.dryRun === true}
                onClick={() => submit(true)()}
              >
                Preview
              </Button>
              <Button
                type="submit"
                size="xs"
                loading={create.isPending && create.variables?.dryRun !== true}
                disabled={result !== null}
              >
                Create subscription
              </Button>
            </Group>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
