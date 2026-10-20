# authorization Specification

## Purpose

Defines how permissions are granted to users, how a grant's scope (global,
environment, or cluster) is resolved against a specific request, and the
safeguards that keep the last administrator from being locked out.

## Requirements

### Requirement: Permissions are named strings grouped into roles

The system SHALL represent a permission as a string naming a resource and an action, and SHALL group permissions into named roles. A role SHALL support a wildcard permission that grants every action, and a wildcard scoped to one resource that grants every action on that resource. The system SHALL provide a read of every permission string the application checks, for use when building a role. That read SHALL be assembled from one catalogue: the permissions the installation's enabled features declare followed by those every active plugin declares. Each entry SHALL be attributed to the feature or plugin that declares it. Every reader of the catalogue, including the role editor, the API-key picker and the feature manifest, SHALL use that same catalogue.

A role MAY hold a permission of a feature that is currently disabled, or of a plugin that is inactive or removed. Holding it SHALL grant nothing while its owner is disabled or inactive: every permission check for it SHALL fail, whatever the role or wildcard it comes through. It SHALL take effect unchanged if the owner is enabled or activated again.

#### Scenario: A role wildcard grants all actions on a resource

- **WHEN** a role holds a resource-scoped wildcard permission
- **THEN** every action on that resource is permitted for a user holding that role at the matching scope

#### Scenario: The full-access wildcard grants everything

- **WHEN** a role holds the full-access wildcard permission
- **THEN** every permission check for a user holding that role at the matching scope succeeds

#### Scenario: The catalogue lists only enabled features' permissions

- **WHEN** the permission catalogue is read on an installation with one feature disabled
- **THEN** every permission of the enabled features appears, attributed to its feature, and none of the disabled feature's permissions appear

#### Scenario: A role keeps a disabled feature's permission

- **WHEN** a feature is disabled and a custom role holds one of its permissions
- **THEN** the role is unchanged, every check of that permission fails, and the permission is effective again once the feature is re-enabled

#### Scenario: A plugin's permissions can be granted

- **WHEN** a plugin that declares permissions is active and an administrator reads the catalogue
- **THEN** each of the plugin's permissions appears attributed to the plugin, and a role saved with them holds them

#### Scenario: A plugin activated while the editor is open

- **WHEN** a plugin is activated while an administrator has the role editor open, and the administrator reloads the editor
- **THEN** the plugin's permissions are offered

#### Scenario: A removed plugin's permissions are no longer offered

- **WHEN** a plugin is deactivated or removed
- **THEN** the catalogue no longer lists its permissions, and roles that hold them keep them unchanged

### Requirement: Three built-in roles exist and cannot be altered

The system SHALL provide built-in Administrator, Operator and Viewer roles and built-in Team Viewer, Team Operator and Team Admin roles, and SHALL prevent their permissions, name, or existence from being changed or deleted. Administrator SHALL hold every permission. Operator SHALL hold every catalogue permission that acts at cluster or resource scope except those that change broker configuration, settings, environments, users, roles, teams, tokens, plugins or data, or reveal governed content in clear. Viewer SHALL hold every read permission. Team Viewer SHALL hold the read permissions that act on a resource; Team Operator SHALL add every message and subscription permission that acts on a resource; Team Admin SHALL add `team:admin`. Whenever the catalogue gains a core permission, a test SHALL fail until each built-in role's set is updated or the permission is listed as deliberately excluded. Custom roles with any permission combination MAY be created, changed, and deleted.

#### Scenario: A built-in role cannot be edited

- **WHEN** a caller attempts to change the permissions of a built-in role
- **THEN** the request is rejected

#### Scenario: A custom role can be created

- **WHEN** an administrator creates a role with a chosen name and permission set
- **THEN** the role exists and can be granted to users

#### Scenario: Operator can manage subscriptions

- **WHEN** a user holds Operator on a cluster
- **THEN** the user may create, update, pause and delete subscriptions, write routes and close connections on that cluster

### Requirement: A grant applies at a global, environment, or cluster scope

The system SHALL allow a role to be granted to a user or a directory group at global scope, at the
scope of one environment, or at the scope of one cluster, and the console SHALL offer all three
scopes wherever a grant is made. A permission check for an action on a specific cluster SHALL
succeed if the user holds a grant of that permission at global scope, at the scope of the
environment containing that cluster, or at the scope of that cluster directly. A grant, a change to
a role, a team membership or a share SHALL take effect on the user's next request, without signing
in again.

#### Scenario: A global grant covers every cluster

- **WHEN** a user holds a role at global scope
- **THEN** the user's permission check succeeds for any cluster

#### Scenario: An environment grant covers its member clusters only

- **WHEN** a user holds a role scoped to one environment
- **THEN** the user's permission check succeeds for clusters in that
  environment and fails for clusters outside it, and the console offers the user's actions on those clusters

#### Scenario: A cluster grant does not extend to other clusters

- **WHEN** a user holds a role scoped to one cluster
- **THEN** the user's permission check fails for a different cluster, even one
  in the same environment

#### Scenario: A new grant applies without re-login

- **WHEN** an administrator grants a signed-in user a role on a cluster
- **THEN** the user's next request on that cluster is allowed what the role holds

### Requirement: Every mutating and cluster-scoped operation is permission-checked

The system SHALL check the calling principal's grants against the specific permission an operation
requires before performing it, and SHALL check a read of cluster-scoped data against a read
permission before returning it. An operation on a broker resource SHALL be checked against that
resource, as the team-access capability defines. A principal without the required permission at
the resolved scope SHALL receive a `403` response, except where a cluster or resource it may not
read is addressed, which SHALL receive a not-found response. A build-time test SHALL fail when an
operation that takes a cluster, subscription or destination has no check.

#### Scenario: A permitted action succeeds

- **WHEN** a user holding the required permission at the target cluster's
  scope performs that action
- **THEN** the action is performed

#### Scenario: An unpermitted action is rejected

- **WHEN** a user lacking the required permission at the target cluster's
  scope attempts that action
- **THEN** the response is `403` and the action is not performed

#### Scenario: A subscription's configuration needs read permission

- **WHEN** a signed-in user without read access to a subscription requests the resource
  with its attributes
- **THEN** the response is not found

### Requirement: Cluster listings are filtered to what the caller may see

The system SHALL return, from any cluster listing or cross-cluster summary,
only clusters for which the caller holds a read grant at the resolved scope or on which a team of
theirs owns or receives a pattern. Summary figures SHALL count only resources the caller may read.
A caller with no grant on a specific cluster SHALL receive a not-found response, not a forbidden
response, when addressing that cluster directly.

#### Scenario: List omits ungranted clusters

- **WHEN** a user holding a grant on only one of several registered clusters
  requests the cluster list
- **THEN** only that cluster is returned

#### Scenario: Direct access to an ungranted cluster is not found

- **WHEN** a user with no grant on a specific cluster requests it directly by
  id
- **THEN** the response is a not-found response

#### Scenario: A team member sees the team's clusters

- **WHEN** a user's only access is membership of a team owning patterns on prod
- **THEN** the cluster list holds prod, and prod's totals count only the team's resources

### Requirement: The last global administrator cannot be removed

The system SHALL prevent disabling, deleting, or removing the full-access
grant from the last enabled user holding a full-access grant at global scope,
and SHALL prevent a user from removing their own administrative grant.

#### Scenario: Disabling the sole administrator is rejected

- **WHEN** an administrator attempts to disable the only other enabled
  global-administrator account, leaving none, or attempts to disable their
  own account as the sole administrator
- **THEN** the request is rejected

#### Scenario: An administrator cannot revoke their own administrative grant

- **WHEN** an administrator attempts to remove their own full-access grant
- **THEN** the request is rejected

### Requirement: Subscription and destination lifecycle permissions

The system SHALL define distinct permissions, named by role (`subscription:*` and
`destination:*`), for creating a subscription or destination, destroying a
subscription or destination, updating a subscription's configuration, and pausing or
resuming a subscription. Each SHALL be resolvable at global, environment, or cluster
scope through the existing scope walk, and SHALL appear in the permission
catalogue with a human label so the role editor presents it without a client-side
change. No permission name SHALL be one system's word for the resource.

Holding a message-level permission SHALL NOT imply any lifecycle permission.
Purging a subscription and destroying a subscription are different authorities: one
empties a resource the operator keeps, the other removes the resource itself.

#### Scenario: Purge does not imply destroy

- **WHEN** a role holds the subscription-purge permission but not the destroy permission
- **THEN** its holder can purge a subscription and cannot destroy one

#### Scenario: A lifecycle permission is scoped like every other

- **WHEN** a role is granted the create permission on a single environment
- **THEN** its holder can create subscriptions on clusters in that environment and not on
  clusters outside it

#### Scenario: A new permission needs no client change to be assignable

- **WHEN** the role editor is opened after the lifecycle permissions are added
- **THEN** each appears with its label and can be granted

### Requirement: Connection control permission

The system SHALL define a permission for closing connections, channels and a
destination's consumers, resolvable at global, environment, or cluster scope through
the existing scope walk, and listed in the permission catalogue with a human label.

This permission SHALL be independent of every message permission. Authority over a
cluster's messages does not imply authority to disconnect the applications
producing and consuming them.

#### Scenario: Independent of message authority

- **WHEN** a role holds message send, move, delete and subscription purge, and not the
  connection-close permission
- **THEN** its holder cannot close a connection

#### Scenario: Scoped like every other permission

- **WHEN** the connection-close permission is granted on one cluster
- **THEN** its holder can close connections on that cluster and on no other

### Requirement: Route write permission

The system SHALL define a permission for creating and deleting routes (`route:write`),
resolvable at global, environment, or cluster scope through the existing scope walk,
and listed in the permission catalogue with a human label.

Viewing routes SHALL require only the permission to view the cluster.
No permission SHALL grant mutation of a bridge, because no such operation exists.

#### Scenario: Viewing needs no additional grant

- **WHEN** a caller who may view a cluster opens its routes view
- **THEN** the routes are listed

#### Scenario: Creating needs the route permission

- **WHEN** a caller without the route permission attempts to create a divert
- **THEN** the request is refused

### Requirement: Capture write permission

The system SHALL define a permission for creating, modifying and deleting message capture
subscriptions, resolvable at global, environment, or cluster scope through the existing
scope walk, and listed in the permission catalogue with a human label.

This permission SHALL be distinct from the permission that governs Rahdar's own settings.
Capture mutates broker routing configuration and creates a stored copy of application
payload; a grant that lets an operator change Rahdar's configuration SHALL NOT thereby let
them tap a production address.

Querying captured messages SHALL require the same message read permission, scoped the same
way, as querying a broker directly.

#### Scenario: Capture needs its own grant

- **WHEN** a caller holding the settings write permission but not the capture permission attempts to create a capture subscription
- **THEN** the request is refused

#### Scenario: Reading captured payload needs the message read permission

- **WHEN** a caller without message read permission on a cluster queries its captured messages
- **THEN** the request is refused

### Requirement: Broker configuration permissions

The system SHALL define a permission for editing a cluster's declared configuration and
a distinct permission for applying it to brokers, each resolvable at global, environment,
or cluster scope through the existing scope walk, and each listed in the permission
catalogue with a human label. Both apply to Artemis clusters only, as the declaration is
Artemis broker configuration.

Viewing a declaration, its drift and its history SHALL require only the permission to
view the cluster.

The apply permission SHALL be a create-and-update authority: it covers creating declared
addresses, queues and diverts and adding or replacing address and security settings. It
SHALL NOT grant destroying a queue or an address, which remain governed by the subscription
and destination lifecycle permissions.

#### Scenario: Declaring is not applying

- **WHEN** a caller holding the configuration write permission but not the apply permission previews an apply
- **THEN** the request is refused

#### Scenario: Applying never destroys a queue

- **WHEN** a caller holding only the apply permission applies a declaration from which a queue was removed
- **THEN** the queue is untouched

### Requirement: Content governance permissions

The system SHALL define three permissions:

- `message:clear` shows sensitive message values in clear at the granted scope;
- `governance:read` reads masking rules, findings and re-mask progress;
- `governance:write` changes rules and confirms or dismisses findings.

Wildcard grants SHALL include these permissions as they include any other. The built-in operator and viewer roles SHALL NOT include `message:clear` or `governance:write`. Holding the message read permission SHALL NOT imply `message:clear`.

#### Scenario: Message read does not imply clear access

- **WHEN** a user whose only message permission is `message:read` opens a message holding an email address
- **THEN** the email is masked

#### Scenario: A wildcard grant includes clear access

- **WHEN** a user holding `*` at global scope opens that message
- **THEN** the email is shown in clear and the clear view is audited

### Requirement: Plugin management belongs to an installer tier that roles cannot grant

The ability to install, activate, update, roll back, enable, disable, uninstall and purge plugins, and to change who holds that ability, SHALL be held by an installer tier kept separately from roles and permissions. The tier SHALL NOT be conferred by any role, including a role holding the full-access wildcard, and SHALL NOT be grantable through role or user editing.

The first installer SHALL be the administrator bootstrapped on a fresh instance, or the users the operator names in deploy-time configuration. Only an installer SHALL add or remove installers, and only with fresh authentication; each change SHALL be audited.

Membership SHALL be checked on every request, so a removal SHALL take effect on the user's next request. The system SHALL tell the client whether the current user is an installer rather than leaving the client to infer it.

#### Scenario: A full-access administrator is not an installer by default

- **WHEN** a user holding the full-access wildcard who is not an installer uploads a plugin
- **THEN** the request is refused with `403`

#### Scenario: A custom role cannot confer installing

- **WHEN** an administrator creates a role holding any permission string and assigns it to themselves
- **THEN** they still cannot install plugins

#### Scenario: Removing an installer takes effect immediately

- **WHEN** an installer is removed while their session is active
- **THEN** their next plugin request is refused

### Requirement: A permission has an owner, a description and the scopes at which it takes effect

Every catalogue entry SHALL state its owning module or plugin, a human description, the scope it acts at (`global`, `cluster` or `resource`), the resource types a `resource` permission applies to (`subscription`, `destination`), and the permissions it requires. A `global` permission SHALL take effect only through a global grant; a `cluster` permission through a global, environment or cluster grant; a `resource` permission through any grant, a team role or a share. A permission's required permissions SHALL be held wherever it is: the role editor SHALL add them with a note when the permission is chosen, and saving a role without them SHALL be refused, naming what is missing. The consistency check SHALL report an entry whose required permission is not in the catalogue, or whose requirements form a cycle.

#### Scenario: An entry lacks a description

- **WHEN** a registered permission has no description
- **THEN** the permission consistency check reports it

#### Scenario: A global-only permission granted at cluster scope

- **WHEN** a role holding a global permission is granted to a user on one cluster
- **THEN** the access check marks that permission as having no effect at that scope, and the role editor marks the permission as global

#### Scenario: A required permission is added

- **WHEN** an administrator adds `subscription:purge` to a role that lacks `subscription:read`
- **THEN** the editor adds `subscription:read` and says why

#### Scenario: A role missing a required permission is refused

- **WHEN** a role holding `subscription:purge` without `subscription:read` is saved through the API
- **THEN** the save is refused, naming `subscription:read`

### Requirement: Effective permissions can be previewed for a user

Rahdar SHALL compute and return the effective permissions of a chosen user: for each of the user's role grants, team memberships and shares, every permission it gives, at its scope, and its source. A wildcard SHALL be expanded to the catalogue entries it matches, naming the wildcard it came through. Rahdar SHALL also answer an access check for a user, a cluster and an optional subscription or destination name: for each permission, whether it is allowed there and every source that allows it. A subscription or destination detail SHALL show its owning team and which teams and roles may act on it. These reads SHALL require the permission to administer users.

#### Scenario: Preview for a user

- **WHEN** an authorised administrator previews a user
- **THEN** the result lists each effective permission with its scope and source role, team or share

#### Scenario: A wildcard is expanded

- **WHEN** the user holds a role with a resource-scoped wildcard
- **THEN** the preview lists each catalogue permission of that resource, each naming the wildcard as its source

#### Scenario: Access check on a subscription

- **WHEN** an administrator checks a user on prod for subscription `orders.in`
- **THEN** each permission is listed as allowed or not, and an allowed one names its source, for example "team Orders, Team Operator"

#### Scenario: Preview without permission

- **WHEN** a caller lacks the permission to administer users
- **THEN** the preview is refused and reveals nothing about the user, not even whether the user exists

### Requirement: Two roles can be compared

Rahdar SHALL show the permissions that differ between two roles, marking which role holds each.

#### Scenario: Diff of two roles

- **WHEN** two roles are compared
- **THEN** permissions held by only one are listed against that role, and permissions held by both are not listed
