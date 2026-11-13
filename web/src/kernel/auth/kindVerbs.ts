import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { useClusterKinds } from '../shell/useClusterSystem.ts';

/** What each verb a kind can lack says, about a subject such as "Artemis addresses". */
const REFUSALS: Record<string, (subject: string) => string> = {
  create: (s) => `${s} cannot be created`,
  browse: (s) => `${s} cannot be browsed`,
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

/** A label mid-sentence: "Queues" becomes "queues", while "ACLs" keeps its capitals. */
export const inSentence = (label: string): string =>
  /^[A-Z][A-Z]/.test(label) ? label : label.charAt(0).toLowerCase() + label.slice(1);

/**
 * The shared subscription route does not create, pause, edit or delete a Kafka subscription. Delete of an
 * empty group stays on Consumer groups, and this row never removes members (ADR-0226).
 */
export function kafkaSubscriptionRefusal(
  system: { id: string; name: string } | null | undefined,
  kind: { resourceClass: string; label: { plural: string } } | undefined,
  verb: string,
): string | undefined {
  if (system?.id !== 'kafka' || kind?.resourceClass !== 'SUBSCRIPTION') return undefined;
  if (verb === 'delete') {
    return 'Delete an empty group from Consumer groups. This does not remove members.';
  }
  if (verb === 'create' || verb === 'pause' || verb === 'update' || verb === 'resume') {
    return verbRefusal(system.name, kind.label, verb);
  }
  return undefined;
}

/** Why a kind cannot take a verb, built from its system and its plural label: "Artemis addresses cannot be purged." */
export function verbRefusal(systemName: string, kindLabel: { plural: string }, verb: string): string {
  const subject = `${systemName} ${inSentence(kindLabel.plural)}`;
  const reason = REFUSALS[verb]?.(subject) ?? `${subject} do not support ${verb}`;
  return `${reason}.`;
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
  const kafka = kafkaSubscriptionRefusal(cluster?.system, known, verb);
  if (kafka) return { kind: 'blocked', reason: kafka };
  if (!cluster?.system || !known || known.verbs.includes(verb)) return undefined;
  return { kind: 'blocked', reason: verbRefusal(cluster.system.name, known.label, verb) };
}
