import { execFileSync } from 'node:child_process';

const PROJECT = process.env.QA_PROJECT ?? 'rahdar-qa-kafka';
const BOOTSTRAP = 'kafka-1:9092,kafka-2:9092,kafka-3:9092';

// ── The broker ───────────────────────────────────────────────────────────────────────────────────────────────────

/** Runs one of the Kafka image's tools in the first broker and returns what it printed. */
export function kafka(tool: string, ...args: string[]): string {
  return execFileSync(
    'docker',
    [
      'exec',
      '-e',
      'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m',
      `${PROJECT}-kafka-1-1`,
      `/opt/kafka/bin/${tool}`,
      '--bootstrap-server',
      BOOTSTRAP,
      ...args,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 90_000 },
  );
}

/** The committed offset of each partition of `topic` for `group`, as the broker reports it. */
export function committed(group: string, topic: string): Record<number, number> {
  const out = kafka('kafka-consumer-groups.sh', '--describe', '--group', group);
  const offsets: Record<number, number> = {};
  for (const line of out.split('\n')) {
    const cols = line.trim().split(/\s+/);
    if (cols[0] === group && cols[1] === topic && /^\d+$/.test(cols[2]) && /^\d+$/.test(cols[3])) {
      offsets[Number(cols[2])] = Number(cols[3]);
    }
  }
  return offsets;
}

/** The offset the broker reports for each partition of `topic` at a time (-1 latest, -2 earliest). */
export function offsetsAt(topic: string, time: -1 | -2): Record<number, number> {
  const out = kafka('kafka-get-offsets.sh', '--topic', topic, '--time', String(time));
  const offsets: Record<number, number> = {};
  for (const line of out.trim().split('\n')) {
    const [, partition, offset] = line.split(':');
    if (offset !== undefined) offsets[Number(partition)] = Number(offset);
  }
  return offsets;
}

/** A tool run in any container of the stack, against that container's own broker. */
export function inContainer(container: string, tool: string, ...args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', '-e', 'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m', container, `/opt/kafka/bin/${tool}`, ...args],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 90_000 },
  );
}

/** A one-broker KRaft cluster on the stack's network, answering to `alias`, advertising `advertised`:9092. */
export function startBroker(name: string, alias: string, advertised: string): void {
  const env = {
    KAFKA_HEAP_OPTS: '-Xms64m -Xmx256m',
    KAFKA_NODE_ID: '1',
    KAFKA_PROCESS_ROLES: 'broker,controller',
    KAFKA_LISTENERS: 'PLAINTEXT://:9092,CONTROLLER://:9093',
    KAFKA_ADVERTISED_LISTENERS: `PLAINTEXT://${advertised}:9092`,
    KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER',
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
    KAFKA_CONTROLLER_QUORUM_VOTERS: '1@localhost:9093',
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1',
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: '1',
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: '1',
  };
  execFileSync(
    'docker',
    [
      'run',
      '-d',
      '--name',
      name,
      '--network',
      `${PROJECT}_default`,
      '--network-alias',
      alias,
      ...Object.entries(env).flatMap(([k, v]) => ['-e', `${k}=${v}`]),
      'apache/kafka:4.4.0',
    ],
    { stdio: 'ignore' },
  );
}

/** Produces one keyed record to `topic` with the image's console producer. */
export function produceKeyed(topic: string, key: string, value: string): void {
  execFileSync(
    'docker',
    [
      'exec',
      '-i',
      '-e',
      'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m',
      `${PROJECT}-kafka-1-1`,
      '/opt/kafka/bin/kafka-console-producer.sh',
      '--bootstrap-server',
      BOOTSTRAP,
      '--topic',
      topic,
      '--property',
      'parse.key=true',
      '--property',
      'key.separator=:',
    ],
    { input: `${key}:${value}\n`, stdio: ['pipe', 'ignore', 'ignore'], timeout: 60_000 },
  );
}

/** The keys a consumer that reads only committed records sees on `topic` within `seconds`. */
export function readCommittedKeys(topic: string, seconds = 15): string[] {
  try {
    const out = execFileSync(
      'docker',
      [
        'exec',
        '-e',
        'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m',
        `${PROJECT}-kafka-1-1`,
        '/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server',
        BOOTSTRAP,
        '--topic',
        topic,
        '--from-beginning',
        '--isolation-level',
        'read_committed',
        '--timeout-ms',
        String(seconds * 1000),
        '--property',
        'print.key=true',
      ],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 120_000 },
    );
    return out
      .split('\n')
      .map((l) => l.split('\t')[0])
      .filter(Boolean);
  } catch (e) {
    const out = (e as { stdout?: string }).stdout ?? '';
    return out
      .split('\n')
      .map((l) => l.split('\t')[0])
      .filter(Boolean);
  }
}

/** The state of a transactional id as the broker reports it. */
export function transactionState(id: string): string {
  const out = kafka('kafka-transactions.sh', 'describe', '--transactional-id', id);
  return out.trim().split('\n').pop() ?? '';
}

/** Records into `topic` with the image's perf tool: lag for the groups reading it. */
export function produce(topic: string, records: number): void {
  inContainer(
    `${PROJECT}-kafka-1-1`,
    'kafka-producer-perf-test.sh',
    '--topic',
    topic,
    '--num-records',
    String(records),
    '--record-size',
    '128',
    '--throughput',
    '1000',
    '--producer-props',
    `bootstrap.servers=${BOOTSTRAP}`,
  );
}

/** How many members a group has, as the broker reports. */
export function members(group: string): number {
  const out = kafka('kafka-consumer-groups.sh', '--describe', '--group', group, '--state');
  const row = out.trim().split('\n').pop()?.trim().split(/\s+/) ?? [];
  return Number(row[row.length - 1]) || 0;
}

/** Starts the detached consumer that keeps `billing` active, as the seed does. */
export function attachBillingConsumer(): void {
  execFileSync(
    'docker',
    [
      'exec',
      '-d',
      '-e',
      'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m',
      `${PROJECT}-kafka-2-1`,
      '/opt/kafka/bin/kafka-console-consumer.sh',
      '--bootstrap-server',
      BOOTSTRAP,
      '--topic',
      'orders',
      '--group',
      'billing',
      '--from-beginning',
    ],
    { stdio: 'ignore' },
  );
}

/** Opens the transaction the seed leaves hanging: a minute's timeout, the producer killed mid-transaction. */
export function openHangingTransaction(): void {
  execFileSync(
    'docker',
    [
      'exec',
      '-e',
      'KAFKA_HEAP_OPTS=-Xms16m -Xmx128m',
      `${PROJECT}-kafka-2-1`,
      'sh',
      '-c',
      `/opt/kafka/bin/kafka-producer-perf-test.sh --topic payments --num-records 1000000 --record-size 128 --throughput 20 --transactional-id payments-settlement --transaction-duration-ms 3600000 --producer-props bootstrap.servers=${BOOTSTRAP} transaction.timeout.ms=60000 >/dev/null 2>&1 & pid=$!; sleep 25; kill -9 $pid`,
    ],
    { stdio: 'ignore', timeout: 90_000 },
  );
}

/** Puts `group`'s committed offset on one partition of `topic` where the test wants it (the group must be empty). */
export function placeGroup(group: string, topic: string, partition: number, offset: number): void {
  kafka(
    'kafka-consumer-groups.sh',
    '--group',
    group,
    '--topic',
    `${topic}:${partition}`,
    '--reset-offsets',
    '--to-offset',
    String(offset),
    '--execute',
  );
}
