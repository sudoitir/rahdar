import { Anchor, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useIndexSubscriptions } from './api.ts';
import { uncapturedDestinations } from './captureCoverage.ts';
import { Notice } from '../../ui/Notice.tsx';

/**
 * A short nudge where something depends on seeing every message: which of these destinations
 * are only sampled, and where capture is turned on. Renders nothing once every one of them
 * is captured, and nothing when subscriptions cannot be read — it is advice, not a state.
 */
export function CaptureHint(props: { clusterId: string; destinations: string[]; purpose: string }) {
  // Nothing to check means nothing to fetch.
  return props.destinations.length === 0 ? null : <CoverageHint {...props} />;
}

function CoverageHint({
  clusterId,
  destinations,
  purpose,
}: Readonly<{ clusterId: string; destinations: string[]; purpose: string }>) {
  const subscriptions = useIndexSubscriptions(clusterId);
  if (!subscriptions.data) return null;
  const missing = uncapturedDestinations(subscriptions.data, destinations);
  if (missing.length === 0) return null;
  return (
    <Notice title="Only sampled">
      <Text size="sm">
        {missing.join(', ')} {missing.length === 1 ? 'is' : 'are'} not captured, so {purpose} sees only the messages a
        sample happened to catch. Turn on &ldquo;Capture everything&rdquo; for {missing.length === 1 ? 'it' : 'them'}{' '}
        under{' '}
        <Anchor component={Link} to={`/clusters/${clusterId}/settings`} size="sm">
          Settings → Message index
        </Anchor>
        .
      </Text>
    </Notice>
  );
}
