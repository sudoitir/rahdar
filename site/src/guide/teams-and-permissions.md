# Teams and permissions

Rahdar decides who may see and do what with roles, granted for the whole installation, one
environment or one cluster, and with **teams**, which own subscriptions and destinations by name. Several
teams can share one broker cluster, and each sees and operates only what it owns.

## Roles and where a permission acts

A role is a set of permissions such as `subscription:read`, `message:send` or `subscription:purge`. Each
permission acts at one of three levels, shown as a badge in the role editor:

- **Global**: only a grant for the whole installation gives it (managing users, environments,
  settings).
- **Cluster**: a grant for the installation, the cluster's environment, or the cluster itself
  (broker configuration, closing connections, alerts).
- **Resource**: one subscription or destination. A grant gives it on every resource the grant covers; a team
  role or a share gives it only on the names the team owns or receives.

Kafka clusters add permissions of their own, which work like the rest: `subscription:seek` (reset a
group's offsets) and `destination:update` and `destination:truncate` (change and truncate a topic) act on
resources; `access-rule:read` and `access-rule:write` (ACLs) and `transaction:read` and `transaction:abort`
(transactions) act on the cluster. **Operator** gains `subscription:seek`, `destination:update`,
`destination:truncate`, `transaction:read`, `transaction:abort` and `access-rule:read`; **Administrator** also
`access-rule:write`; **Viewer** gains `transaction:read` and `access-rule:read`. What each does is on
[Apache Kafka](/guide/kafka#permissions).

Some permissions need others: `subscription:purge` needs `subscription:read`, `message:send` needs
`destination:read`. The role editor adds what is required and says so, and Rahdar refuses a role that
lacks it.

Grant roles to users or directory groups under **Settings → Access → Users** or **Group mapping**,
choosing the whole installation, an environment or a cluster. A change applies on the user's next
request, without signing in again.

Rahdar ships **Administrator**, **Operator** and **Viewer**, and three team roles: **Team Viewer**
(see and browse), **Team Operator** (send, move, delete, purge, pause, create inside the team's
names) and **Team Admin** (also manages the team's user members).

## Teams

Create teams under **Settings → Access → Teams**. A team has:

- **Patterns**: the subscription and destination names it owns on each cluster, in the broker's wildcard
  syntax: `.` separates words, `*` matches exactly one word, `#` any number. `orders.#` owns
  `orders.in` and `orders.eu.retry`. Two teams' patterns may not overlap on a cluster; the editor
  says which team and pattern a new one collides with, and shows how many subscriptions and destinations it
  matches today before you save.
- **Members**: users and directory groups, each with a team role. Adding a directory group can
  admit its users to Rahdar, so it needs `user:admin`; a team admin manages user members only, and
  never gives anyone more than they hold themselves.
- **Shares**: part of what a team owns, given to another team at a chosen team role, for example
  Orders sharing `orders.events.#` with Billing as Team Viewer.
- **Unowned**: the subscriptions and destinations on a cluster no team owns yet, with an action to assign
  them.

## What a team member sees

- Lists, counts and totals contain only what the member may read; another team's subscription answers
  "not found", exactly like a subscription that does not exist.
- Each subscription and destination shows the team that owns it.
- An action the member may not take on something they can see is shown disabled, with the missing
  permission and whom to ask. Target pickers offer only destinations they may send to; creating a
  subscription shows the names they may create under.
- Moving or transferring messages needs the right on both ends.
- Live updates, the audit trail, metrics, alerts, the SQL console, captures, API tokens and the MCP
  tools follow the same rules.

## Explaining access

**Access check** (on a user, under Users) answers, for a cluster and optionally one subscription or
destination, every permission the user holds there and where it comes from: a role grant, a team role
or a share. A subscription's **Access** panel shows its owner and who may act on it.

Every refused request is recorded in the audit trail, naming who, which permission and which
resource.

## For plugin authors

Plugin permissions declare their level too, and a plugin checks a subscription or destination through the
same resolver; see [Plugins](./plugins.md).
