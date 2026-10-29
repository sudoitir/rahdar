import { Anchor } from '@mantine/core';

import { approvalPath } from './held.ts';
import { followInApp } from './inAppNavigation.ts';

/** "View request": the link from a held outcome to its request page, which opens in a new tab on a modified click. */
export function HeldRequestLink({ id }: Readonly<{ id: string }>) {
  const to = approvalPath(id);
  return (
    <Anchor href={to} size="sm" underline="always" onClick={(event) => followInApp(event, to)}>
      View request
    </Anchor>
  );
}
