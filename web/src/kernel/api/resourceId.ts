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

/** `/resources/{kind}/{path}` of a canonical id, ready to follow with `:pause`, `/messages` and the like. */
export function resourcePath(id: string): string {
  const slash = id.indexOf('/');
  return `/resources/${id.slice(0, slash)}/${encodeURIComponent(id.slice(slash + 1))}`;
}

/** The kind the Artemis driver gives a queue; a screen that has only a name composes its id from it. */
export const QUEUE_KIND = 'artemis.queue';

/** The kind the Artemis driver gives an address. */
export const ADDRESS_KIND = 'artemis.address';

export const queueId = (name: string): string => resourceId(QUEUE_KIND, name);

export const addressId = (name: string): string => resourceId(ADDRESS_KIND, name);
