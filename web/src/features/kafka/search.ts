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

export interface ReplicationSearch {
  topic?: string;
}

/** The replication address's topic filter, kept only when it names one. */
export interface SchemasSearch {
  tab?: 'subjects' | 'bindings';
  q?: string;
}

/** The open section and the subject filter, each only when it names a value the page knows. */
export function validateSchemasSearch(raw: Record<string, unknown>): SchemasSearch {
  const out: SchemasSearch = {};
  if (raw.tab === 'subjects' || raw.tab === 'bindings') out.tab = raw.tab;
  if (typeof raw.q === 'string' && raw.q) out.q = raw.q;
  return out;
}

export function validateReplicationSearch(raw: Record<string, unknown>): ReplicationSearch {
  const out: ReplicationSearch = {};
  if (typeof raw.topic === 'string' && raw.topic) out.topic = raw.topic;
  return out;
}

export interface ClusterSearch {
  view?: 'table';
  broker?: string;
}

/** Graph is the default, so only the table is written into the address. */
export function validateClusterSearch(raw: Record<string, unknown>): ClusterSearch {
  const out: ClusterSearch = {};
  if (raw.view === 'table') out.view = 'table';
  // A numeric id is parsed as a number before it arrives here. It is still the broker's id.
  const broker = typeof raw.broker === 'number' ? String(raw.broker) : raw.broker;
  if (typeof broker === 'string' && broker) out.broker = broker;
  return out;
}
