# public-api Specification

## Purpose
The HTTP API under `/api/v1` as a published, versioned contract that clients other than Rahdar's own UI can rely on: a versioning and deprecation policy, breaks caught in CI, one convention for lists, errors and limits, idempotent retries, generated clients, and tests that keep the document and the implementation in step.

## Requirements

### Requirement: The OpenAPI document is a versioned release artifact
Each release SHALL publish, among its GitHub release assets, the OpenAPI document of that version, named `rahdar-<version>.openapi.json`, with a SHA-256 checksum and build provenance. Its `info.version` SHALL be the release version. The running server SHALL serve the same document at `/v3/api-docs`, with `info.version` set to its own version.

#### Scenario: Release
- **WHEN** a version is released
- **THEN** its OpenAPI document is downloadable from the release, and it matches the document the running API serves

#### Scenario: Version discovery
- **WHEN** a client reads `/v3/api-docs`
- **THEN** `info.version` names the Rahdar version that serves it

### Requirement: Versioning and deprecation follow a documented policy
The API version SHALL be the path segment after `/api/` and SHALL be resolved by the server, which refuses unsupported versions. The documentation SHALL state how the API is versioned, how breaking changes are flagged and how endpoints are deprecated. A deprecated operation SHALL be marked `deprecated` in the document, and every response from it SHALL carry a `Deprecation` header (RFC 9745), a `Sunset` header (RFC 8594) and a `Link` header with `rel="deprecation"` pointing at its documentation.

#### Scenario: Deprecated endpoint
- **WHEN** a deprecated endpoint is called
- **THEN** the response carries the `Deprecation`, `Sunset` and `Link rel="deprecation"` headers
- **AND** the operation is marked deprecated in the document

#### Scenario: Unsupported version
- **WHEN** a request names an API version the server does not support, such as `/api/v9/clusters`
- **THEN** it is refused with 400 problem+json of type `invalid-api-version`

#### Scenario: Pre-stable break
- **WHEN** a breaking change lands before the stable release
- **THEN** its commit carries the breaking marker (`!` or `BREAKING CHANGE:`), and the change is listed under Breaking in the release notes

### Requirement: Breaking API changes are detected in CI
CI SHALL compare the pull request's API document with the document of main, which each release publishes. It SHALL fail on a breaking change the pull request introduces unless a commit in the pull request carries the breaking marker, and the failure SHALL name each breaking change. The same comparison SHALL be runnable locally.

#### Scenario: Unflagged break
- **WHEN** a pull request removes a response field and no commit carries the breaking marker
- **THEN** CI fails, naming the removed field and its operation

#### Scenario: Flagged break
- **WHEN** a pull request makes the same change and one of its commits carries the breaking marker
- **THEN** the compatibility check passes

#### Scenario: Compatible change
- **WHEN** a pull request only adds an optional field or a new endpoint
- **THEN** the compatibility check passes without a marker

### Requirement: List endpoints paginate one way
Every endpoint that returns a collection SHALL return the envelope `{data, page, pageSize, count, hasNext}` and accept `page` (1-based, default 1) and `size` (default 50, maximum 500). `count` MAY be null only where the total is unknown. No `/api/v1` endpoint SHALL return a bare JSON array. An out-of-range `page` or `size` SHALL be refused.

#### Scenario: Pagination
- **WHEN** a list is requested
- **THEN** the page size is bounded by 500, and `hasNext` tells whether another page exists

#### Scenario: Oversized page
- **WHEN** a list is requested with `size=501`
- **THEN** the request is refused with a problem of type `invalid-value`

### Requirement: Every error is problem+json with a stable type
Every error response from `/api/v1` SHALL be `application/problem+json` with a stable `type` under `https://rahdar.dev/problems/`. This SHALL hold for authentication and authorization failures, framework-level request errors, unknown routes and unexpected server errors. An unexpected server error SHALL NOT expose internal detail. The document SHALL describe the problem schema for every operation.

#### Scenario: Error shape
- **WHEN** any endpoint fails
- **THEN** the body is problem+json with a stable type

#### Scenario: Unauthenticated
- **WHEN** a request without credentials, or with an invalid bearer token, reaches `/api/v1`
- **THEN** the response is 401 with type `unauthenticated`

#### Scenario: Forbidden
- **WHEN** an authenticated caller lacks the permission for an operation
- **THEN** the response is 403 problem+json

#### Scenario: Malformed request
- **WHEN** a request has an unreadable body or an unsupported method
- **THEN** the response is 400 or 405 problem+json, not the server's default error page

### Requirement: Rate-limited responses carry standard headers
Every response to a rate-limited caller SHALL carry `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`. Every 429 response from `/api/v1` SHALL carry `Retry-After`. The document SHALL describe these headers and the 429 response.

#### Scenario: Limited
- **WHEN** a request is refused with 429
- **THEN** the response carries `Retry-After` and a problem+json body

### Requirement: Mutating endpoints accept idempotency keys
Every `POST`, `PUT`, `PATCH` and `DELETE` under `/api/v1`, except plugin-gateway routes, SHALL accept an `Idempotency-Key` header. The key SHALL be scoped to the calling user. A key SHALL be retained for 24 hours, and within that period a repeat of the same key and the same request (method, path, query and body) SHALL return the original status and body without applying the mutation again. A response that failed with a server error SHALL NOT be retained. The key's retention SHALL be governed with the other data stores.

#### Scenario: Retry
- **WHEN** a client repeats a request with the same key
- **THEN** the mutation is applied once and the same result returns, marked `Idempotent-Replayed: true`

#### Scenario: Key reuse with other body
- **WHEN** the same key is sent with a different request
- **THEN** it is refused with 422 and type `idempotency-key-reused`

#### Scenario: Concurrent repeat
- **WHEN** a repeat arrives while the first request with that key is still running
- **THEN** it is refused with 409 and type `idempotency-in-progress`

#### Scenario: Another caller's key
- **WHEN** a second user sends a request with a key the first user already used
- **THEN** the key is treated as the second user's own, and the first user's result is never returned

#### Scenario: Dry run and real run
- **WHEN** a client sends a real run with the key it used for the `dryRun=true` preview
- **THEN** the key is refused as reused, never answered with the preview's result

### Requirement: TypeScript and Java clients are generated and published
Each release SHALL publish a TypeScript client (`@rahdar/client` on npm) generated from that release's API document, versioned with the release, and published with provenance. A Java client (`io.github.sudoitir:rahdar-client`) generated from a release's API document SHALL be published to Maven Central with provenance when a maintainer publishes that release there, versioned with the release. Every release SHALL attach its API document.

#### Scenario: Client release
- **WHEN** a release is published
- **THEN** the TypeScript client of that version is available on npm and the release carries its API document

#### Scenario: Java client on Central
- **WHEN** a maintainer publishes a release to Maven Central
- **THEN** the Java client of that version is available there, with the plugin API when the backend changed since the newest version on Central

### Requirement: Contract tests keep implementation and document aligned
The build SHALL validate every JSON response produced in the API tests against the document's schema for that operation and status, and SHALL treat an undocumented property as a failure. The document SHALL be checked against a committed snapshot, and that snapshot SHALL hold only broker-neutral paths, fields, enum values and error codes outside the system namespaces.

#### Scenario: Drift
- **WHEN** an endpoint returns a field the document lacks
- **THEN** the contract tests fail

#### Scenario: A system word enters a shared path
- **WHEN** a shared route, field, enum value or error code in the document names a concept of one broker system
- **THEN** the contract tests fail, naming it

### Requirement: The API speaks the SDK's vocabulary
Shared REST resources SHALL be addressed by role, by kind and by the SDK's canonical resource name. Operations SHALL be custom methods on a resource. No shared route, field, enum value or error code SHALL name a concept of one broker system. System-only routes SHALL live under that system's namespace and SHALL exist only for clusters of that system.

#### Scenario: Listing subscriptions on two systems
- **WHEN** a client lists subscriptions on an Artemis cluster and on another system's cluster
- **THEN** both answers have the same shape, with each item's native kind and canonical name

#### Scenario: An Artemis-only route on another system
- **WHEN** a client calls the broker.xml configuration route for a cluster that is not Artemis
- **THEN** the route answers not found, and the cluster's capabilities never offered it

### Requirement: A resource is addressed by kind and path
A single resource SHALL be addressed as `/clusters/{cluster}/resources/{kind}/{path}`, where `{kind}` is the resource's kind and `{path}` is its canonical path percent-encoded into one segment. Names containing `/` or `%` SHALL be reachable. A path that is not a valid resource id SHALL receive a problem answer of type `invalid-resource-id`.

#### Scenario: A name containing a slash is reachable
- **WHEN** a client reads a subscription whose name contains `/`
- **THEN** the resource is returned, where it was unreachable before

#### Scenario: A malformed resource id
- **WHEN** a client addresses a resource with a path that is not a valid canonical id
- **THEN** the response is a problem of type `invalid-resource-id`

### Requirement: Operations beyond create, read, update and delete are custom methods
A verb beyond create, read, update and delete SHALL be a custom method written as a literal `:verb` suffix on the resource's segment (`:pause`, `:resume`, `:purge`, `:close`, `:reset-counters`), and a message operation SHALL be `…/messages:{action}`. Dry runs, overrides and node selection SHALL stay query parameters.

#### Scenario: Purging is a custom method
- **WHEN** a client purges a subscription
- **THEN** it posts to the resource's `:purge` method, and a dry run is the same request with `dryRun`

### Requirement: A cluster states its system and the kinds it offers
A cluster's summary and detail SHALL state its system by id and name, and the kinds it offers with each kind's role, label and verbs, including the verbs Rahdar adds for that system. Clients SHALL decide what to offer from this and from the cluster's capabilities, not from a system's name.

#### Scenario: Verbs per kind
- **WHEN** a client reads a cluster's detail
- **THEN** each kind lists the verbs it supports, so a client can tell that an Artemis address cannot be purged

### Requirement: Estate-wide subscriptions are listed in one call
The API SHALL offer one list of subscriptions across every cluster the caller may read, filterable by text and cluster, sortable and paged as other lists are, each row naming its cluster and system. It SHALL include only the clusters and names the caller may read.

#### Scenario: Subscriptions across clusters
- **WHEN** a client lists subscriptions without naming a cluster
- **THEN** rows from every readable cluster are returned, each with its cluster and system, and nothing from a cluster or name the caller may not read
