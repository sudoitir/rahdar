/**
 * A resource's canonical id is `<kind>/<segment>…`, each segment percent-encoded (ADR-0206):
 * `artemis.queue/orders`. The API names a subscription or a destination by this id wherever a field
 * holds a resource, so a screen that shows a name takes it from the id and a request that names one
 * builds it with the kind the cluster's system gave it.
 */

/** The id of the resource of this kind at this path. */
export function resourceId(kind: string, ...segments: string[]): string {
  return `${kind}/${segments.map(encodeURIComponent).join('/')}`;
}

/** The name an id carries: its last path segment, decoded. An id with no path is returned as it is. */
export function resourceName(id: string | null | undefined): string {
  if (!id) {
    return '';
  }
  const slash = id.indexOf('/');
  if (slash < 0) {
    return id;
  }
  const segments = id.slice(slash + 1).split('/');
  return decodeURIComponent(segments[segments.length - 1] ?? '');
}

/**
 * The id of the subscription of this name. A screen that holds a row from a list takes the row's id; one
 * that holds only a name (a queue typed into a picker, a drawer opened on a name) builds it here, with the
 * kind of the one system Rahdar reaches today.
 */
export function subscriptionIdOf(name: string): string {
  return resourceId('artemis.queue', name);
}

/** The id of the destination of this name, built as {@link subscriptionIdOf} builds a subscription's. */
export function destinationIdOf(name: string): string {
  return resourceId('artemis.address', name);
}
