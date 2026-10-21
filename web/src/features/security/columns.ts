import { createElement } from 'react';

import type { Column } from '../../ui/table/index.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type {
  AccessCheckView,
  EffectivePermissionView,
  GroupMappingView,
  ResourceTeamGrant,
  RoleView,
  UserView,
} from './api.ts';
import {
  AccessSources,
  Effect,
  GrantedThrough,
  MappingActions,
  PermissionName,
  RoleActions,
  RoleName,
  TwoStepStatus,
  UserActions,
  UserEnabled,
  UserGrants,
  UserName,
  type UserControls,
} from './cells.tsx';
import { grantText, sourceText, twoStepText, type ScopeLabel } from './words.ts';

/** What a users table needs from its panel: the controls the panel owns, each gated and busy as it is now. */
export interface UserRows {
  controls: UserControls;
}

/**
 * The users' columns. The name identifies a user and the switch and the actions are never hidden, so an
 * account can always be disabled, unlocked or inspected; the provider is the first to go when the table is
 * narrow, then the second step.
 */
export function userColumns({ controls }: UserRows): Column<UserView>[] {
  return [
    {
      id: 'username',
      header: 'Username',
      accessor: (u) => u.username,
      cell: (u) => createElement(UserName, { user: u }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'provider',
      header: 'Provider',
      accessor: (u) => u.providerId,
      cell: (u) => createElement(StatusBadge, null, u.providerId),
      kind: 'status',
      badge: true,
      priority: 'low',
    },
    {
      id: 'twoStep',
      header: 'Two-step verification',
      short: 'Two-step',
      accessor: twoStepText,
      cell: (u) => createElement(TwoStepStatus, { user: u, onReset: () => controls.onReset(u) }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'grants',
      header: 'Roles',
      accessor: (u) => u.grants.map((g) => grantText(g, controls.scopeLabel)).join(', ') || 'none',
      cell: (u) => createElement(UserGrants, { user: u, controls }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'enabled',
      header: 'Enabled',
      accessor: (u) => (u.disabled ? 'disabled' : 'enabled'),
      cell: (u) => createElement(UserEnabled, { user: u, controls }),
      kind: 'status',
      priority: 'essential',
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Unlock Sessions Access check',
      cell: (u) => createElement(UserActions, { user: u, controls }),
      kind: 'status',
      wrap: true,
      // The buttons stack and never wrap their labels: the column is as wide as the longest, "Access check",
      // with a button's padding, or that label is cut off.
      min: 15,
      priority: 'essential',
    },
  ];
}

/** What a roles table needs from its panel: the edit and delete controls, gated by whether the role is built in. */
export interface RoleRows {
  onEdit: (role: RoleView) => void;
  onDelete: (role: RoleView) => void;
}

/**
 * The roles' columns. The name identifies a role and the actions are never hidden; the two-step setting
 * comes next, and the permissions, which are the longest, go first when the table is narrow.
 */
export function roleColumns({ onEdit, onDelete }: RoleRows): Column<RoleView>[] {
  return [
    {
      id: 'name',
      header: 'Name',
      accessor: (r) => (r.builtin ? `${r.name} built-in` : r.name),
      cell: (r) => createElement(RoleName, { role: r }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'permissions',
      header: 'Permissions',
      accessor: (r) => r.permissions.join(', '),
      kind: 'code',
      wrap: true,
      priority: 'low',
    },
    {
      id: 'team',
      header: 'Team role',
      accessor: (r) => (r.teamAssignable ? 'Yes' : 'No'),
      kind: 'status',
      priority: 'low',
    },
    {
      id: 'mfa',
      header: 'Two-step verification',
      accessor: (r) => (r.requiresMfa ? 'Required' : 'Not required'),
      kind: 'status',
      priority: 'high',
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Edit Delete',
      cell: (r) => createElement(RoleActions, { role: r, onEdit, onDelete }),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** The group mappings' columns: the group names what is mapped, the delete is never hidden. */
export function mappingColumns({
  onDelete,
  scopeLabel,
}: Readonly<{ onDelete: (mapping: GroupMappingView) => void; scopeLabel: ScopeLabel }>): Column<GroupMappingView>[] {
  return [
    { id: 'group', header: 'Group', accessor: (m) => m.groupName, kind: 'identifier', priority: 'essential' },
    { id: 'role', header: 'Role', accessor: (m) => m.roleName, kind: 'text', priority: 'essential' },
    {
      id: 'scope',
      header: 'Scope',
      accessor: (m) => scopeLabel(m.scopeType, m.scopeId),
      kind: 'text',
      priority: 'high',
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Delete',
      cell: (m) => createElement(MappingActions, { mapping: m, onDelete }),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** Why a permission has the effect it has: where it came through and whether it acts at this scope. */
export function effectColumns(): Column<EffectivePermissionView>[] {
  return [
    {
      id: 'permission',
      header: 'Permission',
      accessor: (v) => [v.action, v.description].filter(Boolean).join(' '),
      cell: (v) => createElement(PermissionName, { permission: v }),
      kind: 'code',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'role',
      header: 'Role',
      accessor: (v) => (v.via === v.action ? v.roleName : `${v.roleName} through ${v.via}`),
      cell: (v) => createElement(GrantedThrough, { permission: v }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'effect',
      header: 'Effect',
      accessor: (v) => (v.effective ? 'Granted' : `No effect at this scope ${v.reason ?? ''}`),
      cell: (v) => createElement(Effect, { permission: v }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
  ];
}

/** What a user may do here: each permission, whether it is allowed, and every source that allows it. */
export function accessColumns({ scopeLabel }: Readonly<{ scopeLabel: ScopeLabel }>): Column<AccessCheckView>[] {
  return [
    {
      id: 'permission',
      header: 'Permission',
      accessor: (v) => v.action,
      kind: 'code',
      priority: 'essential',
    },
    {
      id: 'allowed',
      header: 'Result',
      accessor: (v) => (v.allowed ? 'Allowed' : 'Not allowed'),
      cell: (v) => createElement(StatusBadge, null, v.allowed ? 'Allowed' : 'Not allowed'),
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
    {
      id: 'sources',
      header: 'Through',
      accessor: (v) => v.sources.map((s) => sourceText(s, scopeLabel)).join('; '),
      cell: (v) => createElement(AccessSources, { view: v, scopeLabel }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
  ];
}

/** The teams and roles that reach one subscription or destination. The team and the role are never hidden. */
export function resourceGrantColumns(): Column<ResourceTeamGrant>[] {
  return [
    { id: 'team', header: 'Team', accessor: (g) => g.teamName, kind: 'identifier', priority: 'essential' },
    { id: 'role', header: 'Role', accessor: (g) => g.roleName, kind: 'identifier', priority: 'essential' },
    {
      id: 'via',
      header: 'Through',
      accessor: (g) => (g.source === 'OWNER' ? 'Owns it' : `Shared by ${g.sharedByTeamName} (${g.pattern})`),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'members',
      header: 'People',
      description: 'Members holding this role through the team',
      accessor: (g) => g.memberCount,
      kind: 'number',
      priority: 'high',
    },
  ];
}
