import { useEffect, useState } from 'react';
import type { SpotlightActionGroupData } from '@mantine/spotlight';

import type { PaletteSource } from '../../kernel/feature.ts';
import { SubscriptionPalette } from './SubscriptionPalette.tsx';
import { ResourcePalette } from './ResourcePalette.tsx';

/** The palette's groups for this feature: the subscriptions that match, then the offer to search the live views. */
export const ResourcesPalette: PaletteSource = (props) => {
  const { report } = props;
  const [subscriptions, setSubscriptions] = useState<SpotlightActionGroupData[]>([]);
  const [live, setLive] = useState<SpotlightActionGroupData[]>([]);
  useEffect(() => report([...subscriptions, ...live]), [subscriptions, live, report]);
  return (
    <>
      <SubscriptionPalette {...props} report={setSubscriptions} />
      <ResourcePalette {...props} report={setLive} />
    </>
  );
};
