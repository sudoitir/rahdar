import { useState } from 'react';
import { Button, Collapse, Pill, PasswordInput, Select, Text, Textarea, TextInput } from '@mantine/core';
import type { UseFormReturnType } from '@mantine/form';
import { IconChevronRight } from '@tabler/icons-react';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { useRevalidatePairs } from '../../ui/formPairs.ts';
import classes from './Clusters.module.css';
import {
  AUTH_TYPES,
  DEFAULT_EVENT_VHOST,
  DEFAULT_WATCH_SECONDS,
  RABBITMQ_PAIRS,
  parseAmqpEndpoints,
  parseManagementUrls,
  usesTls,
  urlsOf,
  endpointsOf,
  type ParsedAddress,
  type RabbitAuth,
  type RabbitMqFields,
} from './rabbitmqConnection.ts';

function Entries({ entries, label }: Readonly<{ entries: ParsedAddress[]; label: string }>) {
  return (
    <div className={classes.serverPills}>
      {entries.length > 0 ? (
        <Pill.Group role="group" aria-label={label}>
          {entries.map((e) => (
            <Pill key={e.original} className={classes.mono} data-invalid={e.problem ? '' : undefined}>
              {e.address ?? `${e.original} (refused)`}
            </Pill>
          ))}
        </Pill.Group>
      ) : null}
    </div>
  );
}

/**
 * A RabbitMQ connection's fields, shared by registration and the connection edit: the management URLs and AMQP
 * endpoints shown as the entries they parse to, the kind of credential and the account, the TLS bundle only
 * where an address is encrypted, and the event vhost and watch interval under Advanced. With `editing`, a
 * stored secret is kept when its field is left empty.
 */
export function RabbitMqConnectionFields({
  form,
  editing = false,
}: Readonly<{ form: UseFormReturnType<RabbitMqFields>; editing?: boolean }>) {
  const [advancedOpen, setAdvancedOpen] = useState(false);
  useRevalidatePairs(form, RABBITMQ_PAIRS);
  const f = form.values;
  const keep = editing ? 'Leave empty to keep the stored one.' : undefined;
  const plaintext = [...urlsOf(f), ...endpointsOf(f)].some((a) => a.startsWith('http://') || a.startsWith('amqp://'));

  return (
    <div className={classes.form}>
      <Textarea
        label="Management URLs"
        description="http(s)://host:port, one or more, separated by commas, spaces or new lines. The port defaults to 15672 (15671 over https); a path prefix is kept. Rahdar finds the other nodes from them."
        autosize
        minRows={2}
        autoComplete="off"
        spellCheck={false}
        {...form.getInputProps('managementUrls')}
      />
      <Entries entries={parseManagementUrls(f.managementUrls)} label="Management URLs entered" />

      <Textarea
        label="AMQP endpoints"
        description="Optional. amqp(s)://host:port, for reading and sending messages. The port defaults to 5672 (5671 over amqps). Without any, the cluster is managed only."
        autosize
        minRows={2}
        autoComplete="off"
        spellCheck={false}
        {...form.getInputProps('amqpEndpoints')}
      />
      <Entries entries={parseAmqpEndpoints(f.amqpEndpoints)} label="AMQP endpoints entered" />

      <Select
        label="Credentials"
        data={AUTH_TYPES}
        allowDeselect={false}
        {...form.getInputProps('auth')}
        onChange={(value) => {
          form.setFieldValue('auth', value as RabbitAuth);
          form.clearFieldError('token');
          form.clearFieldError('username');
          form.clearFieldError('password');
        }}
      />
      {f.auth === 'token' ? (
        <PasswordInput label="Token" description={keep} autoComplete="off" {...form.getInputProps('token')} />
      ) : (
        <FieldRow>
          <TextInput label="User name" autoComplete="off" {...form.getInputProps('username')} />
          <PasswordInput
            label="Password"
            description={keep}
            autoComplete={editing ? 'new-password' : 'off'}
            {...form.getInputProps('password')}
          />
        </FieldRow>
      )}
      {plaintext ? (
        <Notice title="The credentials travel unencrypted" tone="warning">
          An http or amqp address sends the account in the clear. You can still register the cluster; use https and
          amqps if the cluster offers them.
        </Notice>
      ) : null}

      {usesTls(f) ? (
        <>
          <TextInput
            label="TLS bundle"
            description="Optional. Name of a Spring SSL bundle holding the trust store and a client certificate."
            {...form.getInputProps('tlsBundle')}
          />
          <Text size="xs" c="dimmed">
            Host name verification is always on: a node whose certificate does not name the address it is reached at is
            refused.
          </Text>
        </>
      ) : null}

      <Button
        variant="subtle"
        size="compact-sm"
        className={classes.start}
        onClick={() => setAdvancedOpen((o) => !o)}
        aria-expanded={advancedOpen}
        leftSection={
          <IconChevronRight size={16} aria-hidden className={classes.chevron} data-open={advancedOpen || undefined} />
        }
      >
        Advanced: event vhost and watch interval
      </Button>
      <Collapse expanded={advancedOpen}>
        <div className={classes.form}>
          <TextInput
            label="Event vhost"
            description={`Optional. The vhost whose events Rahdar listens to. Defaults to ${DEFAULT_EVENT_VHOST}.`}
            placeholder={DEFAULT_EVENT_VHOST}
            {...form.getInputProps('eventVhost')}
          />
          <TextInput
            label="Watch interval (seconds)"
            description={`Optional. How often Rahdar reads the cluster. Defaults to ${DEFAULT_WATCH_SECONDS}.`}
            placeholder={String(DEFAULT_WATCH_SECONDS)}
            inputMode="numeric"
            {...form.getInputProps('watchIntervalSeconds')}
          />
        </div>
      </Collapse>
    </div>
  );
}
