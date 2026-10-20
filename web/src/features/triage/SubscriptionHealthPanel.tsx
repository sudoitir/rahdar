import type { ResourceRef } from '../../kernel/api/resourceId.ts';
import { Stack, Text } from '@mantine/core';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { useSubscriptionHealth } from './api.ts';
import { HealthVerdict } from './HealthVerdict.tsx';
import { formatCount, formatDuration, formatRate, trendPhrase, verdictCopy } from './verdict.ts';

/**
 * One subscription's consumer health in its detail drawer (`subscription.detail.panels`).
 *
 * It sits above the metrics history panel and links nowhere of its own: the
 * charts already exist on the metrics view, and redrawing them here would be a
 * second answer to a question already answered.
 */
export function SubscriptionHealthPanel({
  clusterId,
  subscription,
}: Readonly<{
  clusterId: string;
  subscription: ResourceRef;
}>) {
  const query = useSubscriptionHealth(clusterId, subscription);

  if (query.isPending) {
    return (
      <Section variant="card" headingLevel={3} title="Consumer health">
        <LoadingState label="Loading consumer health" blockSize="9rem" />
      </Section>
    );
  }

  if (query.isError) {
    // Unreachable is not healthy, and not empty either.
    return (
      <Section
        variant="card"
        headingLevel={3}
        title="Consumer health"
        description="Consumer health is unavailable: the verdict for this subscription could not be read, so nothing here says whether its consumers are keeping up."
      >
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      </Section>
    );
  }

  const row = query.data;
  if (!row) {
    return null;
  }

  const copy = verdictCopy(row.verdict);
  const eta = formatDuration(row.drainEtaSeconds);
  const sampleAge = formatDuration(row.sampleSpanSeconds);

  return (
    <Section variant="card" headingLevel={3} title="Consumer health" actions={<HealthVerdict row={row} />}>
      <Stack gap="xs">
        <Text size="sm">{copy.headline}</Text>
        <Text size="xs" c="dimmed">
          {row.cause}
        </Text>
      </Stack>

      <DescriptionList
        label="Consumer health figures"
        columns={2}
        items={[
          { term: 'Arriving', value: formatRate(row.addRate) },
          { term: 'Acknowledged', value: formatRate(row.ackRate) },
          { term: 'Per consumer', value: formatRate(row.ackRatePerConsumer) },
          { term: 'In flight', value: formatCount(row.delivering) },
        ]}
      />

      <Text size="xs" c="dimmed">
        {trendPhrase(row)}
        {eta ? ` · clears in about ${eta}` : ''}
        {sampleAge ? ` · measured over ${sampleAge}` : ''}
      </Text>

      {row.nodesPresent < row.nodesTotal ? (
        <Text size="xs" c="dimmed">
          Present on {row.nodesPresent} of {row.nodesTotal} nodes; these numbers cover only the nodes reporting it.
        </Text>
      ) : null}

      {row.source === 'BROKER' ? (
        <Text size="xs" c="dimmed">
          Reported by the broker's own slow-consumer detection
          {row.brokerConsumerName ? ` for ${row.brokerConsumerName}` : ''}.
        </Text>
      ) : null}
    </Section>
  );
}
