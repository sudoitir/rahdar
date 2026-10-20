/*
 * Resource ids as the API spells them: `<kind>/<segment>/<segment>`, each segment percent-encoded outside
 * the unreserved set (`artemis.queue/orders%2Feu`). A resource route carries the kind and the rest of the id
 * as one path segment, encoded once more, so a name that holds a slash or a percent sign still reaches it.
 */

const UNRESERVED_EXTRA = /[!'()*]/g;

/** One segment of an id, percent-encoded as the server does, which also encodes `!'()*`. */
function encodeSegment(value: string): string {
  return encodeURIComponent(value).replace(UNRESERVED_EXTRA, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

/** The canonical id of the resource of this kind with this path, outermost segment first. */
export function resourceId(kind: string, ...path: string[]): string {
  return `${kind}/${path.map(encodeSegment).join('/')}`;
}

/** The kind of a canonical id. */
export function kindOf(id: string): string {
  const slash = id.indexOf('/');
  return slash < 0 ? id : id.slice(0, slash);
}

/** The name of a canonical id: its last segment, decoded. Empty for no id. */
export function nameOf(id: string | null | undefined): string {
  if (!id) return '';
  const last = id.slice(id.lastIndexOf('/') + 1);
  try {
    return decodeURIComponent(last);
  } catch {
    return last;
  }
}

/** What identifies a resource wherever a screen or a plugin is handed one: its canonical id, kind and name. */
export interface ResourceRef {
  /** The resource's canonical id, which an API call names. */
  id: string;
  /** The id of its kind, such as `artemis.queue`. */
  kind: string;
  /** Its name, which a filter and a copy use. */
  name: string;
}

/** The reference of a canonical id. */
export function refOf(id: string): ResourceRef {
  return { id, kind: kindOf(id), name: nameOf(id) };
}

/** `/resources/{kind}/{path}` of a canonical id, ready to follow with `:pause`, `/messages` and the like. */
export function resourcePath(id: string): string {
  const slash = id.indexOf('/');
  return `/resources/${id.slice(0, slash)}/${encodeURIComponent(id.slice(slash + 1))}`;
}

/** The kinds a cluster's system offers, as the cluster view lists them. */
export type ClusterKinds = ReadonlyArray<{ id: string; resourceClass: string }>;

/** The roles a screen that holds only a name needs an id for. */
export type Role = 'SUBSCRIPTION' | 'DESTINATION';

/** The kind that plays this role in the cluster's system. */
export function kindOfRole(kinds: ClusterKinds | undefined, role: Role): string {
  const kind = kinds?.find((k) => k.resourceClass === role);
  if (!kind) {
    throw new Error(`The cluster offers no ${role.toLowerCase()} kind`);
  }
  return kind.id;
}

/** The id of the subscription of this name; for a screen that holds a name and no row (a URL param, a picker). */
export const subscriptionId = (kinds: ClusterKinds | undefined, name: string): string =>
  resourceId(kindOfRole(kinds, 'SUBSCRIPTION'), name);

/** The id of the destination of this name, built as {@link subscriptionId} builds a subscription's. */
export const destinationId = (kinds: ClusterKinds | undefined, name: string): string =>
  resourceId(kindOfRole(kinds, 'DESTINATION'), name);
