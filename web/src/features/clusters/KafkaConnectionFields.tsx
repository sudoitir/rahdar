import { useState } from 'react';
import { Button, Collapse, Pill, PasswordInput, Select, Text, Textarea, TextInput } from '@mantine/core';
import type { UseFormReturnType } from '@mantine/form';
import { IconChevronRight } from '@tabler/icons-react';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { useRevalidatePairs } from '../../ui/formPairs.ts';
import { parseBootstrapServers } from './bootstrapServers.ts';
import classes from './Clusters.module.css';
import { KAFKA_PAIRS, MECHANISMS, PROTOCOLS, usesSasl, usesTls, type KafkaFields } from './kafkaConnection.ts';
import type { SecurityProtocol } from './api.ts';

/**
 * A Kafka connection's fields, shared by registration and the connection edit: bootstrap servers shown as
 * the entries they parse to, the security protocol, the SASL mechanism and credentials only where the
 * protocol uses them, and the TLS bundle only where it encrypts. With `editing`, a stored secret is kept
 * when its field is left empty.
 */
export function KafkaConnectionFields({
  form,
  editing = false,
}: Readonly<{ form: UseFormReturnType<KafkaFields>; editing?: boolean }>) {
  const [advancedOpen, setAdvancedOpen] = useState(false);
  useRevalidatePairs(form, KAFKA_PAIRS);
  const f = form.values;
  const entries = parseBootstrapServers(f.bootstrap);
  const sasl = usesSasl(f.securityProtocol);
  const keep = editing ? 'Leave empty to keep the stored one.' : undefined;

  return (
    <div className={classes.form}>
      <Textarea
        label="Bootstrap servers"
        description="host:port, one or more, separated by commas, spaces or new lines. The port defaults to 9092. Rahdar finds the other brokers from them."
        autosize
        minRows={2}
        autoComplete="off"
        spellCheck={false}
        {...form.getInputProps('bootstrap')}
      />
      {entries.length > 0 ? (
        <Pill.Group role="group" aria-label="Servers entered">
          {entries.map((e) => (
            <Pill key={e.original} className={classes.mono} data-invalid={e.problem ? '' : undefined}>
              {e.address ?? `${e.original} (refused)`}
            </Pill>
          ))}
        </Pill.Group>
      ) : null}

      <Select
        label="Security protocol"
        data={PROTOCOLS}
        allowDeselect={false}
        {...form.getInputProps('securityProtocol')}
        onChange={(value) => {
          form.setFieldValue('securityProtocol', value as SecurityProtocol);
          if (value && !usesSasl(value as SecurityProtocol)) form.clearFieldError('saslMechanism');
        }}
      />
      {f.securityProtocol === 'SASL_PLAINTEXT' ? (
        <Notice title="The credentials travel unencrypted" tone="warning">
          SASL over plaintext sends the username and password, or the token, in the clear. You can still register the
          cluster; choose SASL over TLS if the brokers offer it.
        </Notice>
      ) : null}
      {f.securityProtocol === 'PLAINTEXT' ? (
        <Text size="xs" c="dimmed">
          Nothing is encrypted and Rahdar does not authenticate.
        </Text>
      ) : null}

      {sasl ? (
        <>
          <Select
            label="SASL mechanism"
            data={MECHANISMS}
            allowDeselect={false}
            placeholder="Choose one"
            {...form.getInputProps('saslMechanism')}
          />
          {f.saslMechanism === 'OAUTHBEARER' ? (
            <PasswordInput label="Token" description={keep} autoComplete="off" {...form.getInputProps('token')} />
          ) : null}
          {f.saslMechanism && f.saslMechanism !== 'OAUTHBEARER' ? (
            <FieldRow>
              <TextInput label="Username" autoComplete="off" {...form.getInputProps('username')} />
              <PasswordInput
                label="Password"
                description={keep}
                autoComplete={editing ? 'new-password' : 'off'}
                {...form.getInputProps('password')}
              />
            </FieldRow>
          ) : null}
        </>
      ) : null}

      {usesTls(f.securityProtocol) ? (
        <>
          <TextInput
            label="TLS bundle"
            description="Optional. Name of a Spring SSL bundle holding the trust store and a client certificate."
            {...form.getInputProps('tlsBundle')}
          />
          <Text size="xs" c="dimmed">
            Host name verification is always on: a broker whose certificate does not name the address it advertises is
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
        Advanced: client id
      </Button>
      <Collapse expanded={advancedOpen}>
        <TextInput
          label="Client id"
          description="Optional. How Rahdar names itself to the brokers in their logs and quotas."
          {...form.getInputProps('clientId')}
        />
      </Collapse>
    </div>
  );
}
