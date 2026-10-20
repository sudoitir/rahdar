import { ActionIcon, Button, Fieldset, Group, Modal, Stack, Switch, Text, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { IconPlus, IconTrash } from '@tabler/icons-react';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify } from '../../ui/notify.ts';
import { KindField } from '../../ui/KindField.tsx';
import { useSendMessage, type SendMessageRequest } from './api.ts';
import { announceFailure, VERBS } from './outcomes.ts';

/** A property row; the id keeps it identifiable while rows are added, edited and removed. */
type Pair = { id: string; k: string; v: string };

type MessageKind = SendMessageRequest['kind'];

interface FormState {
  kind: MessageKind;
  durable: boolean;
  body: string;
  props: Pair[];
}

const KINDS: { value: MessageKind; label: string }[] = [
  { value: 'TEXT', label: 'Text' },
  { value: 'BYTES', label: 'Bytes (base64)' },
];

/** Base64 as the broker's decoder reads it: whole 4-character groups, no whitespace. */
const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** What is wrong with the form, field by field, keyed by the field's path. An empty result means it can be sent. */
function check({ kind, body, props }: FormState): Record<string, string> {
  const errors: Record<string, string> = {};
  const seen = new Set<string>();
  props.forEach(({ k }, i) => {
    const key = k.trim();
    if (!key) errors[`props.${i}.k`] = 'Name the property, or remove the row.';
    else if (seen.has(key)) errors[`props.${i}.k`] = `Another property is already named "${key}".`;
    seen.add(key);
  });
  if (kind === 'BYTES' && !BASE64.test(body)) errors.body = 'Enter the body as base64, or switch the body to text.';
  return errors;
}

/** Enqueue one message, as text or as bytes given in base64. */
export function SendMessage({
  clusterId,
  subscriptionName,
  node,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  subscriptionName: string;
  node?: string;
  opened: boolean;
  onClose: () => void;
}>) {
  const send = useSendMessage(clusterId, subscriptionName);
  const form = useForm<FormState>({
    initialValues: { kind: 'TEXT', durable: true, body: '', props: [] },
    validateInputOnBlur: true,
    validate: check,
  });

  const submit = form.onSubmit(({ kind, durable, body, props }) => {
    const properties = Object.fromEntries(props.map((p) => [p.k.trim(), p.v]));
    send.mutate(
      { body: { kind, durable, body, headers: {}, properties }, node },
      {
        onSuccess: () => {
          notify.succeeded({ action: VERBS.send, subject: `a message to subscription "${subscriptionName}"` });
          form.setFieldValue('body', '');
          form.setFieldValue('props', []);
          form.clearErrors();
          onClose();
        },
        onError: (e) => announceFailure('send', `a message to subscription "${subscriptionName}"`, e),
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={`Send to ${subscriptionName}`}
      size="lg"
      closeOnEscape={!send.isPending}
      closeOnClickOutside={!send.isPending}
    >
      <form onSubmit={submit} noValidate>
        <Stack gap="sm">
          <FieldRow>
            <KindField
              label="Body"
              data={KINDS}
              value={form.values.kind}
              onChange={(next) => {
                form.setFieldValue('kind', next);
                form.clearFieldError('body');
              }}
            />
            <Switch label="Durable" {...form.getInputProps('durable', { type: 'checkbox' })} />
          </FieldRow>
          <Textarea
            label="Content"
            description={
              form.values.kind === 'BYTES' ? 'The bytes of the message, encoded as base64.' : 'The text of the message.'
            }
            {...form.getInputProps('body')}
            autosize
            minRows={4}
          />
          <Fieldset legend="Properties">
            <Stack gap="xs">
              {form.values.props.length === 0 ? (
                <Text size="sm" c="dimmed">
                  No properties. A property is a named value a consumer can select on.
                </Text>
              ) : null}
              {form.values.props.map((p, i) => (
                <FieldRow key={p.id}>
                  <TextInput label="Key" size="xs" {...form.getInputProps(`props.${i}.k`)} />
                  <TextInput label="Value" size="xs" {...form.getInputProps(`props.${i}.v`)} />
                  <ActionIcon
                    variant="subtle"
                    aria-label={`Remove property ${i + 1}`}
                    onClick={() => form.removeListItem('props', i)}
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
                  onClick={() => form.insertListItem('props', { id: randomId(), k: '', v: '' })}
                >
                  Add property
                </Button>
              </div>
            </Stack>
          </Fieldset>
          <Group justify="flex-end">
            <Button variant="default" size="xs" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" size="xs" loading={send.isPending}>
              Send
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
