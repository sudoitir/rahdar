import { describe, expect, it } from 'vitest';
import { IconAt } from '@tabler/icons-react';

import { CONTRACT, defineFeature, type NavContribution } from './feature.ts';
import type { NavGroupId } from './nav/groups.ts';
import { navGroups } from './registry.ts';

function view(group: NavGroupId, order: number, label: string): NavContribution {
  return { group, order, label, icon: IconAt, path: label.toLowerCase() };
}

describe('navGroups', () => {
  it('orders groups by the kernel catalogue and views by their declared order', () => {
    const groups = navGroups([
      defineFeature({ contract: CONTRACT, id: 'events', nav: [view('activity', 10, 'Events')] }),
      defineFeature({
        contract: CONTRACT,
        id: 'resources',
        nav: [view('messaging', 20, 'Later'), view('messaging', 10, 'Queues')],
      }),
    ]);

    expect(groups.map((group) => group.label)).toEqual(['Messaging', 'Activity']);
    expect(groups[0].items.map((item) => item.label)).toEqual(['Queues', 'Later']);
  });

  it('leaves out every group no feature contributes to', () => {
    expect(navGroups([defineFeature({ contract: CONTRACT, id: 'mcp' })])).toEqual([]);
  });

  it('lists a system-only view only for that system, and a view of a resource class only where the system has one', () => {
    const features = [
      defineFeature({
        contract: CONTRACT,
        id: 'resources',
        nav: [
          { ...view('resources', 10, 'Connections'), resourceClass: 'CONNECTION' },
          { ...view('resources', 20, 'Producers'), resourceClass: 'PRODUCER' },
          { ...view('resources', 30, 'Topology'), system: 'artemis' },
          view('resources', 40, 'Alerts'),
        ],
      }),
    ];
    const labels = (system: string | undefined, classes: string[] | undefined) =>
      navGroups(features, system, classes).flatMap((g) => g.items.map((i) => i.label));

    expect(labels('artemis', ['CONNECTION', 'PRODUCER'])).toEqual(['Connections', 'Producers', 'Topology', 'Alerts']);
    expect(labels('kafka', ['PRODUCER'])).toEqual(['Producers', 'Alerts']);
    // Until the cluster has said what it has, a view that needs a class is not claimed.
    expect(labels(undefined, undefined)).toEqual(['Alerts']);
  });
});
