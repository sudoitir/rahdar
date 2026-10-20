import { useEffect, useState } from 'react';
import type { SpotlightActionGroupData } from '@mantine/spotlight';

import type { PaletteSource } from '../../kernel/feature.ts';
import { QueuePalette } from '../queues/index.ts';
import { ResourcePalette } from './ResourcePalette.tsx';

/** The palette's groups for this feature: the queues that match, then the offer to search the live views. */
export const ResourcesPalette: PaletteSource = (props) => {
  const { report } = props;
  const [queues, setQueues] = useState<SpotlightActionGroupData[]>([]);
  const [live, setLive] = useState<SpotlightActionGroupData[]>([]);
  useEffect(() => report([...queues, ...live]), [queues, live, report]);
  return (
    <>
      <QueuePalette {...props} report={setQueues} />
      <ResourcePalette {...props} report={setLive} />
    </>
  );
};
