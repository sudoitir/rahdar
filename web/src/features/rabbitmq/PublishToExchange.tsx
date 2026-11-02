import { ActionIcon, Button, Fieldset, Group, Modal, Stack, Switch, Text, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { IconLink, IconPlus, IconSend, IconTrash } from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import type { ActionProps, DestinationTarget } from '../../kernel/actions/types.ts';
import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { isNotRouted, usePublish } from './api.ts';
import { RABBITMQ_EXCHANGE } from './words.ts';

const SEND: ActionVerb = { verb: 'Publish', past: 'Published', progressive: 'Publishing' };

interface PublishForm {
  routingKey: string;
  body: string;
  headers: { id: string; k: string; v: string }[];
}

/** The vhost and name of an exchange id such as `rabbitmq.exchange/orders/events`. */
function exchangeParts(target: DestinationTarget): { vhost: string; name: string } {
  const path = target.id.slice(target.id.indexOf('/') + 1);
  const slash = path.indexOf('/');
  return slash < 0 ? { vhost: '', name: target.name } : { vhost: path.slice(0, slash), name: path.slice(slash + 1) };
}

/** The dialog that publishes one text message to an exchange, with the routing key that selects its queues. */
export function PublishDialog({
  clusterId,
  exchange,
  opened,
  onClose,
}: Readonly<{ clusterId: string; exchange: DestinationTarget; opened: boolean; onClose: () => void }>) {
  const send = usePublish(clusterId, exchange.id);
  const parts = exchangeParts(exchange);
  const form = useForm<PublishForm>({
    initialValues: { routingKey: '', body: '', headers: [] },
    validateInputOnBlur: true,
    validate: {
      headers: {
        k: (v) => (v.trim() ? null : 'Name the header, or remove the row.'),
      },
    },
  });
  const submit = form.onSubmit(({ routingKey, body, headers }) => {
    const key = routingKey.trim();
    send.mutate(
      {
        kind: 'TEXT',
        durable: true,
        body,
        headers: Object.fromEntries(headers.map((h) => [h.k.trim(), h.v])),
        properties: {},
        ...(key ? { routingKey: key } : {}),
      },
      {
        onSuccess: (result) => {
          const subject = `a message to exchange "${exchange.name}"`;
          onClose();
          if ('inDoubt' in result) {
            notify.inDoubt({ action: SEND, subject, detail: result.detail });
            return;
          }
          notify.succeeded({ action: SEND, subject });
        },
        onError: (error) => {
          // Not routed stays in the dialog, beside the routing key it names, so the key can be corrected.
          if (isNotRouted(error)) return;
          notify.settle(error, {
            action: SEND,
            subject: `a message to exchange "${exchange.name}"`,
            cause: error.message,
            next: 'Check the exchange and try again.',
          });
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={`Publish to ${exchange.name}`}
      size="lg"
      closeOnEscape={!send.isPending}
      closeOnClickOutside={!send.isPending}
    >
      <form onSubmit={submit} noValidate>
        <Stack gap="sm">
          <TextInput
            label="Routing key"
            description="Which bindings of the exchange match it decides which queues receive the message."
            data-autofocus
            {...form.getInputProps('routingKey')}
          />
          <Textarea
            label="Body"
            description="The text of the message."
            autosize
            minRows={4}
            {...form.getInputProps('body')}
          />
          {isNotRouted(send.error) ? (
            <Notice tone="danger" title="No queue received the message">
              <Text size="sm">
                {`Exchange ${exchange.name} has no binding that matches routing key "${form.values.routingKey.trim()}". Nothing was stored. `}
                <Link
                  to={`/clusters/${clusterId}/rabbitmq/bindings`}
                  search={{ vhost: parts.vhost, source: parts.name }}
                >
                  Open its bindings
                </Link>
              </Text>
            </Notice>
          ) : null}
          <Fieldset legend="Headers">
            <Stack gap="xs">
              {form.values.headers.length === 0 ? (
                <Text size="sm" c="dimmed">
                  No headers. A header is a named value a headers exchange can route on.
                </Text>
              ) : null}
              {form.values.headers.map((h, i) => (
                <FieldRow key={h.id}>
                  <TextInput label="Name" size="xs" {...form.getInputProps(`headers.${i}.k`)} />
                  <TextInput label="Value" size="xs" {...form.getInputProps(`headers.${i}.v`)} />
                  <ActionIcon
                    variant="subtle"
                    aria-label={`Remove header ${i + 1}`}
                    onClick={() => form.removeListItem('headers', i)}
                  >
                    <IconTrash size="1rem" aria-hidden />
                  </ActionIcon>
                </FieldRow>
              ))}
              <div>
                <Button
                  size="xs"
                  variant="default"
                  leftSection={<IconPlus size="1rem" aria-hidden />}
                  onClick={() => form.insertListItem('headers', { id: randomId(), k: '', v: '' })}
                >
                  Add header
                </Button>
              </div>
            </Stack>
          </Fieldset>
          <Switch label="Persistent" checked disabled description="The message is kept on disk until it is consumed." />
          <Group justify="flex-end">
            <Button variant="default" size="xs" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" size="xs" loading={send.isPending}>
              Publish
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** "Publish…" on an exchange's row. */
export function PublishToExchange({ clusterId, target, host }: Readonly<ActionProps<DestinationTarget>>) {
  const { hidden, verdict } = useResourceGate({
    clusterId,
    noun: 'destination',
    permission: 'message:send',
    label: 'Send messages',
    resource: target.snapshot,
    kind: { id: target.kind, verb: 'send' },
  });
  if (target.kind !== RABBITMQ_EXCHANGE || hidden) return null;
  return (
    <ActionMenuItem
      label="Publish…"
      icon={<IconSend size="1rem" aria-hidden />}
      verdict={verdict}
      onExplain={(v) => host.explain(v, 'publishing to this exchange')}
      onSelect={() => host.open(PublishDialog, { clusterId, exchange: target })}
    />
  );
}

/** "Bindings" on an exchange's row: the bindings that send its messages somewhere, filtered to it. */
export function ExchangeBindings({ clusterId, target }: Readonly<ActionProps<DestinationTarget>>) {
  const navigate = useNavigate();
  const { hidden, verdict } = useResourceGate({
    clusterId,
    noun: 'destination',
    permission: 'destination:read',
    label: 'Read bindings',
    resource: target.snapshot,
  });
  if (target.kind !== RABBITMQ_EXCHANGE || hidden) return null;
  const { vhost, name } = exchangeParts(target);
  const query = new URLSearchParams({ ...(vhost ? { vhost } : {}), source: name }).toString();
  return (
    <ActionMenuItem
      label="Bindings"
      icon={<IconLink size="1rem" aria-hidden />}
      verdict={verdict}
      href={clusterHref(clusterId, `rabbitmq/bindings?${query}`)}
      onSelect={() =>
        navigate({
          to: `/clusters/${clusterId}/rabbitmq/bindings`,
          search: { vhost: vhost || undefined, source: name },
        })
      }
    />
  );
}
