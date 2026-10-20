import { useRef, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';

import { accessQuery, resourceAccessQuery, type AccessSummary } from './api.ts';

/**
 * A subscription or destination of a cluster, for a check on one resource. Pass the `allowedActions` a row already
 * carries from the server to answer from it with no request.
 */
export interface ResourceWhere {
  clusterId: string;
  type: 'subscription' | 'destination';
  name: string;
  allowedActions?: AllowedActions;
}

/** The actions the server says the caller holds on one row, as a list carries them. */
export type AllowedActions = readonly string[];

/** One thing the hook has asked the server about: a cluster as a whole, or one queue or address of it. */
type Ask = { id: string; clusterId: string; resource?: { type: 'SUBSCRIPTION' | 'DESTINATION'; name: string } };

const askFor = (where: string | ResourceWhere): Ask =>
  typeof where === 'string'
    ? { id: where, clusterId: where }
    : {
        id: `${where.clusterId}|${where.type}|${where.name}`,
        clusterId: where.clusterId,
        resource: { type: where.type === 'subscription' ? 'SUBSCRIPTION' : 'DESTINATION', name: where.name },
      };

/**
 * Client-side reflection of the server's permission model (authorization spec), used only to offer, disable or
 * explain controls a request would be rejected for anyway. The server's own checks are the enforcement; this reads
 * the summary it computes with the same resolver (`/me/access`), so environment grants, teams and shares are
 * counted as the server counts them.
 *
 * <p>`can(permission)` is about the whole installation. `can(permission, clusterId)` is about one cluster, and
 * `can(permission, { clusterId, type, name })` about one subscription or destination, with a team's or share's rights counted.
 * Each is fetched the first time it is asked. Until it arrives the control is offered, as it is while grants load:
 * blocking on an answer not yet known would lock out someone the server lets in. For a resource permission the
 * cluster answer counts grants only; what a team or share gives on some subscription or address is in the summary's
 * `anywhere`, and on one named queue or address in its own answer, or in the row's `allowedActions`.
 *
 * <p>`canAnywhere` is the page-level right: held on the cluster by a grant, or on some subscription or address through a team
 * or a share. A control is hidden only when it is false. `canOn` is the right on one row the server sent, from the
 * row's `allowedActions`: false there is a control shown disabled, with its reason. `createPatterns` are the name
 * patterns a team or share lets the caller create queues and addresses under.
 */
export function useCan() {
  const installation = useQuery(accessQuery());
  const [asked, setAsked] = useState<Ask[]>([]);
  const knownAsked = useRef(new Set<string>());
  const answers = useQueries({
    queries: asked.map((a) =>
      a.resource ? resourceAccessQuery(a.clusterId, a.resource.type, a.resource.name) : accessQuery(a.clusterId),
    ),
  });
  const byAsk = new Map<string, readonly string[] | undefined>(
    asked.map((a, i) => {
      const data = answers[i]?.data;
      return [a.id, data && 'actions' in data ? data.actions : data?.permissions];
    }),
  );
  const clusterSummaries = new Map<string, AccessSummary | undefined>(
    asked.map((a, i) => [a.id, a.resource ? undefined : (answers[i]?.data as AccessSummary | undefined)]),
  );

  // Something is asked about while a component renders, so it is requested just after the render.
  function ask(request: Ask) {
    if (knownAsked.current.has(request.id)) return;
    knownAsked.current.add(request.id);
    queueMicrotask(() => setAsked((current) => [...current, request]));
  }

  const summary = installation.data;
  const teamAdmin = summary?.teams.some((t) => t.teamAdmin) ?? false;

  function can(permission: string, where?: string | ResourceWhere): boolean {
    if (!where) {
      // Holding team:admin in a team is not a grant, yet it is what lets someone manage that team's members.
      return (summary?.permissions.includes(permission) ?? false) || (permission === 'team:admin' && teamAdmin);
    }
    if (typeof where !== 'string' && where.allowedActions) {
      return where.allowedActions.includes(permission);
    }
    const request = askFor(where);
    const known = byAsk.get(request.id);
    if (!known) {
      ask(request);
      return true;
    }
    return known.includes(permission);
  }

  /**
   * Whether the caller holds the permission on the cluster, or, for a resource permission, on some subscription or
   * address of it through a team or share: whether to offer a page, not whether to allow a change.
   */
  function canAnywhere(permission: string, clusterId: string): boolean {
    const known = clusterSummaries.get(clusterId);
    if (!known) {
      ask(askFor(clusterId));
      return true;
    }
    return known.permissions.includes(permission) || known.anywhere.includes(permission);
  }

  /** How the caller reaches a kind of resource on a cluster: by a grant, only through teams, or not at all. */
  function reach(permission: string, clusterId: string): 'grant' | 'teams' | 'none' | 'unknown' {
    const known = clusterSummaries.get(clusterId);
    if (!known) {
      ask(askFor(clusterId));
      return 'unknown';
    }
    if (known.permissions.includes(permission)) return 'grant';
    return known.anywhere.includes(permission) ? 'teams' : 'none';
  }

  function createPatterns(clusterId: string, type: 'subscription' | 'destination'): string[] | undefined {
    const known = clusterSummaries.get(clusterId);
    if (!known) ask(askFor(clusterId));
    return known?.createPatterns[type];
  }

  return {
    can,
    canAnywhere,
    reach,
    /** Whether the row the server sent allows the action to the caller. */
    canOn: (permission: string, resource: { allowedActions: AllowedActions }) =>
      resource.allowedActions.includes(permission),
    createPatterns,
    /** The teams the caller belongs to, with their role in each. */
    teams: summary?.teams ?? [],
    loading: installation.isLoading,
  };
}
