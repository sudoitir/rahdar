import { useState } from 'react';
import { Button, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useNavigate } from '@tanstack/react-router';

import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCheckConnection, useRegisterCluster, type RegisterClusterRequest } from './api.ts';
import { ClusterIdentityFields } from './ClusterIdentityFields.tsx';
import classes from './Clusters.module.css';
import { RABBITMQ_EMPTY, rabbitMqConnection, rabbitMqProblems, type RabbitMqFields } from './rabbitmqConnection.ts';
import { checkPassed, unreachedNodes } from './connectionCheck.ts';
import { ConnectionCheckPanel } from './ConnectionCheckPanel.tsx';
import { RabbitMqConnectionFields } from './RabbitMqConnectionFields.tsx';
import { RegistrationFailure } from './RegistrationFailure.tsx';

const REGISTER: ActionVerb = { verb: 'Register', past: 'Registered', progressive: 'Registering' };

interface Fields extends RabbitMqFields {
  name: string;
  description: string;
  environmentId: string;
}

/**
 * The RabbitMQ registration form: management URLs, AMQP endpoints and credentials, the connection check with its
 * per-node and per-endpoint result, then the name and Register, which waits for a passing check of exactly these inputs.
 */
export function RabbitMqRegistrationForm() {
  const [checkedInputs, setCheckedInputs] = useState<string | null>(null);
  const check = useCheckConnection();
  const register = useRegisterCluster();
  const navigate = useNavigate();

  const form = useForm<Fields>({
    initialValues: { ...RABBITMQ_EMPTY, name: '', description: '', environmentId: '' },
    validateInputOnBlur: true,
    validate: (values) => rabbitMqProblems(values),
  });
  const f = form.values;
  const valid = Object.keys(rabbitMqProblems(f)).length === 0;

  // The check vouches for exactly these inputs, secrets included: one edited afterwards is a new check.
  const signature = JSON.stringify(rabbitMqConnection(f));
  const checkedThis = checkedInputs === signature;
  const result = check.data ?? null;
  const passed = checkedThis && result !== null && checkPassed(result);
  const degraded = passed && result ? unreachedNodes(result) : [];

  function blockedReason(): string | null {
    if (!valid) return 'Fill in the fields marked above, then check the connection.';
    if (check.isPending) return 'Checking the connection…';
    if (passed) return null;
    if (result && !checkedThis) return 'Check the connection again: the details changed since the last check.';
    if (check.isError || (result && checkedThis)) {
      return result?.alreadyRegistered
        ? 'This cluster is registered already, so there is nothing to register.'
        : 'The check did not pass. Fix what it reports above, then check again.';
    }
    return 'Check the connection first.';
  }
  const reason = blockedReason();

  const request = (): RegisterClusterRequest => ({
    system: 'rabbitmq',
    name: f.name || undefined,
    description: f.description || undefined,
    environmentId: f.environmentId || undefined,
    connection: rabbitMqConnection(f),
  });

  const checkConnection = form.onSubmit(() => {
    setCheckedInputs(signature);
    register.reset();
    check.mutate(request());
  }, focusFirstInvalid(form.getInputNode));

  return (
    <div className={classes.register}>
      <form className={classes.form} noValidate onSubmit={checkConnection}>
        <RabbitMqConnectionFields form={form} />

        {check.isError ? <RegistrationFailure error={check.error} /> : null}
        {result ? <ConnectionCheckPanel check={result} /> : null}
        {register.isError ? <RegistrationFailure error={register.error} /> : null}

        <ClusterIdentityFields
          input={(path) => form.getInputProps(path)}
          nameHint="Optional. Defaults to the first management URL's host."
        />

        <div className={`${classes.actions} ${classes.stickyActions}`}>
          {reason ? (
            <Text size="xs" c="dimmed" className={classes.reason}>
              {reason}
            </Text>
          ) : null}
          {degraded.length > 0 ? (
            <Text size="xs" className={classes.reason}>
              {`${degraded.length} node${degraded.length === 1 ? ' was' : 's were'} not reached, so the cluster will be shown degraded.`}
            </Text>
          ) : null}
          <Button type="submit" variant="default" loading={check.isPending}>
            Check connection
          </Button>
          <Button
            loading={register.isPending}
            disabled={!passed}
            onClick={() =>
              register.mutate(request(), {
                onSuccess: (detail) => {
                  notify.succeeded({ action: REGISTER, subject: `cluster ${detail.name}` });
                  void navigate({ to: `/clusters/${detail.id}` });
                },
              })
            }
          >
            Register cluster
          </Button>
        </div>
      </form>
    </div>
  );
}
