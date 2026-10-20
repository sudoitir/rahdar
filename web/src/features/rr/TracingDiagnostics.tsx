import { useMemo } from 'react';
import { List, Stack, Text } from '@mantine/core';

import { useServerNow } from '../../kernel/time/time.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useRrDiagnostics, type ExpectationDiagnosticsView } from './api.ts';
import { diagnosticsColumns } from './columns.ts';

const rowKey = (e: ExpectationDiagnosticsView) => e.expectationId;

/**
 * Why the Flows tab is empty.
 *
 * A bare `0 flows` reads identically whether nothing was sent, nothing could be
 * browsed, or every request was consumed faster than the sampler ticks — three
 * situations with three different answers. This shows what the sampler actually
 * did and the reasons ranked most-likely-first, each with its remedy.
 */
export function TracingDiagnostics({ clusterId }: Readonly<{ clusterId: string }>) {
  const diagnostics = useRrDiagnostics(clusterId);
  const now = useServerNow();
  const columns = useMemo(() => diagnosticsColumns(now), [now]);

  if (diagnostics.isPending) {
    return <LoadingState label="Checking why there are no flows" blockSize="9rem" />;
  }
  if (diagnostics.isError) {
    return (
      <Stack gap="sm">
        <Text size="sm">Rahdar could not read tracing diagnostics, so it cannot say why there are no flows.</Text>
        <ErrorState error={diagnostics.error} onRetry={() => void diagnostics.refetch()} />
      </Stack>
    );
  }

  const d = diagnostics.data;
  const skewed = d.clock.verdict === 'BROKER_SKEWED' || d.clock.verdict === 'RAHDAR_SUSPECT';

  return (
    <Stack gap="md">
      <Section
        variant="card"
        headingLevel={3}
        title="No flows to show — here is what Rahdar did"
        description={
          <>
            Tracing browses every {Math.round(d.sampleIntervalMs / 1000)}s over the Core client, on{' '}
            {d.nodesWithCoreEndpoint} of {d.nodesTotal} node
            {d.nodesTotal === 1 ? '' : 's'}. Reasons you may be seeing nothing, most likely first:
          </>
        }
      >
        <List size="sm" spacing={4}>
          {d.reasons.map((r) => (
            <List.Item key={r.code}>
              {r.summary}{' '}
              <Text span size="xs" c="dimmed">
                — {r.remedy}
              </Text>
            </List.Item>
          ))}
        </List>
      </Section>

      {skewed ? (
        <Section
          variant="card"
          headingLevel={3}
          title="A clock disagrees with Rahdar's"
          description={
            d.clock.verdict === 'RAHDAR_SUSPECT'
              ? `Every measured node reports the same offset, so the common factor is Rahdar's own host rather than the brokers. Check the clock where Rahdar runs.`
              : `${d.clock.skewedNodes.join(', ')} disagree with Rahdar by up to ${d.clock.worstOffsetMs}ms. Deadlines and latencies involving those nodes are corrected, but a producer or consumer on the same clock is not.`
          }
        />
      ) : null}

      {d.expectations.length > 0 ? (
        <DataTable
          variant="static"
          label="What the sampler did for each traced destination"
          storageKey="rr.diagnostics"
          columns={columns}
          data={d.expectations}
          rowKey={rowKey}
          empty={null}
        />
      ) : null}

      <Text size="xs" c="dimmed">
        Sampling is the design, not a limitation being worked around —{' '}
        <a
          className={linkClasses.link}
          href="https://github.com/sudoitir/rahdar/blob/main/docs/adr/0030-rr-correlation-notification-anchored-browse-sampled.md"
          target="_blank"
          rel="noopener noreferrer"
        >
          ADR-0030
        </a>{' '}
        explains the coverage ceiling and why browsing every message would not be broker-friendly.
      </Text>
    </Stack>
  );
}
