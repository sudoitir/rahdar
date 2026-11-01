import { pairProblems } from '../../ui/formPairs.ts';
import type { RabbitMqConnection, RabbitMqConnectionDetail, RabbitMqConnectionUpdate } from './api.ts';

export type RabbitAuth = 'password' | 'token';

/** What the RabbitMQ connection form holds. */
export interface RabbitMqFields {
  managementUrls: string;
  amqpEndpoints: string;
  auth: RabbitAuth;
  username: string;
  password: string;
  token: string;
  tlsBundle: string;
  eventVhost: string;
  watchIntervalSeconds: string;
}

export const RABBITMQ_EMPTY: RabbitMqFields = {
  managementUrls: '',
  amqpEndpoints: '',
  auth: 'password',
  username: '',
  password: '',
  token: '',
  tlsBundle: '',
  eventVhost: '',
  watchIntervalSeconds: '',
};

export const RABBITMQ_PAIRS: [string, string][] = [['username', 'password']];

export const AUTH_TYPES: { value: RabbitAuth; label: string }[] = [
  { value: 'password', label: 'User name and password' },
  { value: 'token', label: 'Bearer token' },
];

export const DEFAULT_EVENT_VHOST = '/';
export const DEFAULT_WATCH_SECONDS = 10;

/** One address in what was typed. */
export interface ParsedAddress {
  /** What the operator typed, verbatim. */
  original: string;
  /** The normalised address, or `null` while the token is not one. */
  address: string | null;
  /** What is wrong with the token, or `null`. */
  problem: string | null;
}

const HOST = /^(?:[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)(?:\.[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)*$/;
const URL_SHAPE = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(.*)$/;

interface Scheme {
  schemes: Record<string, number>;
  /** What the field takes, for the message. */
  shape: string;
  /** Whether a path after the port is allowed. */
  prefix: boolean;
}

const MANAGEMENT: Scheme = { schemes: { http: 15672, https: 15671 }, shape: 'http(s)://host:port', prefix: true };
const AMQP: Scheme = { schemes: { amqp: 5672, amqps: 5671 }, shape: 'amqp(s)://host:port', prefix: false };

function parseOne(original: string, kind: Scheme): ParsedAddress {
  const refuse = (problem: string): ParsedAddress => ({ original, address: null, problem });
  const parts = URL_SHAPE.exec(original);
  if (!parts) return refuse(`${original} is not an address: write ${kind.shape}.`);
  const [, rawScheme, authority, path, rest] = parts;
  const scheme = rawScheme.toLowerCase();
  if (authority.includes('@')) {
    return refuse(`Remove the account from ${original}: put it in the credentials fields.`);
  }
  if (!(scheme in kind.schemes)) return refuse(`${original} must start with ${kind.shape.split('://')[0]}://.`);
  if (rest) return refuse(`Remove the query or fragment from ${original}.`);
  if (!kind.prefix && path.replace(/\/+$/, '')) return refuse(`Remove the path from ${original}: write ${kind.shape}.`);
  const bracketed = /^(\[[0-9A-Fa-f:.]+])(?::(\d+))?$/.exec(authority);
  let host: string;
  let port: string | undefined;
  if (bracketed) {
    [, host, port] = bracketed;
  } else {
    const colon = authority.lastIndexOf(':');
    host = colon < 0 ? authority : authority.slice(0, colon);
    port = colon < 0 ? undefined : authority.slice(colon + 1);
    if (!HOST.test(host)) return refuse(`${original} has no valid host name: write ${kind.shape}.`);
  }
  if (port !== undefined && !/^\d+$/.test(port)) return refuse(`The port of ${original} must be a number.`);
  const number = Number(port ?? kind.schemes[scheme]);
  if (number < 1 || number > 65535) return refuse(`The port of ${original} must be between 1 and 65535.`);
  const prefix = kind.prefix ? path.replace(/\/+$/, '') : '';
  return { original, address: `${scheme}://${host.toLowerCase()}:${number}${prefix}`, problem: null };
}

function parseAddresses(raw: string, kind: Scheme): ParsedAddress[] {
  const seen = new Set<string>();
  const out: ParsedAddress[] = [];
  for (const original of raw.split(/[\s,;]+/).filter(Boolean)) {
    const entry = parseOne(original, kind);
    const key = entry.address ?? original;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(entry);
  }
  return out;
}

/** Every management URL in what was typed, normalised: the port defaulting to 15672 (15671 over https). */
export const parseManagementUrls = (raw: string): ParsedAddress[] => parseAddresses(raw, MANAGEMENT);

/** Every AMQP endpoint in what was typed, normalised: the port defaulting to 5672 (5671 over amqps). */
export const parseAmqpEndpoints = (raw: string): ParsedAddress[] => parseAddresses(raw, AMQP);

const addressesOf = (entries: ParsedAddress[]): string[] => entries.flatMap((e) => (e.address ? [e.address] : []));
const problemOf = (entries: ParsedAddress[]): string | null => entries.find((e) => e.problem)?.problem ?? null;

export const urlsOf = (values: RabbitMqFields): string[] => addressesOf(parseManagementUrls(values.managementUrls));
export const endpointsOf = (values: RabbitMqFields): string[] => addressesOf(parseAmqpEndpoints(values.amqpEndpoints));

/** Whether the connection is encrypted anywhere, so a TLS bundle has a use. */
export const usesTls = (values: RabbitMqFields): boolean =>
  [...urlsOf(values), ...endpointsOf(values)].some((a) => a.startsWith('https://') || a.startsWith('amqps://'));

const sameList = (a: string[], b: string[]): boolean => a.length === b.length && a.every((x, i) => x === b[i]);

/**
 * Whether the stored secret must be entered again, as the server decides it: the connection now goes to other
 * management URLs or AMQP endpoints, as another user or with another kind of credential, or no secret is
 * stored. A stored secret is never sent anywhere new. Always true for a registration, which stores none.
 */
export function secretRequired(values: RabbitMqFields, stored: RabbitMqConnectionDetail | null): boolean {
  if (!stored?.secretStored) return true;
  const token = values.auth === 'token';
  return (
    !sameList(urlsOf(values), stored.managementUrls) ||
    !sameList(endpointsOf(values), stored.amqpEndpoints) ||
    token !== stored.tokenAuth ||
    (!token && values.username !== (stored.username ?? ''))
  );
}

/** What is wrong with the values as they stand, by field; the form can be sent only when this is empty. */
export function rabbitMqProblems(
  values: RabbitMqFields,
  stored: RabbitMqConnectionDetail | null = null,
): Record<string, string> {
  const problems: Record<string, string> = {};
  const urls = parseManagementUrls(values.managementUrls);
  const urlProblem = problemOf(urls);
  if (urlProblem) problems.managementUrls = urlProblem;
  else if (addressesOf(urls).length === 0) problems.managementUrls = 'Add at least one management URL.';
  const amqpProblem = problemOf(parseAmqpEndpoints(values.amqpEndpoints));
  if (amqpProblem) problems.amqpEndpoints = amqpProblem;
  const interval = values.watchIntervalSeconds.trim();
  if (interval && !(/^\d+$/.test(interval) && Number(interval) >= 1)) {
    problems.watchIntervalSeconds = 'Enter a whole number of seconds, at least 1.';
  }

  const again = secretRequired(values, stored);
  if (values.auth === 'token') {
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
            missing: 'Enter the user name for this password, or clear the password.',
          },
          {
            name: 'password',
            value: values.password,
            missing: 'Enter the password for this user name, or clear the user name.',
          },
        ),
  );
  if (!values.username) problems.username ??= 'Enter the user name.';
  else if (again && !values.password) {
    problems.password ??= stored ? 'Enter the password again: the connection changed.' : 'Enter the password.';
  }
  return problems;
}

/**
 * The credentials these values describe. A secret left empty is left out, so the server keeps the stored one
 * where it may; the account is always named, so a changed user is seen as one.
 */
function credentialsOf(values: RabbitMqFields): NonNullable<RabbitMqConnection['credentials']> {
  if (values.auth === 'token') return { token: values.token || undefined };
  return { username: values.username, password: values.password || undefined };
}

/** The event vhost and watch interval, left out when empty so the server keeps its default or the stored value. */
function optionalOf(values: RabbitMqFields) {
  const interval = values.watchIntervalSeconds.trim();
  return {
    ...(values.eventVhost ? { eventVhost: values.eventVhost } : {}),
    ...(interval ? { watchIntervalSeconds: Number(interval) } : {}),
  };
}

/** The connection these values describe, ready to check and register. */
export function rabbitMqConnection(values: RabbitMqFields): RabbitMqConnection {
  const amqpEndpoints = endpointsOf(values);
  return {
    system: 'rabbitmq',
    managementUrls: urlsOf(values),
    ...(amqpEndpoints.length > 0 ? { amqpEndpoints } : {}),
    credentials: credentialsOf(values),
    ...(usesTls(values) && values.tlsBundle ? { tlsBundle: values.tlsBundle } : {}),
    ...optionalOf(values),
  };
}

/**
 * The update these values describe for a stored connection: always the whole address list and account, so a
 * change is seen, and an emptied optional field sent as an empty string, which clears it.
 */
export function rabbitMqUpdate(values: RabbitMqFields): RabbitMqConnectionUpdate {
  return {
    system: 'rabbitmq',
    managementUrls: urlsOf(values),
    amqpEndpoints: endpointsOf(values),
    credentials: credentialsOf(values),
    tlsBundle: usesTls(values) ? values.tlsBundle : '',
    ...optionalOf(values),
  };
}

/** The form's starting values for a stored connection: never a secret. */
export function rabbitMqFieldsOf(stored: RabbitMqConnectionDetail): RabbitMqFields {
  return {
    managementUrls: stored.managementUrls.join('\n'),
    amqpEndpoints: stored.amqpEndpoints.join('\n'),
    auth: stored.tokenAuth ? 'token' : 'password',
    username: stored.username ?? '',
    password: '',
    token: '',
    tlsBundle: stored.tlsBundle ?? '',
    eventVhost: stored.eventVhost,
    watchIntervalSeconds: String(stored.watchIntervalSeconds),
  };
}
