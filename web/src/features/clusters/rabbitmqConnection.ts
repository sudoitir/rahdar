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

const SCHEME_NAME = /^[A-Za-z][A-Za-z0-9+.-]*$/;
const LABEL = /^[A-Za-z0-9_-]+$/;
const ALPHANUMERIC = /^[A-Za-z0-9]$/;

/** Whether every dot-separated label is letters, digits, `_` and `-`, starting and ending with a letter or digit. */
function isHostName(host: string): boolean {
  return host
    .split('.')
    .every(
      (label) =>
        LABEL.test(label) && ALPHANUMERIC.test(label.charAt(0)) && ALPHANUMERIC.test(label.charAt(label.length - 1)),
    );
}

/** Where the first of `stops` is in the text, or its length. */
function firstStop(text: string, stops: string): number {
  for (let i = 0; i < text.length; i += 1) {
    if (stops.includes(text.charAt(i))) return i;
  }
  return text.length;
}

/** The text without the slashes at its end, found by a scan so a long run of them costs only its length. */
function withoutTrailingSlashes(text: string): string {
  let end = text.length;
  while (end > 0 && text.charAt(end - 1) === '/') end -= 1;
  return text.slice(0, end);
}

interface UrlParts {
  scheme: string;
  authority: string;
  path: string;
  rest: string;
}

/** An address cut into its scheme, authority, path and what follows (a query or fragment), or `null`. */
function splitUrl(original: string): UrlParts | null {
  const separator = original.indexOf('://');
  if (separator < 1) return null;
  const scheme = original.slice(0, separator);
  if (!SCHEME_NAME.test(scheme)) return null;
  const afterScheme = original.slice(separator + 3);
  const authorityEnd = firstStop(afterScheme, '/?#');
  const remainder = afterScheme.slice(authorityEnd);
  const pathEnd = firstStop(remainder, '?#');
  return {
    scheme,
    authority: afterScheme.slice(0, authorityEnd),
    path: remainder.slice(0, pathEnd),
    rest: remainder.slice(pathEnd),
  };
}

interface Scheme {
  schemes: Record<string, number>;
  /** What the field takes, for the message. */
  shape: string;
  /** Whether a path after the port is allowed. */
  prefix: boolean;
}

const MANAGEMENT: Scheme = { schemes: { http: 15672, https: 15671 }, shape: 'http(s)://host:port', prefix: true };
const AMQP: Scheme = { schemes: { amqp: 5672, amqps: 5671 }, shape: 'amqp(s)://host:port', prefix: false };

/** The host and the port text of an authority, or `null` when the host is not one. */
function splitAuthority(authority: string): { host: string; port: string | undefined } | null {
  const bracketed = /^(\[[0-9A-Fa-f:.]+])(?::(\d+))?$/.exec(authority);
  if (bracketed) return { host: bracketed[1], port: bracketed[2] };
  const colon = authority.lastIndexOf(':');
  const host = colon < 0 ? authority : authority.slice(0, colon);
  return isHostName(host) ? { host, port: colon < 0 ? undefined : authority.slice(colon + 1) } : null;
}

/** What is wrong with the scheme, the account, the query or the path of an address, or `null`. */
function shapeProblem(original: string, scheme: string, parts: UrlParts, kind: Scheme): string | null {
  if (parts.authority.includes('@')) return `Remove the account from ${original}: put it in the credentials fields.`;
  if (!(scheme in kind.schemes)) return `${original} must start with ${kind.shape.split('://')[0]}://.`;
  if (parts.rest) return `Remove the query or fragment from ${original}.`;
  if (!kind.prefix && withoutTrailingSlashes(parts.path))
    return `Remove the path from ${original}: write ${kind.shape}.`;
  return null;
}

function parseOne(original: string, kind: Scheme): ParsedAddress {
  const refuse = (problem: string): ParsedAddress => ({ original, address: null, problem });
  const parts = splitUrl(original);
  if (!parts) return refuse(`${original} is not an address: write ${kind.shape}.`);
  const scheme = parts.scheme.toLowerCase();
  const shape = shapeProblem(original, scheme, parts, kind);
  if (shape) return refuse(shape);
  const target = splitAuthority(parts.authority);
  if (!target) return refuse(`${original} has no valid host name: write ${kind.shape}.`);
  if (target.port !== undefined && !/^\d+$/.test(target.port))
    return refuse(`The port of ${original} must be a number.`);
  const number = Number(target.port ?? kind.schemes[scheme]);
  if (number < 1 || number > 65535) return refuse(`The port of ${original} must be between 1 and 65535.`);
  const prefix = kind.prefix ? withoutTrailingSlashes(parts.path) : '';
  return { original, address: `${scheme}://${target.host.toLowerCase()}:${number}${prefix}`, problem: null };
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

/** What is wrong with the account or the token, added to `problems`. */
function credentialProblems(
  values: RabbitMqFields,
  stored: RabbitMqConnectionDetail | null,
  problems: Record<string, string>,
): void {
  const again = secretRequired(values, stored);
  if (values.auth === 'token') {
    if (again && !values.token) problems.token = 'Enter the token.';
    return;
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

  credentialProblems(values, stored, problems);
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
