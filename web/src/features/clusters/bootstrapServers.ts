const DEFAULT_PORT = '9092';
const HOST = /^(?:[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)(?:\.[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)*$/;

export interface BootstrapServer {
  /** What the operator typed, verbatim. */
  original: string;
  /** `host:port`, the port defaulted to 9092, or `null` while the token is not an address. */
  address: string | null;
  /** What is wrong with the token, or `null`. */
  problem: string | null;
}

/**
 * Every bootstrap server in what was typed, split on commas, spaces, semicolons and new lines, so a second
 * host in the same box is a second entry, never dropped. A server is `host:port` with the port defaulting
 * to 9092; a scheme or an account is refused, naming what to remove.
 */
export function parseBootstrapServers(raw: string): BootstrapServer[] {
  const seen = new Set<string>();
  const out: BootstrapServer[] = [];
  for (const original of raw.split(/[\s,;]+/).filter(Boolean)) {
    const entry = parseOne(original);
    const key = entry.address ?? original;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(entry);
  }
  return out;
}

function parseOne(original: string): BootstrapServer {
  const refuse = (problem: string): BootstrapServer => ({ original, address: null, problem });
  if (original.includes('://')) return refuse(`Remove the scheme from ${original}: write host:port.`);
  if (original.includes('@')) return refuse(`Remove the account from ${original}: put it in the credentials fields.`);
  const bracketed = /^\[([0-9A-Fa-f:.]+)](?::(\d+))?$/.exec(original);
  let host: string;
  let port: string | undefined;
  if (bracketed) {
    [, host, port] = bracketed;
    host = `[${host}]`;
  } else {
    const colon = original.lastIndexOf(':');
    host = colon < 0 ? original : original.slice(0, colon);
    port = colon < 0 ? undefined : original.slice(colon + 1);
    if (!HOST.test(host)) return refuse(`${original} is not a host name or address: write host:port.`);
  }
  if (port !== undefined && !/^\d+$/.test(port)) return refuse(`The port of ${original} must be a number.`);
  const number = Number(port ?? DEFAULT_PORT);
  if (number < 1 || number > 65535) return refuse(`The port of ${original} must be between 1 and 65535.`);
  return { original, address: `${host}:${number}`, problem: null };
}
