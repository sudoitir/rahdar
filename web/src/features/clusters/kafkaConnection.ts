import { pairProblems } from '../../ui/formPairs.ts';
import type { KafkaConnection, SaslMechanism, SecurityProtocol } from './api.ts';
import { parseBootstrapServers } from './bootstrapServers.ts';

/** What the Kafka connection form holds. */
export interface KafkaFields {
  bootstrap: string;
  securityProtocol: SecurityProtocol;
  saslMechanism: SaslMechanism | '';
  username: string;
  password: string;
  token: string;
  tlsBundle: string;
  clientId: string;
}

export const KAFKA_EMPTY: KafkaFields = {
  bootstrap: '',
  securityProtocol: 'PLAINTEXT',
  saslMechanism: '',
  username: '',
  password: '',
  token: '',
  tlsBundle: '',
  clientId: '',
};

export const KAFKA_PAIRS: [string, string][] = [['username', 'password']];

export const PROTOCOLS: { value: SecurityProtocol; label: string }[] = [
  { value: 'PLAINTEXT', label: 'Plaintext (no encryption, no authentication)' },
  { value: 'SSL', label: 'TLS' },
  { value: 'SASL_PLAINTEXT', label: 'SASL over plaintext' },
  { value: 'SASL_SSL', label: 'SASL over TLS' },
];

export const MECHANISMS: SaslMechanism[] = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512', 'OAUTHBEARER'];

export const usesSasl = (protocol: SecurityProtocol): boolean => protocol.startsWith('SASL_');
export const usesTls = (protocol: SecurityProtocol): boolean => protocol === 'SSL' || protocol === 'SASL_SSL';

/** The addresses typed, each with `host:port`, and what is wrong with them by field. */
export function bootstrapOf(values: KafkaFields): { servers: string[]; problem: string | null } {
  const entries = parseBootstrapServers(values.bootstrap);
  const servers = entries.flatMap((e) => (e.address ? [e.address] : []));
  const bad = entries.find((e) => e.problem);
  if (bad) return { servers, problem: bad.problem };
  return { servers, problem: servers.length === 0 ? 'Add at least one bootstrap server, as host:port.' : null };
}

/**
 * Whether the stored secret must be entered again: the connection now goes somewhere new or as someone
 * else, and a stored secret is never sent there. Always true for a registration, which stores none.
 */
export function secretRequired(values: KafkaFields, stored: KafkaConnection | null): boolean {
  if (!stored) return true;
  const known = new Set(stored.bootstrapServers);
  const username = stored.credentials && 'username' in stored.credentials ? stored.credentials.username : '';
  return (
    bootstrapOf(values).servers.some((s) => !known.has(s)) ||
    values.securityProtocol !== stored.securityProtocol ||
    values.saslMechanism !== (stored.saslMechanism ?? '') ||
    values.username !== username
  );
}

/** What is wrong with the values as they stand, by field; the form can be sent only when this is empty. */
export function kafkaProblems(values: KafkaFields, stored: KafkaConnection | null = null): Record<string, string> {
  const problems: Record<string, string> = {};
  const { problem } = bootstrapOf(values);
  if (problem) problems.bootstrap = problem;
  if (!usesSasl(values.securityProtocol)) return problems;
  if (!values.saslMechanism) {
    problems.saslMechanism = 'Choose the SASL mechanism the brokers use.';
    return problems;
  }
  const again = secretRequired(values, stored);
  if (values.saslMechanism === 'OAUTHBEARER') {
    if (again && !values.token) problems.token = 'Enter the token.';
    return problems;
  }
  Object.assign(
    problems,
    stored
      ? {}
      : pairProblems(
          {
            name: 'username',
            value: values.username,
            missing: 'Enter the username for this password, or clear the password.',
          },
          {
            name: 'password',
            value: values.password,
            missing: 'Enter the password for this username, or clear the username.',
          },
        ),
  );
  if (!values.username) problems.username ??= 'Enter the username.';
  else if (again && !values.password) problems.password ??= 'Enter the password again: the connection changed.';
  return problems;
}

/** The connection these values describe, ready to check or save. */
export function kafkaConnection(values: KafkaFields): KafkaConnection {
  const sasl = usesSasl(values.securityProtocol);
  let credentials: KafkaConnection['credentials'] = null;
  if (sasl && values.saslMechanism === 'OAUTHBEARER') credentials = values.token ? { token: values.token } : null;
  else if (sasl) credentials = { username: values.username, password: values.password || undefined };
  return {
    system: 'kafka',
    bootstrapServers: bootstrapOf(values).servers,
    securityProtocol: values.securityProtocol,
    saslMechanism: sasl && values.saslMechanism ? values.saslMechanism : null,
    credentials,
    tlsBundle: usesTls(values.securityProtocol) && values.tlsBundle ? values.tlsBundle : null,
    clientId: values.clientId || null,
  };
}

/** The form's starting values for a stored connection: never a secret. */
export function kafkaFieldsOf(stored: KafkaConnection): KafkaFields {
  return {
    bootstrap: stored.bootstrapServers.join('\n'),
    securityProtocol: stored.securityProtocol,
    saslMechanism: stored.saslMechanism ?? '',
    username: stored.credentials && 'username' in stored.credentials ? stored.credentials.username : '',
    password: '',
    token: '',
    tlsBundle: stored.tlsBundle ?? '',
    clientId: stored.clientId ?? '',
  };
}
