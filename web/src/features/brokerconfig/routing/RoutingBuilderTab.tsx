import { useState } from 'react';
import { Stack, Text } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { ErrorState } from '../../../ui/ErrorState.tsx';
import { LoadingState } from '../../../ui/LoadingState.tsx';
import { useBrokerConfig } from '../api.ts';
import { useDeclarationGates } from '../gates.ts';
import { ReviewApplyDrawer, type ApplyScope } from '../ReviewApplyDrawer.tsx';
import { asSection, type Section } from '../words.ts';
import { RoutingTab } from './RoutingTab.tsx';

/** What the builder keeps in the Routing screen's URL (ADR-0090 D9, ADR-0094). */
interface BuilderSearch {
  section?: string;
  item?: string;
  anchor?: string;
  selected?: string;
}

/**
 * The routing builder as the Routing screen's Builder tab (ADR-0094), contributed through the
 * `routes.tabs` slot. It brings what the Configuration screen used to hand it: the declaration,
 * the write and apply gates, its URL state, and the review drawer — the same one, so the builder
 * still has one plan and one apply (ADR-0090 D1).
 */
export function RoutingBuilderTab({ clusterId }: Readonly<{ clusterId: string }>) {
  const search = useSearch({ strict: false }) as BuilderSearch;
  const navigate = useNavigate();
  const declaration = useBrokerConfig(clusterId);
  const { writeGate, applyGate } = useDeclarationGates(clusterId, declaration.data);
  const [scope, setScope] = useState<ApplyScope | null>(null);

  const onSearch = (patch: { section?: Section; item?: string; anchor?: string; selected?: string }) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  if (declaration.isError) {
    return (
      <Stack gap="sm">
        <ErrorState error={declaration.error} onRetry={() => void declaration.refetch()} />
        <Text size="sm" c="dimmed">
          The builder draws the declaration, so it has nothing to draw until the declaration can be read. The Diverts
          and Bridges tabs read the brokers directly.
        </Text>
      </Stack>
    );
  }
  if (!declaration.data) {
    return <LoadingState label="Loading the declaration" blockSize="28rem" />;
  }

  const d = declaration.data;
  return (
    <>
      <RoutingTab
        declaration={d}
        writeGate={writeGate}
        applyGate={applyGate}
        onReview={() => setScope({})}
        openSection={asSection(search.section)}
        openItem={search.item}
        anchor={search.anchor}
        selected={search.selected}
        onSearch={onSearch}
      />
      <ReviewApplyDrawer declaration={d} scope={scope} opened={scope !== null} onClose={() => setScope(null)} />
    </>
  );
}
