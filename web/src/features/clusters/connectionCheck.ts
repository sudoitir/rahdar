import type { ConnectionCheck } from './api.ts';

/** Whether a check says the cluster may be registered: nothing failed, it is new and some node answered. */
export function checkPassed(check: ConnectionCheck): boolean {
  return !check.failure && check.supported && !check.alreadyRegistered && check.nodes.some((n) => n.outcome === 'OK');
}

/** The nodes a check did not reach, which register the cluster degraded. */
export function unreachedNodes(check: ConnectionCheck): string[] {
  return check.nodes
    .filter((n) => n.outcome === 'UNREACHABLE')
    .map((n) => (n.artemis ? n.id : `Broker ${n.id}`) + ` (${n.address ?? 'address not learned'})`);
}

/** The accounts a check found rejected, one sentence each: saving would make them worse. */
export function rejectedAccounts(check: ConnectionCheck): string[] {
  return check.nodes.flatMap((n) => {
    const found: string[] = [];
    if (n.artemis?.management === 'REJECTED') found.push(`${n.id} rejected the management account`);
    if (n.artemis?.core === 'REJECTED') found.push(`${n.id} rejected the Core account`);
    return found;
  });
}

const SYSTEM_NAMES: Record<string, string> = {
  kafka: 'Apache Kafka',
  artemis: 'Apache ActiveMQ Artemis',
};

/** A system's name for the check's version line. */
export const systemWords = (id: string): string => SYSTEM_NAMES[id] ?? id;
