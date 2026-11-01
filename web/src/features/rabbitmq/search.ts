/** The filters the lists share; each is a name typed by the operator. */
export interface FilterSearch {
  vhost?: string;
  source?: string;
  destination?: string;
  user?: string;
}

/** The address's filter, each member only when it is a non-empty string. */
export function validateFilterSearch(raw: Record<string, unknown>): FilterSearch {
  const out: FilterSearch = {};
  for (const name of ['vhost', 'source', 'destination', 'user'] as const) {
    const value = raw[name];
    if (typeof value === 'string' && value) out[name] = value;
  }
  return out;
}
