import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { GroupMappingPanel } from './GroupMappingPanel.tsx';
import { SubscriptionAccessPanel } from './ResourceAccessPanel.tsx';
import { DestinationAccess } from './rowActions.tsx';
import { RolesPanel } from './RolesPanel.tsx';
import { SessionsSection } from './sections.tsx';
import { TeamsPanel } from './TeamsPanel.tsx';
import { UsersPanel } from './UsersPanel.tsx';

/**
 * Administration of users, roles, teams and grants, and each identity provider's group mappings (authorization spec),
 * and the signed-in user's own sessions on the account page.
 */
export const securityFeature = defineFeature({
  contract: CONTRACT,
  id: 'security',
  slots: {
    'subscription.detail.panels': [{ id: 'security-access', order: 50, Component: SubscriptionAccessPanel }],
    'destination.actions': [
      { id: 'security.destination.access', order: 50, section: 'open', Component: DestinationAccess },
    ],
    // Beside the password: changing it ends the other sessions listed here.
    'account.sections': [{ id: 'security-sessions', order: 15, title: 'Sessions', Component: SessionsSection }],
    'admin.tabs': [
      { id: 'users', order: 10, title: 'Users', group: 'access', Component: UsersPanel },
      { id: 'roles', order: 20, title: 'Roles', group: 'access', Component: RolesPanel },
      { id: 'teams', order: 22, title: 'Teams', group: 'access', Component: TeamsPanel },
      { id: 'group-mappings', order: 40, title: 'Group mappings', group: 'access', Component: GroupMappingPanel },
    ],
  },
});
