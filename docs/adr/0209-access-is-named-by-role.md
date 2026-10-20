# ADR-0209: Access is named by role

- **Status**: accepted
- **Date**: 2026-10-20
- **Deciders**: Mahdi Amirabdollahi

## Context

Permission identifiers named Artemis concepts (`queue:read`, `address:create`, `divert:write`), and so
did the kinds stored with team patterns and shares (QUEUE, ADDRESS, BOTH), API token grants, and the
plugin contract's `ResourceKind`. ADR-0204 kept them so that a stored kind always matched the permission
it scopes, to be renamed together.

## Decision

- Permissions: `destination:read|create|delete`, `subscription:read|create|update|delete|pause|purge`,
  `route:write`. Gate operation types follow (`subscription.delete`, `destination.delete`,
  `subscription.purge`).
- Team patterns and shares cover `SUBSCRIPTION`, `DESTINATION` or `ANY`; token grants and plugin
  permissions are scoped by `ResourceType` (`DESTINATION`, `SUBSCRIPTION`), which replaces
  `ResourceKind`; `plugin.json` says `resourceTypes`.
- The baselines are edited in place (ADR-0192).

## Consequences

- Custom roles, team patterns, token grants and plugin manifests use the new names; the database is
  recreated.

## Alternatives considered

- **Keeping Artemis names as aliases**: two names for one right, which is what no backward
  compatibility forbids before the stable release.
