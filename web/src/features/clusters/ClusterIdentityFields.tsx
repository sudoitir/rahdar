import { Select, TextInput } from '@mantine/core';

import { useEnvironments } from './api.ts';

type Path = 'name' | 'description' | 'environmentId';

/**
 * What names a cluster once its connection is checked: name, description and environment, all optional.
 * Both systems' registration forms end with it.
 */
export function ClusterIdentityFields({
  input,
  nameHint,
}: Readonly<{ input: (path: Path) => object; nameHint: string }>) {
  const environments = useEnvironments();
  const options = (environments.data ?? []).map((e) => ({ value: e.id, label: e.name }));
  return (
    <>
      <TextInput label="Name" description={nameHint} {...input('name')} />
      <TextInput label="Description" description="Optional. Shown beside the name." {...input('description')} />
      <Select
        label="Environment"
        description={
          options.length > 0
            ? 'Optional. Groups the cluster with others.'
            : 'Optional. There are no environments yet; create one under Administration.'
        }
        data={options}
        disabled={options.length === 0}
        clearable
        {...input('environmentId')}
      />
    </>
  );
}
