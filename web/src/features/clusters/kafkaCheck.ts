import type { KafkaConnectionCheck } from './api.ts';

/** Whether a check says the cluster may be registered: nothing failed, it is new and some broker answered. */
export function kafkaCheckPassed(check: KafkaConnectionCheck): boolean {
  return (
    check.failure === null &&
    check.supported &&
    check.alreadyRegistered === null &&
    check.nodes.some((n) => n.outcome === 'OK')
  );
}

/** The brokers a check did not reach, which register the cluster degraded. */
export function unreachedBrokers(check: KafkaConnectionCheck): string[] {
  return check.nodes
    .filter((n) => n.outcome === 'UNREACHABLE')
    .map((n) => `Broker ${n.id} (${n.address ?? 'address not learned'})`);
}
