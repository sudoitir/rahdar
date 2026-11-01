import { ActionIcon, Button, Fieldset, Stack, Text, TextInput } from '@mantine/core';
import type { UseFormReturnType } from '@mantine/form';
import { IconPlus, IconTrash } from '@tabler/icons-react';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { newArgument, type ArgumentRow } from './arguments.ts';

/** The rows of arguments such as `x-max-length` of a queue or an exchange, in a form with an `arguments` list. */
export function ArgumentRows<T extends { arguments: ArgumentRow[] }>({
  form,
}: Readonly<{ form: UseFormReturnType<T> }>) {
  const rows = form.values.arguments;
  return (
    <Fieldset legend="Arguments">
      <Stack gap="xs">
        {rows.length === 0 ? (
          <Text size="sm" c="dimmed">
            No arguments. An argument such as x-max-length changes how the broker treats it.
          </Text>
        ) : null}
        {rows.map((r, i) => (
          <FieldRow key={r.id}>
            <TextInput label="Name" size="xs" placeholder="x-max-length" {...form.getInputProps(`arguments.${i}.k`)} />
            <TextInput label="Value" size="xs" {...form.getInputProps(`arguments.${i}.v`)} />
            <ActionIcon
              variant="subtle"
              aria-label={`Remove argument ${i + 1}`}
              onClick={() => form.removeListItem('arguments' as never, i)}
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
            onClick={() => form.insertListItem('arguments' as never, newArgument() as never)}
          >
            Add argument
          </Button>
        </div>
      </Stack>
    </Fieldset>
  );
}
