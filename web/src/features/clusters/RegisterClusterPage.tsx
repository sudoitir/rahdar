import { Group, Radio, Text } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { useSystems, type LoadedSystem } from './api.ts';
import { ArtemisRegistrationForm } from './ArtemisRegistrationForm.tsx';
import classes from './Clusters.module.css';
import { versionRangeWords } from './versionRange.ts';
import { KafkaRegistrationForm } from './KafkaRegistrationForm.tsx';

/** The system's connection form; one per kind of connection the drivers take. */
function ConnectionForm({ system }: Readonly<{ system: LoadedSystem }>) {
  return system.connection === 'bootstrap' ? <KafkaRegistrationForm /> : <ArtemisRegistrationForm />;
}

/** The system the address names, or the only loaded one when the address names none. */
function chosenSystem(list: LoadedSystem[], chosen: string | undefined): LoadedSystem | undefined {
  const found = list.find((system) => system.id === chosen);
  if (found) return found;
  if (!chosen && list.length === 1) return list[0];
  return undefined;
}

/**
 * Registration (`/clusters/new?system=<id>`), a page of its own outside any cluster: choose the system
 * from the drivers Rahdar loads, give its connection, check it, then name and register the cluster. The
 * chosen system is in the address, so the page can be linked to.
 */
export function RegisterClusterPage() {
  const systems = useSystems();
  const { system: chosen } = useSearch({ strict: false }) as { system?: string };
  const navigate = useNavigate();

  const list = systems.data ?? [];
  const selected = chosenSystem(list, chosen);

  let body;
  if (systems.isError) {
    body = <ErrorState error={systems.error} onRetry={() => void systems.refetch()} />;
  } else if (!systems.data) {
    body = <LoadingState label="Loading the broker systems" blockSize="12rem" />;
  } else if (list.length === 0) {
    body = (
      <EmptyState
        kind="empty"
        title="No broker system is loaded"
        description="Rahdar registers clusters through broker drivers, and this installation has none loaded."
      />
    );
  } else {
    body = (
      <>
        <Section title="System" description="The kind of broker the cluster runs.">
          <Radio.Group
            value={selected?.id ?? ''}
            onChange={(id) => void navigate({ to: '.', search: { system: id }, replace: true })}
            label="Broker system"
            withAsterisk={false}
          >
            <div className={classes.systems}>
              {list.map((s) => (
                <Radio.Card key={s.id} value={s.id} className={classes.systemCard}>
                  <Group gap="xs" wrap="nowrap">
                    <Radio.Indicator checked={s.id === selected?.id} />
                    <Text size="sm" fw={500}>
                      {s.name}
                    </Text>
                  </Group>
                  <Text size="xs" c="dimmed" mt={4}>
                    {`Versions: ${versionRangeWords(s.supportedVersions)}`}
                  </Text>
                </Radio.Card>
              ))}
            </div>
          </Radio.Group>
        </Section>
        {chosen && !selected ? (
          <Notice title="That system is not loaded" tone="warning">
            {`Rahdar has no driver for "${chosen}". It supports ${list.map((s) => s.name).join(', ')}.`}
          </Notice>
        ) : null}
        {selected ? (
          <Section title={`${selected.name} connection`} key={selected.id}>
            <ConnectionForm system={selected} />
          </Section>
        ) : null}
        {!chosen && !selected ? (
          <Text size="sm" c="dimmed">
            Choose the system to see what its connection needs.
          </Text>
        ) : null}
      </>
    );
  }

  return (
    <Page>
      <PageHeader
        title="Register a cluster"
        description="A cluster is a set of brokers of one system that Rahdar watches and manages together. Rahdar finds the rest of the cluster from the addresses you give."
      />
      {body}
    </Page>
  );
}
