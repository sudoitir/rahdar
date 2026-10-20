import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { useClusterKinds } from '../shell/useClusterSystem.ts';

/** What each verb a kind can lack says, about a subject such as "Artemis addresses". */
const REFUSALS: Record<string, (subject: string) => string> = {
  create: (s) => `${s} cannot be created`,
  update: (s) => `${s} cannot be edited`,
  delete: (s) => `${s} cannot be deleted`,
  pause: (s) => `${s} cannot be paused`,
  resume: (s) => `${s} cannot be resumed`,
  purge: (s) => `${s} cannot be purged`,
  close: (s) => `${s} cannot be closed`,
  'reset-counters': (s) => `${s} cannot have their counters reset`,
  expire: (s) => `${s} cannot have their messages expired`,
  'move-messages': (s) => `Messages cannot be moved out of ${s}`,
  'copy-messages': (s) => `Messages cannot be copied out of ${s}`,
  'retry-messages': (s) => `Messages cannot be retried from ${s}`,
  'delete-messages': (s) => `Messages cannot be deleted from ${s}`,
};

const plural = (noun: string): string => {
  if (/(s|x|z|ch|sh)$/.test(noun)) return `${noun}es`;
  if (/[^aeiou]y$/.test(noun)) return `${noun.slice(0, -1)}ies`;
  return `${noun}s`;
};

/** Why a kind cannot take a verb, built from its system and its label: "Artemis addresses cannot be purged." */
export function verbRefusal(systemName: string, kindLabel: string, verb: string): string {
  const subject = `${systemName} ${plural(kindLabel.toLowerCase())}`;
  return `${REFUSALS[verb]?.(subject) ?? `${subject} do not support ${verb}`}.`;
}

/**
 * Blocked, with the reason, when the cluster's system says the kind cannot take the verb; `undefined` when it can,
 * and while the cluster or the kind is not known — unknown is not unavailable. It is the first reason a control
 * gives, before a capability that is missing and a permission that is not held.
 */
export function useKindVerbRefusal(
  clusterId: string,
  kind: string | undefined,
  verb: string,
): Extract<GateVerdict, { kind: 'blocked' }> | undefined {
  const cluster = useClusterKinds(clusterId);
  const known = cluster?.kinds?.find((k) => k.id === kind);
  if (!cluster?.system || !known || known.verbs.includes(verb)) return undefined;
  return { kind: 'blocked', reason: verbRefusal(cluster.system.name, known.label, verb) };
}
