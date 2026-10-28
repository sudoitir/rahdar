import { GROUP_STATES, GROUP_TYPES } from './words.ts';

export interface GroupsSearch {
  q?: string;
  type?: string;
  state?: string;
}

/** The address's filter, each member only when it names a value the list knows. */
/** The address's filter, each member only when it names a value the list knows. */
export function validateGroupsSearch(raw: Record<string, unknown>): GroupsSearch {
  const out: GroupsSearch = {};
  if (typeof raw.q === 'string' && raw.q) out.q = raw.q;
  if ((GROUP_TYPES as readonly string[]).includes(String(raw.type))) out.type = String(raw.type);
  if ((GROUP_STATES as readonly string[]).includes(String(raw.state))) out.state = String(raw.state);
  return out;
}

export interface AclSearch {
  principal?: string;
  resource?: string;
}

export function validateAclSearch(raw: Record<string, unknown>): AclSearch {
  const out: AclSearch = {};
  if (typeof raw.principal === 'string' && raw.principal) out.principal = raw.principal;
  if (typeof raw.resource === 'string' && raw.resource) out.resource = raw.resource;
  return out;
}
