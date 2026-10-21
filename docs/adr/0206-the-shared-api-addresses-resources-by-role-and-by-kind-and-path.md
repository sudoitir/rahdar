# ADR-0206: The shared API addresses resources by role, and one resource by kind and path

- **Status**: accepted
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

Rahdar reaches every broker through OpenBroker (ADR-0203) and stores what it keeps in the SDK's
vocabulary (ADR-0204), but its REST API still spoke Artemis: `/queues/{queueName}`, `/addresses`,
`/diverts`, `/sessions`, verbs as sub-resources (`/pause`, `/reset-counter`), and fields such as
`queueName`, `address` and `routingType`. Every system after Artemis would inherit those names, and a
view across clusters of several systems cannot be built on them. A resource's identity in the SDK is
its canonical id, `<kind>/<segment>…` with each segment percent-encoded (`artemis.queue/orders`).
Names that contain `/` or `%` were unreachable: Tomcat and Spring Security's firewall reject an encoded
slash or percent by default, and the UI encoded names with `encodeURIComponent`.

## Decision

- **Collections by role.** `GET /clusters/{c}/{destinations|subscriptions|routes|connections|channels|consumers|producers}`
  lists every kind with that role. Every item carries `id` (the canonical id), `kind`, `kindLabel`
  (the sentence-cased local part of the kind: `artemis.queue` is "Queue") and `name`; what a system says
  beyond the standard attributes is in `nativeAttributes`. `GET /subscriptions` lists subscriptions
  across every cluster the caller may read.
- **One resource by kind and path.** `/clusters/{c}/resources/{kind}/{path}`, where `{path}` is the
  resource path's canonical text encoded once more into one segment. Read, `PATCH` and `DELETE` act on
  it; its messages are under `…/messages`.
- **Custom methods** (AIP-136) are a `:verb` suffix on that segment: `:pause`, `:resume`, `:purge`,
  `:seek`, `:truncate`, `:reset-counters`, `:close`, and `messages:move|copy|delete|retry|expire`. A
  decoded path never holds a literal `:`, so the suffix is unambiguous.
- **Fields name resources by role and hold ids**: `subscription`, `destination`, `source`, `target`.
- **Encoded `/` and `%` pass.** Rahdar's firewall allows an encoded slash and percent, and Tomcat passes
  an encoded slash through undecoded. The operations guide says how to keep a reverse proxy from
  decoding them.
- **The queues module folds into resources.** One module owns every role list and every resource method.

## Consequences

- A client written against the old routes breaks; this is before the stable release (no backward
  compatibility). The OpenAPI document, the generated clients and the snapshot change with it.
- A resource whose name contains `/` or `%` is now reachable.
- A reverse proxy that decodes `%2F` breaks resources with multi-segment paths; it fails on the first
  request, not subtly, and the guide names the setting.
- The next system's resources appear in the same routes and shapes with nothing added.

## Alternatives considered

- **The id as a query parameter** (`/resource:pause?id=…`): robust behind any proxy, but not
  resource-oriented, awkward for messages under a resource, and worse to read in OpenAPI.
- **Role-specific item routes** (`/subscriptions/{id}`): the role is implied by the kind, so it would be
  stated twice, and a kind whose role changes between versions would move.
