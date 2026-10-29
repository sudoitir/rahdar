import { useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';

import type { PaletteSource } from '../../kernel/feature.ts';
import { destinationOf, useSubscriptions } from './api.ts';
import { backlogWords } from '../../ui/backlog.ts';

const SHOWN = 8;

/**
 * The command palette's Subscriptions group (ADR-0109): the subscriptions whose name or destination matches what is
 * typed, each opening that subscription. It reads Rahdar's own subscription snapshot — never a broker — and only
 * while the palette is open with at least two characters typed, once per query rather than polling.
 */
export const SubscriptionPalette: PaletteSource = ({ clusterId, query, opened, report }) => {
  const searching = Boolean(clusterId) && opened && query.length >= 2;
  const subscriptions = useSubscriptions(
    clusterId ?? '',
    { q: query, size: SHOWN },
    { enabled: searching, live: false },
  );
  const navigate = useNavigate();

  useEffect(() => {
    if (!searching || !clusterId || !subscriptions.data || subscriptions.isPlaceholderData) {
      report([]);
      return;
    }
    const { data, hasNext } = subscriptions.data;
    const count = subscriptions.data.count ?? data.length;
    report([
      {
        group: 'Subscriptions',
        actions: [
          ...data.map((q) => {
            const where = destinationOf(q) === q.name ? '' : `on ${destinationOf(q)} · `;
            return {
              id: `subscription-${destinationOf(q)}-${q.name}`,
              label: q.name,
              description: `${where}backlog ${backlogWords(q.backlog)} · ${q.nodesPresent}/${q.nodesTotal} nodes`,
              // The query itself, so the palette's own filter keeps what the server already matched.
              keywords: [query, destinationOf(q)],
              onClick: () => navigate({ to: `/clusters/${clusterId}/subscriptions`, search: { subscription: q.id } }),
            };
          }),
          ...(hasNext
            ? [
                {
                  id: 'subscriptions-all',
                  label: `Show all ${count.toLocaleString()} subscriptions matching "${query}"`,
                  keywords: [query],
                  onClick: () => navigate({ to: `/clusters/${clusterId}/subscriptions`, search: { q: query } }),
                },
              ]
            : []),
        ],
      },
    ]);
  }, [searching, clusterId, query, subscriptions.data, subscriptions.isPlaceholderData, navigate, report]);

  return null;
};
