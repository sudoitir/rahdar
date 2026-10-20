# plugin-runtime Specification

## Purpose
Lets an authorised operator extend an installed Rahdar with third-party plugins. A plugin contributes screens, API, assistant tools and its own data. It is installed, updated, rolled back and removed from the UI, and a faulty plugin can never take Rahdar down.

## Requirements

### Requirement: A plugin is one self-describing artifact

A plugin SHALL be delivered as a single jar containing a descriptor that states:
- its identifier, name, version, vendor and description
- its change notes
- its base package and root configuration
- the extension-contract version it was built against, and the range of Rahdar versions it supports (a minimum, and optionally a maximum)
- the other features it requires
- whether it has a UI
- whether activating it needs a restart
- an optional update URL
- the permissions, setting keys, stream topics and assistant tools it contributes
- whether it requires a license

The system SHALL read the descriptor without executing any code from the plugin.

A plugin identifier SHALL consist of at least two lowercase kebab segments, SHALL be at most 50 characters, and SHALL NOT begin with `identity-`.

#### Scenario: The descriptor is read without running plugin code
- **WHEN** a jar whose classes have static initialisers is inspected
- **THEN** its descriptor and contributions are reported and none of its code has run

#### Scenario: A single-segment identifier is refused
- **WHEN** a plugin declares the identifier `notes`
- **THEN** it is refused with a message that the identifier needs a vendor segment, for example `acme-notes`

#### Scenario: A license requirement is declared
- **WHEN** a plugin's descriptor states that it requires a license
- **THEN** the review before activation and the plugin list say so

### Requirement: A plugin is inspected before anything is stored or run

The system SHALL validate an uploaded plugin and SHALL refuse it, listing every violation in plain language together with what the author must change, when:
- the descriptor is invalid;
- the jar contains an entry outside its declared base package, its own changelog directory or its own descriptor directory;
- the jar's manifest declares a class path, an agent, module opens or exports, or native access;
- the archive exceeds 50 MB compressed or 250 MB uncompressed, holds more than 20 000 entries, has an entry compressed beyond 100:1, contains duplicate, absolute, backslash or parent-relative entry names, or nests an archive;
- its classes overlap Rahdar's own packages, a platform library's or another plugin's;
- a contribution falls outside the plugin's namespace;
- it was built against a different contract version;
- the running Rahdar version is outside its supported range;
- it uses a forbidden construct: process exit or halt, process execution, thread stop, changing the context class loader, storing into the HTTP session, its own scheduling or async annotations, auto-configuration, or component scanning outside its package;
- a database changeset is non-transactional, or differs from one already applied.

A refused plugin SHALL leave nothing stored.

#### Scenario: Every violation is listed
- **WHEN** a jar has an entry outside its package and was built for another contract version
- **THEN** the upload is refused with `422` and both violations are listed, each with the change the author must make

#### Scenario: An incompatible Rahdar version is named
- **WHEN** a plugin requires a newer Rahdar than the one running
- **THEN** the refusal names the plugin's minimum version and the running version

#### Scenario: A zip-slip entry is refused
- **WHEN** a jar contains an entry named `../../etc/passwd`
- **THEN** the upload is refused and nothing is written outside the system's temporary storage

### Requirement: Plugin contributions live in reserved namespaces

A plugin's contributions SHALL be confined to its identifier's namespace, so that no plugin can collide with a built-in feature, a future built-in feature, or another plugin:
- permissions `<id>:…`
- setting keys `<id>.…`
- stream topics `<id>` or `<id>.…`
- assistant tools, resources and prompts `<id_with_underscores>_…`
- HTTP paths under `/api/v1/p/<id>/` or `/api/v1/clusters/{clusterId}/p/<id>/`
- configuration properties under `rahdar.plugins.<id>.`
- UI routes under `/p/<id>/` or `/clusters/{clusterId}/p/<id>/`

#### Scenario: A plugin cannot claim a core permission
- **WHEN** a plugin declares the permission `subscription:purge`
- **THEN** it is refused as outside its namespace

### Requirement: Only installers manage plugins, interactively and freshly authenticated

The following actions SHALL be restricted to users in the installer tier:
- uploading, activating, updating and rolling back a plugin
- enabling, disabling and uninstalling a plugin
- purging a plugin's data
- changing who is an installer

Each of these actions except the upload SHALL additionally require that the user authenticated within the last 5 minutes. When that is not the case the system SHALL answer `403` with problem type `reauthentication-required`.

Callers authenticated by an API token, including the assistant surface, SHALL be refused with `403` problem type `plugin-install-interactive-only`.

At most one activation SHALL be in progress at a time, and a user SHALL be limited to 5 uploads per hour. Past that limit the system SHALL answer `429`.

Operators SHALL be able to turn plugin installation off at deploy time. The installing controls SHALL then remain visible, disabled, with the reason.

#### Scenario: A stale session cannot activate
- **WHEN** an installer who signed in an hour ago confirms an activation
- **THEN** the request is refused with `403 reauthentication-required`, and succeeds after the installer re-authenticates

#### Scenario: A token cannot install
- **WHEN** a request authenticated by a personal API token uploads a plugin
- **THEN** it is refused with `403 plugin-install-interactive-only`

### Requirement: Uploading shows what a plugin will be able to do before it is activated

Uploading SHALL store the plugin inert, as pending, and SHALL present a review. The review SHALL show:
- the plugin's identity and version
- its compatibility
- what it will contribute, in words: screens, API paths, assistant tools with their read or write posture, permissions, a new data schema and its tables, and dependencies
- the activation class that confirming will trigger, with its expected downtime

For an update the review SHALL also show:
- what is added and removed
- how many roles grant a permission the new version removes
- the change notes
- the database changes as generated SQL
- whether those changes can be reversed

Activation SHALL be confirmed by typing the plugin's identifier.

#### Scenario: A removed permission's impact is counted
- **WHEN** an update removes a permission that two roles grant
- **THEN** the review states that two roles grant it and will lose it

#### Scenario: An irreversible database change is flagged
- **WHEN** an update contains a changeset without a rollback
- **THEN** the review marks it irreversible, states that rollback will not be available, and advises taking a backup first

### Requirement: Every plugin change is classified and never interrupts more than it states

Before confirmation the system SHALL classify every activation, update, enable, disable, uninstall and rollback as one of the following, and SHALL behave as the classification states:

- **Instant.** No pending database changes. The new version starts while the old one keeps serving; only once the new version is ready are requests switched to it, and the old version then finishes its in-flight work and stops. If the new version fails to start, the old version keeps serving.
- **Brief maintenance.** Pending database changes. The plugin alone answers `503` with problem type `plugin-updating` and a `Retry-After` header while its in-flight work drains, its database changes apply, and the new version starts. The rest of Rahdar is unaffected.
- **Restart.** The plugin declares that it needs a restart, or a previous version did not stop cleanly. The system SHALL record the version to start and mark the plugin as needing a restart; the next start of Rahdar starts it. The review SHALL state beforehand whether Rahdar will restart itself or the operator must, with the exact command in the latter case.

#### Scenario: An update without database changes has no downtime
- **WHEN** an installer updates a plugin whose new version has no pending changesets
- **THEN** requests to the plugin are answered throughout, and after the switch they are served by the new version

#### Scenario: A failed start keeps the old version serving
- **WHEN** the new version of a plugin fails to start
- **THEN** the previous version keeps serving, and the plugin is shown as failed with the cause

#### Scenario: Database changes pause only that plugin
- **WHEN** an update with pending changesets is activated
- **THEN** that plugin's API answers `503 plugin-updating` with `Retry-After` until the new version is active, while every other screen and API keeps working

### Requirement: Rahdar restarts itself for a plugin only when something will start it again

The system SHALL restart itself only by exiting gracefully — every plugin stopped, the stop recorded as clean — with a non-zero exit code, and only when it is supervised: `rahdar.plugins.restart.supervised` is true, or, when that is unset, it runs on Kubernetes. When it is not supervised it SHALL refuse to exit and state the command to run. It SHALL NOT offer a restart or shutdown over an actuator endpoint.

It SHALL restart itself automatically after an activation whose review said it would, once the version to start is recorded. An installer SHALL also be able to request a restart. That request requires a browser session, re-authentication within 5 minutes, and an audit record, and SHALL be refused within 2 minutes of Rahdar starting.

The system SHALL report a stopped plugin version whose classes are still in memory a minute after it stopped, and SHALL mark a restart as needed while any such version, or any plugin needing a restart, exists.

#### Scenario: A confirmed restart-class plugin is running after the restart
- **GIVEN** Rahdar is supervised
- **WHEN** an installer confirms a plugin that declares it needs a restart
- **THEN** Rahdar stops gracefully, is started again by its supervisor, and the plugin is active

#### Scenario: An unsupervised Rahdar never stops itself
- **GIVEN** Rahdar is not supervised
- **WHEN** a plugin needs a restart
- **THEN** Rahdar keeps running, and the plugin shows as needing a restart with the command to run

#### Scenario: Restarts cannot be chained
- **WHEN** an installer requests a restart less than 2 minutes after Rahdar started
- **THEN** the request is refused with the time it will be allowed

### Requirement: A plugin's data is isolated and never constrains Rahdar

Each plugin SHALL keep its data in its own database schema, reached through its own bounded set of database connections. An activation SHALL fail, and the previous version SHALL resume, when a plugin's database changes:
- create any object in Rahdar's own schema; or
- create a foreign key from the plugin's schema to Rahdar's schema.

A plugin SHALL be refused activation with a stated reason when activating it would take the installation's database connections above 80 % of the server's limit.

#### Scenario: A plugin cannot block cluster deletion
- **WHEN** a plugin's changeset declares a foreign key to the cluster table
- **THEN** the activation fails with a message that plugins may not reference Rahdar's tables, and the previous version resumes

### Requirement: Database changes fail safely

A failing plugin changeset SHALL NOT be left half-applied.

When a fresh activation fails after its database changes applied:
- if every applied change declared a rollback, the changes SHALL be rolled back and the previous version SHALL resume;
- otherwise the plugin SHALL be marked failed with "schema at version X", and retry, upload a fix, and uninstall SHALL be offered.

A lock left behind by an interrupted migration SHALL be released safely on the next activation.

A migration SHALL wait at most 10 seconds for a database lock before failing.

#### Scenario: A reversible failure resumes the previous version
- **WHEN** version 1.5 applies reversible changes and then fails to start
- **THEN** the changes are rolled back and version 1.4 serves again

### Requirement: Rollback returns to the previous code

The system SHALL keep the previous artifact of each plugin and SHALL offer rolling back to it when the current version applied no database changes. When database changes were applied, the rollback control SHALL be disabled and SHALL state why.

#### Scenario: Rollback after a schema change is explained
- **WHEN** the current version changed the database
- **THEN** the rollback control is disabled and states that the database changed and a fixed version must be uploaded or a backup restored

### Requirement: Rahdar always starts, whatever its plugins do

A plugin SHALL NEVER prevent Rahdar from starting or serving its built-in features. The system SHALL quarantine a plugin, showing the reason and a fix action, when:

- it is invalid;
- the running Rahdar version is outside its supported range: the plugin is shown as incompatible;
- it fails, or takes longer than 60 seconds, to start;
- its stored artifact fails an integrity check.

A crash during a plugin's activation SHALL mark that plugin failed on the next start, rather than retrying it. After 3 unclean stops within 15 minutes the system SHALL start with no plugin running, and SHALL say so to administrators.

Operators SHALL be able to force this safe mode at deploy time.

#### Scenario: An incompatible plugin after a Rahdar upgrade
- **WHEN** Rahdar is upgraded past a plugin's maximum supported version
- **THEN** Rahdar starts, the plugin is not started, and it is shown as incompatible with Update and Uninstall offered

#### Scenario: A crash loop ends in safe mode
- **WHEN** Rahdar stops uncleanly three times within fifteen minutes
- **THEN** the next start runs no plugin and administrators see a banner explaining safe mode

### Requirement: A plugin's security annotations are enforced or the plugin does not run

Method-security, transaction and validation annotations in a plugin SHALL be enforced exactly as in a built-in feature. If any annotated component would not be enforced, the plugin SHALL NOT be activated.

#### Scenario: A method-security rule in a plugin is enforced
- **WHEN** a plugin endpoint requires a permission the caller lacks
- **THEN** the call is refused with `403`

### Requirement: Updates can be found and installed from the UI

An installer SHALL be able to update a plugin by uploading a newer jar, or by asking Rahdar to check the update URL each plugin declares.

Checking SHALL happen only on request. The check SHALL:
- use HTTPS only, with no redirects and a 5-second timeout;
- read at most 64 KB of metadata and 50 MB of artifact;
- compare the downloaded artifact's SHA-256 with the digest the metadata published, and refuse a mismatch.

A downloaded update SHALL go through the same inspection and review as an upload.

A downgrade, or a jar from a different vendor under an existing identifier, SHALL be refused.

#### Scenario: A mismatched download is refused
- **WHEN** the downloaded jar's SHA-256 differs from the one the update metadata states
- **THEN** the update is refused and nothing is stored

### Requirement: Removing a plugin keeps its data until purged

Disabling or uninstalling a plugin SHALL keep its data, grants and settings; they SHALL be inert.

Purging a plugin SHALL:
- require that the plugin is uninstalled;
- be previewable (dry run), estimating its tables, rows and size;
- be confirmed by typing its identifier;
- remove its schema, its grants, its settings and its artifacts;
- be audited.

A plugin that other active plugins require SHALL be disabled only together with them, and they SHALL be listed before confirmation.

#### Scenario: Reusing an identifier after purge starts clean
- **WHEN** a plugin is purged and a plugin with the same identifier is later installed
- **THEN** it inherits no grants, settings or data from the purged one

### Requirement: Every plugin action is audited and visible

Every plugin lifecycle action SHALL be recorded in the audit log with:
- the actor and source address
- the plugin identifier, versions and artifact digest
- the outcome

It SHALL also be written to the application log.

Administrators SHALL see a banner for every plugin that is pending, failed, incompatible or needs a restart, and while safe mode is active.

#### Scenario: A failed activation is visible to every administrator
- **WHEN** an activation fails
- **THEN** every administrator sees a banner naming the plugin, the cause and who started it

### Requirement: A plugin's UI joins the console without being able to break it

An active plugin with a UI SHALL contribute its routes, navigation entries, palette actions, slot contributions and stream-topic handlers to the console as a built-in feature does, within its namespace and the existing navigation groups.

A plugin UI that fails to load, or a plugin component that throws, SHALL NOT affect built-in screens. A plugin's address SHALL explain whether the plugin is:
- not installed
- disabled
- updating
- failed
- incompatible

After plugins change, the console SHALL offer a reload without forcing one.

#### Scenario: A broken plugin panel does not break the subscription drawer
- **WHEN** a plugin's subscription-drawer panel throws while rendering
- **THEN** the drawer renders its own content and shows that the plugin's panel failed

#### Scenario: A disabled plugin's address explains itself
- **WHEN** an operator opens the address of a disabled plugin's screen
- **THEN** the page states that the plugin is disabled and, for an installer, links to its entry in Admin → Plugins

### Requirement: Plugin authors have a published, versioned kit

The system SHALL publish, with every release:
- the Java API plugins compile against, with its supported types marked;
- a UI kit with types and a build preset;
- a verifier that runs the same inspection as upload.

A binary-incompatible change to the marked Java API SHALL NOT be released without incrementing the extension-contract version.

The release process SHALL build, install, update and roll back a reference plugin against the release candidate.

#### Scenario: An unannounced API break fails the build
- **WHEN** a marked API type changes incompatibly and the contract version is unchanged
- **THEN** the build fails

### Requirement: A plugin's row actions are namespaced and sectioned, and links stay Rahdar's

A plugin MAY contribute actions to a resource's row menu, on a target named by role (destination, subscription, route, connection, channel, consumer, producer, message). Each such contribution SHALL be
named with the plugin's id as its prefix and SHALL name one of the menu's sections. A
failure inside it SHALL stay inside it.

A plugin SHALL NOT contribute the link that another view uses for a built-in resource. A
plugin bundle that does either of these things SHALL be refused, and the reason SHALL be
stated.

#### Scenario: A plugin tries to own subscription links

- **WHEN** a plugin bundle contributes to the subscription link slot
- **THEN** it is refused, stating that links to built-in resources are Rahdar's own

### Requirement: A plugin's assistant tools declare the permission Rahdar enforces

Each assistant tool a plugin declares SHALL name a `permission` (one of the plugin's own permissions) and a `scope` (`resource`, `cluster` or `global`), and MAY declare `params: [{name, values?, shape?, note?}]`. A `resource` tool SHALL also name the argument that holds the resource name and its resource type (subscription or destination). Its `posture` SHALL be `read` or `write`. Before a plugin tool runs, Rahdar SHALL check the caller's permission, as follows:
- a `cluster` tool's `clusterId` argument is parsed (a missing or malformed one is a malformed call, -32602), and the permission is required on that cluster. A denial hides the cluster exactly as Rahdar's own tools do.
- a `resource` tool's `clusterId` and resource arguments are parsed the same way, and the permission is required on that resource. A denial hides the resource exactly as Rahdar's own tools do.
- a `global` tool's permission is required globally, and a denial names it.

The plugin's code SHALL NOT run when the check fails. Activation SHALL be refused, naming the tool, when:
- the registered and declared tools differ;
- a `cluster` or `resource` tool takes no required string argument `clusterId`, or a `resource` tool no required string argument for its resource;
- a tool's `readOnlyHint` is not true exactly when its posture is `read`;
- a tool's scope does not match its permission's scope.

#### Scenario: A tool without a permission is refused
- **WHEN** a plugin declares an assistant tool with no `permission`, or one it does not declare
- **THEN** the install is refused, naming the tool

#### Scenario: A denied cluster tool hides the cluster
- **WHEN** a key without a grant on a cluster calls a plugin's cluster tool for it
- **THEN** the answer is "No such cluster, or this key has no grant on it", and the plugin's method was not invoked

#### Scenario: A denied resource tool hides the resource
- **WHEN** an Orders-only key calls a plugin's resource tool for subscription `billing.in`
- **THEN** the answer is the same as for a subscription that does not exist, and the plugin's method was not invoked

#### Scenario: A denied global tool names the permission
- **WHEN** a key without `acme-notes:admin` calls a global tool that declares it
- **THEN** the answer names `acme-notes:admin`, and the plugin's method was not invoked

#### Scenario: An allowed call reaches the plugin
- **WHEN** the key's owner holds the declared permission on the cluster
- **THEN** the tool runs with the plugin's class loader

#### Scenario: A read tool that is not read-only is refused
- **WHEN** a tool declared `read` is annotated `readOnlyHint = false`
- **THEN** activation is refused, naming the tool

### Requirement: Discovery shows each plugin tool's access and parameters

`rahdar_help` (its index and each topic) and `rahdar://tools` SHALL show every plugin tool's permission and scope, and each declared parameter's values, shape and note.

#### Scenario: Help shows the permission
- **WHEN** `rahdar_help` is called with a plugin tool's name
- **THEN** it returns the tool's permission, scope and declared parameters

### Requirement: The SDK offers a code editor

The plugin SDK SHALL export `CodeEditor`, which edits YAML or JSON with Rahdar's editor and colours. It SHALL take:
- a value;
- a change handler (without one it is read-only);
- a language;
- diagnostics located by 1-based line and column with a severity;
- a visible label that is its accessible name;
- an optional maximum height;
- optional line wrapping.

It SHALL summarise the diagnostics in a live region. It SHALL highlight keys, values, numbers and booleans, comments, punctuation and document markers distinctly, from Rahdar's colour tokens. Each token SHALL meet 4.5:1 contrast in both colour schemes. It SHALL fold blocks, match brackets and search.

Tab SHALL indent. Escape followed by Tab SHALL move focus out of the editor, and the editor SHALL say so. Past the maximum height the editor SHALL scroll inside itself, and a long line SHALL scroll horizontally within the editor, never past its container.

#### Scenario: Diagnostics are shown and announced
- **WHEN** a plugin passes an error for line 4, column 9
- **THEN** the editor marks that position and announces "1 error" with the first message

#### Scenario: A YAML document is highlighted
- **WHEN** the editor shows `queue: orders # the inbound queue`
- **THEN** the key, the value and the comment each carry a different highlight style

#### Scenario: Leaving the editor from the keyboard
- **WHEN** focus is in an editable editor and the operator presses Escape, then Tab
- **THEN** focus moves to the next control after the editor, and no tab character was inserted

#### Scenario: A long line stays inside a narrow container
- **WHEN** the editor inside a drawer shows a line wider than the drawer
- **THEN** the editor scrolls horizontally and the drawer does not

### Requirement: Plugins can read the Rahdar version and a cluster's name

Rahdar SHALL offer plugins a `@PluginApi` bean that gives the running Rahdar version, empty for a development build. It SHALL also give the display name of a cluster, empty unless the current caller holds a grant on that cluster.

#### Scenario: A plugin names its source
- **WHEN** a plugin asks for the version and the name of a cluster the caller can see, on a released Rahdar
- **THEN** it receives the release's version and the cluster's name

#### Scenario: A cluster the caller cannot see stays hidden
- **WHEN** a plugin asks for the name of a cluster on which the caller holds no grant
- **THEN** it receives nothing, exactly as for a cluster that does not exist

### Requirement: A plugin permission declares the scope it acts at

Each permission a plugin declares SHALL state `scope` as `global`, `cluster` or `resource`, and a
`resource` permission SHALL state `resourceTypes` (`subscription`, `destination`) and MAY state `requires`
(other permissions of the plugin or of Rahdar, such as `subscription:read`). A manifest with any other form, including the
removed `globalOnly` field and the removed `resourceKinds` field, SHALL be refused at install, naming the permission. Rahdar SHALL publish
for plugins a check of a permission on a named subscription or destination, a filter of a list by such a check,
and the same check for the console SDK; these SHALL give exactly the answers Rahdar's own checks give.

#### Scenario: A manifest with globalOnly is refused

- **WHEN** a plugin declaring a permission with `globalOnly: true` is uploaded
- **THEN** the upload is refused, naming the permission and the `scope` field to use

#### Scenario: A manifest with resourceKinds is refused

- **WHEN** a plugin declaring a permission with `resourceKinds` is uploaded
- **THEN** the upload is refused, naming the permission and the `resourceTypes` field to use

#### Scenario: A plugin checks a subscription

- **WHEN** a plugin checks its `resource` permission on `orders.in` for an Orders Team Operator whose team role holds it
- **THEN** the check succeeds, and fails for `billing.in`

### Requirement: Work that runs as its owner is checked on every run

A plugin's work that runs later as its owner SHALL declare to Rahdar the permissions and resources
it needs when it is published or enabled. Rahdar SHALL refuse the publish when the owner lacks any
of them, naming each, SHALL check them again before each run, and SHALL suspend the work with the
reason when the owner has lost one, resuming it only when the owner holds them again and someone
re-enables it.

#### Scenario: Publishing without send rights on a target

- **WHEN** a user publishes work that sends to `billing.in` without `message:send` on it
- **THEN** the publish is refused, naming `message:send` and `billing.in`

#### Scenario: Losing a right suspends the work

- **WHEN** the owner is removed from the team owning the work's source subscription
- **THEN** the work is suspended before its next run, and shows why

### Requirement: A plugin SHALL declare itself an approval provider

A plugin SHALL declare in its descriptor that it is an approval provider, naming the permission its approvers need, which the plugin itself declares. At most one approval provider SHALL be active. The gate SHALL count as installed whenever the provider is meant to be active, even while it is starting or has failed, so that gated operations fail closed. A held operation SHALL be named by its operation type in dot form (`subscription.delete`, `destination.delete`, `subscription.purge`), and a previewed effect SHALL carry an etag that the held action must still match when it runs.

#### Scenario: Second provider

- **WHEN** an administrator activates a second approval provider
- **THEN** activation is refused with the reason

#### Scenario: Provider failed to start

- **WHEN** the approval provider failed to start and an operator purges a subscription
- **THEN** the purge is not run and the operator is told approvals are unavailable

#### Scenario: A held action runs only on the state that was approved

- **WHEN** a held action's resource has changed since its effect was previewed
- **THEN** the action does not run, because the etag no longer matches
