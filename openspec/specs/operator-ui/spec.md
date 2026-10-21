# operator-ui Specification

## Purpose
How Rahdar's operations console behaves for an operator about to act: blast radius before destructive actions, the four outcomes of a mutation, per-node results, honest capability gating, forms and empty states.

## Requirements

### Requirement: A destructive action states its blast radius before it can be armed

Before a destructive operation can be confirmed, the system SHALL state what will be
affected in concrete terms — the resource, the nodes, and the count of data that
will be destroyed — and SHALL NOT present a confirmation whose text is generic.

The confirmation SHALL be a button the operator presses and holds until it fills, and
SHALL act only when it is full. The button SHALL name the action and its count. A
click, a double click, or a press let go early SHALL do nothing, and the hold SHALL be
available from the keyboard. It SHALL NOT be confirmed by typing a name, a checkbox, a
second click, or a countdown.

Where an estimate is unavailable, the confirmation SHALL say that the count is
unknown rather than omitting it, because an absent number reads as zero.

#### Scenario: The consequence is named, not implied

- **WHEN** an operator is asked to confirm a destructive operation
- **THEN** the confirmation names the resource, the nodes it will apply to, and how
  much data will be destroyed

#### Scenario: An unknown count is stated as unknown

- **WHEN** the affected count cannot be estimated
- **THEN** the confirmation says so rather than omitting the figure

#### Scenario: Confirmation requires a hold

- **WHEN** an operator clicks the confirming control, or lets go before it is full
- **THEN** nothing is done, and the control says to keep holding

### Requirement: Every mutation renders four outcomes, not one

Every mutating screen SHALL implement, and be reviewable against, the pending,
succeeded, failed, and partially-succeeded outcomes. A screen that renders only the
successful path SHALL NOT be considered complete.

While a mutation is in flight the initiating control SHALL be busy and SHALL NOT be
re-submittable, so that a slow broker cannot produce a duplicated command.

The outcome SHALL be announced to assistive technology through a live region, not
only rendered visually, because an operator using a screen reader otherwise receives
no signal that a destructive action completed.

A failure SHALL state its cause and the next action available. A message that
reports only that something went wrong SHALL NOT be shipped.

#### Scenario: A slow command cannot be double-submitted

- **WHEN** an operator submits a command and the broker is slow to answer
- **THEN** the control is busy and a second submission is not possible

#### Scenario: An outcome is announced, not only drawn

- **WHEN** a mutation completes or fails
- **THEN** its outcome is announced through a live region

#### Scenario: A failure names its cause and the way forward

- **WHEN** a mutation fails
- **THEN** the message states why it failed and what the operator can do next

### Requirement: A per-node outcome is presented as one object, legible at a glance

Where an operation applies across nodes, the system SHALL present its result as a
single per-node summary rather than as a list of separate notifications, and SHALL
make a partial result distinguishable from a complete one without the operator
reading each row.

Each node's state SHALL be carried by text as well as by colour, because colour
alone cannot convey it. Colour SHALL be used only where something is wrong,
consistent with this product's existing convention that a healthy view is
near-monochrome.

Counts SHALL be rendered with tabular figures so that per-node numbers align in a
column and a difference between nodes is visible without reading the digits.

The same summary SHALL be used for a preview and for a result, so that what the
operator confirmed and what happened are visually comparable.

#### Scenario: A partial result is obvious without reading rows

- **WHEN** an operation succeeds on some nodes and fails on others
- **THEN** the summary makes the partial outcome apparent before any individual row
  is read

#### Scenario: State does not depend on colour

- **WHEN** a node outcome is rendered
- **THEN** its state is conveyed in text, and colour, where used, is redundant with
  that text

#### Scenario: Preview and result are comparable

- **WHEN** an operator previews an operation and then performs it
- **THEN** the result is presented in the same form as the preview

### Requirement: An unavailable capability is explained where the action would be

Where an operation is unavailable because the connection lacks a capability, the
control SHALL be present, disabled, and accompanied by the reason and the
configuration change that would enable it. The control SHALL NOT be hidden.

Where a capability is not yet known rather than known to be missing, the control
SHALL be enabled and the uncertainty stated, so that an operator is not blocked by
the absence of evidence.

The explanation SHALL be reachable by keyboard and SHALL NOT be available only on
hover.

#### Scenario: A missing capability explains itself in place

- **WHEN** a connection cannot perform an operation
- **THEN** the control is visible, disabled, and accompanied by the reason and the
  configuration that would enable it

#### Scenario: An unknown capability does not block the operator

- **WHEN** a capability has not yet been established
- **THEN** the control is enabled and the uncertainty is stated

#### Scenario: The explanation is reachable without a pointer

- **WHEN** a keyboard user reaches a disabled control
- **THEN** the reason is available to them without hovering

### Requirement: Forms label their fields, validate on blur, and never disable silently

Every input SHALL carry a visible label. A placeholder SHALL NOT be used as a
label.

A field whose value the system will reject SHALL be validated when the operator
leaves it, not only when the form is submitted, and its message SHALL appear beside
the field it belongs to.

A submit control SHALL NOT be disabled without the reason being visible. Either the
reason is shown, or the control stays enabled and validates on activation.

A field that cannot be changed SHALL be presented as immutable with the reason,
which is distinct from being disabled — one cannot be changed at all, the other is
unavailable right now.

Configuration that most operators will not change SHALL be collapsed behind a
disclosure rather than presented alongside the fields that identify the resource.

#### Scenario: Labels are visible

- **WHEN** a form is rendered
- **THEN** every input has a visible label and no field relies on its placeholder to
  name it

#### Scenario: A field is validated when it is left

- **WHEN** an operator enters a value the system will reject and moves to the next
  field
- **THEN** the message appears beside that field before the form is submitted

#### Scenario: A disabled submit explains itself

- **WHEN** a submit control is disabled
- **THEN** the reason is visible without the operator having to guess

#### Scenario: Immutable and unavailable are distinguishable

- **WHEN** a form shows a field that can never be changed and one that is currently
  unavailable
- **THEN** the two are presented differently, each with its reason

### Requirement: A view teaches what is missing when it has nothing to show

An empty view SHALL state what the resource is, why there is none, and the action that creates one where the operator is permitted to take it. A view whose empty state reads only that there is nothing SHALL NOT be shipped.

An empty result caused by a filter SHALL be distinguishable from one caused by there being nothing at all, and SHALL offer to clear the filter.

An empty view that is empty because a node could not be reached SHALL say so rather than presenting an absence as a fact.

A view that is loading SHALL show where its content will appear, at that content's size, so the page does not move when it arrives. A view whose data failed to load SHALL state the cause and the next action in place of its content, and SHALL offer to retry where retrying can help.

#### Scenario: Nothing to show teaches what would be shown
- **WHEN** a view has no rows
- **THEN** it explains what the resource is and how one comes to exist

#### Scenario: Filtered-empty is not the same as empty
- **WHEN** a filter excludes every row
- **THEN** the view says so and offers to clear the filter

#### Scenario: Unreachable is not empty
- **WHEN** rows are absent because a node could not be reached
- **THEN** the view reports the unreachable node rather than showing an empty result

#### Scenario: Loading does not move the page
- **WHEN** a table's rows arrive after it was shown loading
- **THEN** nothing above or beside the table moves

#### Scenario: A failure names its cause and the next step
- **WHEN** a view's data request is refused because the operator lacks a permission
- **THEN** the view names the missing permission instead of an empty or generic failure

### Requirement: Destructive flows are operable and legible without a pointer

A dialog confirming a destructive action SHALL move focus into itself when it opens,
SHALL keep focus within itself while open, SHALL close on the escape key, and SHALL
return focus to the control that opened it when it closes.

Every control SHALL have an accessible name; an icon-only control SHALL carry one.
Focus SHALL remain visible throughout, and a focus indicator SHALL NOT be removed.

New views SHALL meet the contrast floor this product already applies to its text and
status colours, verified rather than assumed, and SHALL be checked in both colour
schemes.

Any motion introduced SHALL be suppressed when the operator has asked for reduced
motion.

#### Scenario: A confirmation dialog is keyboard-complete

- **WHEN** an operator opens and dismisses a destructive confirmation using only the
  keyboard
- **THEN** focus enters the dialog, stays within it, escape dismisses it, and focus
  returns to the control that opened it

#### Scenario: Both colour schemes are verified

- **WHEN** a new view is reviewed
- **THEN** its contrast is checked in the light and the dark scheme rather than
  inferred from one

#### Scenario: Reduced motion is honoured

- **WHEN** an operator has asked for reduced motion
- **THEN** motion introduced by these views does not play

### Requirement: New views reuse the product's existing view, token, and confirmation machinery

Tabular views SHALL use the product's data table: its interactive grid for large or interactive data, or its static table for a small read-only set, with its sizing, sorting, sort-state announcement, density and node attribution, rather than a new table implementation. Pairs of terms and values SHALL use the product's description list.

Views SHALL be composed from the product's page parts: one page header, sections, toolbars, and the empty, error and loading states.

Confirmations SHALL use the product's confirmation dialog, and destructive confirmations its press-and-hold component, rather than a further hand-rolled copy.

Colour, type, spacing, radius, elevation, motion and density SHALL come from the product's semantic token layer. A raw colour literal SHALL NOT appear in a component.

Layout SHALL use logical properties, never physical ones.

State that describes what is being viewed — filters, selection, paging, the open resource — SHALL live in the URL so a view can be shared and restored, while transient interaction state stays local.

#### Scenario: A new table is not a new table implementation
- **WHEN** a new tabular view is added
- **THEN** it uses the product's data table and inherits its behaviour

#### Scenario: Sorting is announced
- **WHEN** an operator sorts a table by a column
- **THEN** the sorted column and direction are announced once the sorted rows have loaded

#### Scenario: A shared view reopens as it was left
- **WHEN** an operator shares the address of a filtered, sorted view
- **THEN** the recipient sees the same filter and sort

### Requirement: A view whose size grows with the cluster is bounded

The system SHALL bound what any view renders at once, so that the amount of work a
screen does is a function of what the operator can see rather than of how large the
cluster is. A view listing rows SHALL either present a bounded page of them with a
way to reach the rest, or render only the rows within the viewport, or both.

A view that is bounded SHALL make the bound visible: an operator SHALL NOT be left
unable to tell whether they are looking at everything.

#### Scenario: A large result does not render in full

- **WHEN** a view's underlying result contains far more rows than fit on a screen
- **THEN** the number of rows rendered is bounded, and the view states that more
  exist and how to reach them

#### Scenario: A single page is not silently the whole answer

- **WHEN** a view fetches a bounded page of results and more pages exist
- **THEN** the view offers a way to reach the following pages

#### Scenario: A dense graph degrades rather than drawing everything

- **WHEN** a topology contains more nodes than can be drawn legibly at once
- **THEN** the view reduces the detail it draws rather than drawing every node at
  full detail, and states that it has done so

### Requirement: A capture subscription's cost and state are visible where it is managed

The interface that lists capture subscriptions SHALL show, for each one: its per-node state
with the reason for any node that is not capturing, what it currently holds against both its
retention and its size bound, and its estimated loss.

A subscription that is degraded SHALL be distinguishable from one that is healthy in text,
not by colour alone.

#### Scenario: A degraded node is named with its reason

- **WHEN** a capture subscription is active on one node and refused on another
- **THEN** both are listed, and the refusal is named in text

### Requirement: Deleting a capture subscription states its full blast radius

Deleting a capture subscription destroys stored application payload and removes objects from
every live broker in the cluster. The interface SHALL state, before the action can be armed,
how many captured messages will be destroyed and which broker objects will be removed from
which nodes, and SHALL be confirmed by pressing and holding the delete button.

#### Scenario: The blast radius is stated before the action is armed

- **WHEN** an operator begins deleting a capture subscription
- **THEN** the number of captured messages to be destroyed and the broker objects to be removed are stated, and the action is confirmed only by holding the delete button

### Requirement: The console keeps its results reachable while it explains itself

The console's statements about a result — its source, its completeness, the bounds it
reached and the notices it carries — SHALL be presented so that they do not displace the
result itself out of view.

A screen that pushes its rows below the fold to explain them defeats the explanation: an
operator mid-incident scrolls past the text to reach the rows, which is exactly the text
they needed. The statements SHALL remain reachable and SHALL NOT be dismissable where the
underlying requirement forbids dismissal.

#### Scenario: A result carrying several statements still shows its rows

- **WHEN** a result carries a source statement, more than one bound and more than one notice
- **THEN** the rows remain visible without scrolling past the statements, and each statement remains reachable

### Requirement: A control disabled by capability or permission explains itself from the keyboard

Every control the console disables because the resource's kind lacks the verb, a capability is missing, or a permission is missing SHALL
carry its reason on something that takes keyboard focus. A disabled control takes no focus,
so the explanation SHALL hang off an element that does. When more than one reason applies, the
reason shown SHALL be the first of these: the kind lacks the verb (for example, that an
Artemis address cannot be purged), a capability is unavailable (its stated reason), a permission
is missing (naming the permission).

#### Scenario: The reason for a disabled control is reachable by keyboard

- **WHEN** an operator using only the keyboard reaches a control the console has disabled
- **THEN** the reason it is disabled is reachable and readable without a pointer

#### Scenario: The kind's missing verb is the first reason

- **WHEN** an operator without the purge permission reaches Purge on a resource whose kind cannot be purged
- **THEN** the reason names the kind that lacks the verb, not the missing permission

### Requirement: A view that accumulates rows indefinitely states its bound

A view that receives rows continuously SHALL be bounded, and SHALL state when it has begun
discarding the oldest rows to stay within that bound.

#### Scenario: A long-running tail states that it is discarding

- **WHEN** a tail has delivered more rows than the view retains
- **THEN** the view states that the oldest rows are no longer shown

### Requirement: Navigation is grouped into a fixed, ordered set of groups

The navigation SHALL present every view within one of a fixed, ordered set of groups: Observe, Messaging, Resources, Configuration, Activity. Within a group, views SHALL appear in a declared order. When the navigation is expanded, each group SHALL show a visible heading exposed to assistive technology as a heading. When collapsed, groups SHALL be separated visually and SHALL keep their accessible names. The command palette SHALL list views under the same groups. A group with no enabled views SHALL NOT be shown.

#### Scenario: Views appear under their group headings

- **WHEN** an operator opens a cluster with the navigation expanded
- **THEN** each view is listed under its group's heading, groups appear in the fixed order, and a screen reader announces each heading

#### Scenario: Collapsed navigation keeps groups distinguishable

- **WHEN** the navigation is collapsed
- **THEN** groups remain visually separated and each view keeps its accessible name

#### Scenario: A group whose views are all disabled is not shown

- **WHEN** every view in a group belongs to disabled features
- **THEN** that group's heading does not appear

### Requirement: A disabled feature is absent from navigation and explains itself at its address

A view belonging to a feature disabled on this installation SHALL NOT appear in navigation, the command palette, or any screen section that feature would contribute. Navigating directly to such a view's address SHALL show a page that:
- states the feature is disabled on this installation;
- names the startup property that enables it;
- offers a way back to the cluster.

The page SHALL NOT be blank and SHALL NOT look like a generic not-found page.

A view belonging to an enabled feature that the operator lacks permission for SHALL remain visible and disabled, with the reason available by keyboard.

#### Scenario: A deep link to a disabled feature explains how to enable it

- **WHEN** an operator opens the address of a view whose feature is disabled
- **THEN** the page states the feature is disabled on this installation, names `rahdar.features.<id>.enabled`, and links back to the cluster

#### Scenario: A disabled feature contributes nothing to shared screens

- **WHEN** a feature that contributes a section to the settings screen is disabled
- **THEN** the settings screen renders without that section and without an empty placeholder for it

#### Scenario: A permission gap is not treated as a disabled feature

- **WHEN** an operator lacks the read permission for an enabled feature's view
- **THEN** the navigation entry is visible and disabled, and its reason is reachable by keyboard

### Requirement: Governed message content is presented for what it is

Wherever message content is shown (message detail, SQL results, flow detail), the interface SHALL present each governed value distinctly:

- **masked value**: a labelled token naming its class in words;
- **dropped credential**: a labelled marker;
- **withheld content**: a notice stating why, and naming the setting that changes it where one does;
- **value shown in clear by grant**: marked as sensitive.

Colour SHALL NOT be the only carrier of any of these states. Copying or downloading governed content SHALL state that the copy contains masked values where it does.

#### Scenario: A masked value is labelled in words

- **WHEN** a masked email appears in message detail
- **THEN** it is shown as a token reading that it is a masked email, not as a string resembling an address

#### Scenario: A withheld body names its reason

- **WHEN** message detail shows a message whose binary body was withheld
- **THEN** the body area states that the binary body could not be classified and was withheld

### Requirement: The governance screens teach and gate honestly

The governance screens SHALL:

- list rules, marking built-in rules as not deletable while leaving their enable control available;
- list open findings with confirm and dismiss actions;
- show how many stored rows are still under an earlier policy version.

A user without the governance write permission SHALL see the change controls disabled, with the reason reachable from the keyboard. An empty inbox SHALL explain what a finding is and why there are none; a filtered-empty inbox SHALL say so and offer to clear the filter.

#### Scenario: A read-only user sees why controls are disabled

- **WHEN** a user with governance read but not governance write opens the rules screen
- **THEN** the create, edit and delete controls are disabled and a keyboard-reachable explanation names the missing permission

#### Scenario: An empty inbox teaches

- **WHEN** the inbox has no findings
- **THEN** it explains that findings are personal data detected in fields no rule covers, and that none have been detected yet

### Requirement: A previewed mutation is exactly what is submitted

Where a mutation is previewed before it is confirmed, as when creating a divert, the values
previewed SHALL be exactly the values submitted. While a preview is shown its inputs SHALL
NOT be editable. Changing a value SHALL require leaving the preview, and the preview SHALL
be run again before the mutation can be confirmed.

The form SHALL validate each field on blur against the same rules the server enforces, and
SHALL show any per-field error the server returns beside that field. On a rejected
submission, focus SHALL move to the first invalid field. A control that cannot yet be used
SHALL state why.

The dialog SHALL NOT be dismissible while its mutation is in flight. The outcome — succeeded,
failed, or partial with per-node detail — SHALL be shown once the mutation settles.

#### Scenario: Editing after preview requires a new preview

- **WHEN** an operator previews a divert and then chooses to change its forwarding address
- **THEN** the preview is left, the fields become editable, and the creation cannot be confirmed until a new preview has run

#### Scenario: A server field error is shown beside its field

- **WHEN** the server rejects a divert because its name contains an unsafe character
- **THEN** the error is shown beside the name field and focus moves to it

#### Scenario: The outcome of an in-flight mutation is not lost

- **WHEN** an operator tries to close the divert dialog while its creation is in flight
- **THEN** the dialog stays open, and shows the per-node outcome when the creation settles

### Requirement: Arming a capture subscription is confirmed against its dry run

The interface SHALL render the dry run before a capture subscription can be armed. The dry
run shows:
- the resolved addresses;
- the target nodes;
- the capture queue's bounds in messages and bytes;
- the broker objects to be created;
- the equivalent broker configuration.

Starting a capture subscription SHALL be confirmed by pressing and holding the start button, which names the pattern. Its bounds — message and
byte limits, per-message body limit, ingest rate and filter — SHALL be editable behind a
disclosure, validated on blur against the server's limits. A value outside those limits
SHALL be refused with its reason, never silently adjusted.

The copy SHALL match the subscription's mode: a captured subscription SHALL NOT be described
as sampled, and a sampled one SHALL NOT be described as capturing everything.

#### Scenario: Capture cannot be started without its dry run and a hold

- **WHEN** an operator starts creating a capture subscription
- **THEN** the dry run is shown and the action starts only when its button has been held

#### Scenario: An out-of-range bound is refused, not clamped

- **WHEN** an operator enters a capture queue bound above the server's maximum
- **THEN** the field shows the maximum and the reason, and the value is not silently changed

#### Scenario: Copy follows the mode

- **WHEN** an operator views a subscription in capture mode
- **THEN** no text on it describes the subscription as sampled

### Requirement: Per-node outcomes and errors are announced

A per-node outcome summary SHALL be announced to assistive technology when it appears or
changes, and an error that stops an action SHALL be announced as an alert. This applies to
the preview and to the result.

#### Scenario: A partial outcome is announced

- **WHEN** a divert creation settles with a partial outcome
- **THEN** the per-node summary is announced by assistive technology without the operator moving focus to it

### Requirement: An unavailable figure is stated, never shown as zero

Where a count, total or estimate that a view would show is unavailable, the view SHALL state
that it is unavailable and why. This covers a browse total, a capture loss estimate, and a
capture footprint. It SHALL NOT show zero, a dash that reads as zero, or omit the figure.

#### Scenario: A browse without a total says so

- **WHEN** a browse response states that its total is unavailable
- **THEN** the message view says the total is unavailable and why, and shows no number in its place

#### Scenario: A filtered capture's loss says it cannot be estimated

- **WHEN** a capture subscription's loss estimate is unavailable
- **THEN** the subscription view says the estimate is unavailable and why

### Requirement: A capture deletion states what happens on unreachable nodes

Before a capture subscription's deletion can be armed, the interface SHALL state that broker
objects are removed from reachable nodes immediately, and from unreachable nodes on their
next reconciliation. After the deletion, it SHALL show the per-node removal outcome.

#### Scenario: An unreachable node's cleanup is stated

- **WHEN** an operator deletes a capture subscription while one of its nodes is unreachable
- **THEN** the confirmation states that a node that does not answer is cleaned on its next reconciliation, rather than claiming every node was cleaned

### Requirement: Sampling is named as sampling where capture would see more

Wherever a sampled subscription is chosen or listed, the UI SHALL say briefly that it is only
sampling and that a message consumed between two polls is not recorded.

Where request-reply tracing depends on destinations that no enabled capture subscription covers,
the UI SHALL show a short hint naming those destinations and where capture is turned on. The hint
SHALL disappear once every such destination is captured.

#### Scenario: A traced destination that is only sampled gets a hint

- **WHEN** an operator traces a request destination and a reply destination, and neither is captured
- **THEN** the tracing screen names both as only sampled and points to where capture is turned on

#### Scenario: The hint goes away once capture covers the destinations

- **WHEN** an enabled capture-everything subscription covers every traced destination
- **THEN** no capture hint is shown

#### Scenario: A sampled subscription says it is only sampling

- **WHEN** an operator views or creates a sampled subscription
- **THEN** it is described as just sampling, which misses messages consumed between polls

### Requirement: Content that moves on its own can be paused and honours reduced motion

A view that animates without operator input for longer than five seconds SHALL offer a
visible, keyboard-reachable control that pauses the animation, SHALL stop the animation
while the operator's system requests reduced motion, and SHALL stop it while the view is not
visible. Whatever the animation conveys SHALL also be conveyed without motion, so pausing
removes no information. Pausing an animation SHALL NOT pause data refresh; pausing data
refresh remains its own control.

#### Scenario: Pause keeps information

- **WHEN** an operator pauses an animated view
- **THEN** the movement stops and every value it conveyed is still shown as text or shape

#### Scenario: Reduced motion is honoured

- **WHEN** the operator's system requests reduced motion
- **THEN** the view opens without movement and offers no way for it to start on its own

#### Scenario: Hidden view stops moving

- **WHEN** the browser tab showing an animated view is hidden
- **THEN** the animation stops and resumes, unless paused, when the tab is shown again

### Requirement: A health verdict is carried in words, with colour as redundant emphasis

A view presenting a derived verdict SHALL state it as a word. Colour SHALL NOT be the only
carrier of the verdict, and SHALL be applied only where something is wrong, so a healthy
view stays near-monochrome.

#### Scenario: A verdict is legible without colour perception

- **WHEN** a ranked health view is rendered
- **THEN** each row's verdict is readable as text, and removing colour removes no
  information

#### Scenario: A healthy cluster is quiet

- **WHEN** every subscription in a cluster is healthy
- **THEN** the view carries no warning or danger colour

#### Scenario: An uncomputed verdict is distinguishable from a healthy one

- **WHEN** a subscription's verdict could not be computed
- **THEN** the row states that, and is visually distinct from a row reporting health

### Requirement: A graph that can be edited is operable without a pointer and has a non-graph equivalent

A view that presents a structure as a graph and allows that structure to be edited SHALL be
fully operable from the keyboard: focus SHALL be able to enter the graph, move between its
elements, open the element that has focus, and leave the graph again, and every element SHALL
carry an accessible name that says what it is and what it connects.

Such a view SHALL NOT be the only way to reach what it presents. Every element it shows and
every edit it offers SHALL also be reachable from a non-graph presentation of the same
underlying state, so that an operator who cannot use the graph is not locked out of the
capability.

A graph that does not animate on its own needs no pause control; a graph that does SHALL meet
the same pausing, reduced-motion and off-screen requirements as any other moving content.

#### Scenario: The graph is traversed without a pointer

- **WHEN** an operator moves focus into an editable graph and uses the keyboard alone
- **THEN** focus moves between its elements, the focused element can be opened, and focus can
  leave the graph again

#### Scenario: Every element carries a name

- **WHEN** an element of the graph receives focus
- **THEN** its accessible name states what the element is and what it connects

#### Scenario: The graph is not the only path

- **WHEN** an operator uses the non-graph presentation of the same state
- **THEN** every element the graph shows and every edit it offers is available there

### Requirement: An authored change that has not been applied is stated as such

Where a view lets an operator author a change that is recorded before it takes effect, the
view SHALL state, in words, which parts of what it shows have been authored and not yet
applied. Colour SHALL NOT be the only carrier of that distinction.

An authoring surface SHALL NOT be presented as a preview of a mutation. The preview remains
the confirmation step, whose inputs do not change while it is shown and which is recomputed
when the underlying state has moved.

#### Scenario: Unapplied authoring is legible without colour

- **WHEN** a view shows both applied and authored-but-unapplied elements
- **THEN** each authored-but-unapplied element says so in text, and removing colour removes no
  information

#### Scenario: Authoring does not stand in for the preview

- **WHEN** an operator has authored a change and moves to apply it
- **THEN** a preview is computed and confirmed separately, and its inputs cannot be edited
  while it is shown

### Requirement: Subscriptions can be selected individually or by filter

The subscriptions screen SHALL let an operator select subscriptions row by row, and select every subscription
on the page. When the page does not show every subscription matching the current filter, it
SHALL offer to select all of them, stating how many that is. Once all matching subscriptions
are selected, the screen SHALL say so, and SHALL offer to clear the selection.

When subscriptions are selected, the screen SHALL show the number selected and the bulk actions
available for them.

Each bulk action SHALL be gated on the permission of the single-subscription operation it
applies. An action the operator may not take SHALL be shown disabled, with the reason
reachable from the keyboard. While permissions are still loading, the actions SHALL be
offered.

#### Scenario: Selecting all subscriptions matching a filter

- **WHEN** an operator filters the subscriptions screen to `orders`, which matches 140 subscriptions
  across three pages, and selects the page
- **THEN** the screen offers to select all 140 matching subscriptions, and after they accept it states
  that all 140 subscriptions matching `orders` are selected

### Requirement: A bulk run is confirmed against its preview and states its blast radius

Choosing a bulk action SHALL show its preview before the action can be confirmed. The
preview SHALL state, in words:
- the operation;
- the number of subscriptions;
- the number of nodes;
- for purge and delete, the messages that will be destroyed, or that the figure is
  unavailable.

It SHALL list every subscription with its per-subscription figures. It SHALL list refused subscriptions and
their reasons. It SHALL offer to show only the subscriptions with a refusal or a warning.

A purge or delete SHALL be confirmed by pressing and holding a button that names the
action and the count, e.g. `Delete 37 subscriptions`; a run over exactly one subscription names that
subscription. A pause or resume SHALL be confirmed once. The option to override
the message cap SHALL be shown only when the run is over the cap and the operator may
override it. The option to continue past failures SHALL be off by default.

The confirming control SHALL be busy while the execution request is in flight, and SHALL
NOT be submitted twice. Once the run is accepted, the operator SHALL be taken to the
run's progress view.

#### Scenario: A destructive bulk run is confirmed by a hold

- **WHEN** an operator previews a delete of 37 subscriptions
- **THEN** the preview states the subscriptions, the nodes, and the messages destroyed, and the
  delete is confirmed only by holding `Delete 37 subscriptions`

#### Scenario: A one-subscription purge names the subscription

- **WHEN** an operator previews a purge of the one subscription `orders`
- **THEN** the purge button names `orders` and is confirmed only by a hold

#### Scenario: A keyboard-only operator can complete and abandon the flow

- **WHEN** an operator opens a bulk delete preview from the keyboard
- **THEN** focus moves into the dialog, escape dismisses it, and focus returns to the control
  that opened it

### Requirement: A bulk run's progress and outcome are visible and announced

A bulk run SHALL have a view at its own address. The view SHALL show:
- the run's status in words;
- its progress;
- the count of subscriptions in each outcome;
- each subscription's outcome, expandable to its per-node detail in the same form as a
  single-subscription outcome.

It SHALL update live while the run executes. It SHALL offer to stop a running run, and
SHALL link to the run's audit event.

When the run finishes, the outcome SHALL be announced to assistive technology, and it
SHALL be stated in words as succeeded, partial, failed, stopped, or interrupted.

Past bulk runs on a cluster SHALL be listed, newest first, each with its operation,
subscription count, who ran it, when, and its outcome.

Progress motion SHALL honour the operator's reduced-motion preference.

#### Scenario: A partial run is stated as partial

- **WHEN** a run finishes with 8 subscriptions succeeded and 2 failed
- **THEN** the view states the run was partial, shows 8 succeeded and 2 failed, and
  announces that outcome

#### Scenario: A reloaded page shows the run in progress

- **WHEN** an operator reloads the progress view of a run that is still executing
- **THEN** the view shows the run's current progress and continues to update

### Requirement: Settings is presented as grouped tabs

The Settings page SHALL be a cluster's, shown to those who hold `settings:read`. It SHALL present each settings section as a tab in a vertical tab list. The tabs SHALL be grouped under fixed headings in this order: Rahdar-wide configuration, this cluster, and plugins. What is not configuration a cluster's operators share SHALL live elsewhere: the operator's own display preferences on the Account page, which needs no permission, and the installation's encryption keys and Rahdar's own health under Administration. Rahdar-wide configuration SHALL have one tab per settings category. Each category SHALL be edited as a form whose inputs fit each setting's kind. A modified setting SHALL show its default and offer a reset.

One search SHALL find settings across all categories, and a filter SHALL show only modified settings. The open tab and the search SHALL be held in the address, so they can be shared and restored.

Edits SHALL form one draft across categories. A marker SHALL show on each category with unsaved changes. Leaving with unsaved changes SHALL ask first. The draft SHALL be applied in one step when no approval is needed. When approval is needed, the draft SHALL be reviewed first, as a list of current and new values, with a reason. Enter in a setting's field SHALL open that review rather than apply the draft; only the footer's primary action SHALL apply it directly.

A change waiting for approval SHALL show beside its setting, with its requester, its age and a way to cancel it.

The tab list SHALL be operable from the keyboard, and changing tab SHALL move focus to the opened section's heading. A section contributed by a plugin SHALL appear under the plugins heading. A section whose form holds unsaved input SHALL be marked Unsaved, and changing tab away from it SHALL ask before discarding that input.

#### Scenario: The open tab survives a reload

- **WHEN** an operator opens the Broker credentials tab and reloads the page
- **THEN** the Broker credentials tab is open

#### Scenario: The tabs are keyboard operable

- **WHEN** focus is on the tab list and the operator presses the down arrow and Enter
- **THEN** the next tab opens and focus moves to its heading

#### Scenario: Search across categories

- **WHEN** an operator searches for "retention"
- **THEN** only categories with a matching setting are listed, with their match counts

#### Scenario: Change needs approval

- **WHEN** a team member edits two settings and a provider would hold the change
- **THEN** the primary action reads "Request approval", and submitting shows both changes as pending beside their settings

#### Scenario: Enter in a field reviews the draft

- **WHEN** an operator edits a setting and presses Enter in its field
- **THEN** the review of the whole draft opens, and nothing is applied until it is confirmed

#### Scenario: Leaving with unsaved edits

- **WHEN** an operator with unsaved edits navigates away
- **THEN** Rahdar asks before discarding them

#### Scenario: Unsaved connection edits survive a stray tab change

- **WHEN** an operator has edited a cluster's connection form and opens another Settings tab
- **THEN** Rahdar asks whether to discard the edits, and staying keeps them

### Requirement: Plugin management states consequences before acting and outcomes after

The plugin management screen SHALL state, before any plugin action is confirmed, what it will do, to which plugin, and for how long anything will be unavailable. It SHALL render the pending, succeeded, failed and partially-succeeded outcomes of every action, announce each outcome through a live region, and pair every failed, incompatible or restart-required state with the action that resolves it. Plugin state SHALL be carried in words, with colour only as redundant emphasis where something is wrong. Closing the screen during an activation SHALL NOT cancel it, and its progress SHALL remain visible from the application header.

#### Scenario: A failed plugin shows its remedy in place

- **WHEN** a plugin failed to activate
- **THEN** its row states the cause in words and offers the action that resolves it

#### Scenario: Leaving during activation does not cancel it

- **WHEN** an operator closes the activation dialog while the activation is in progress
- **THEN** the activation continues and its progress is visible from the header

### Requirement: A row's actions are offered in one menu reachable by pointer and keyboard

Every grid that lists broker resources SHALL offer, for each row, one menu of the actions
that apply to that resource. The menu SHALL open:
- by right-click on the row;
- by a visible Actions control on the row, which has an accessible name naming the row;
- by Shift+F10 or the ContextMenu key while a cell of the row has focus.

Right-click SHALL leave the browser's own menu in place on a link or an input, while Shift
is held, and while text is selected.

The menu SHALL group its items into Open, Copy, Operate and Destroy, in that order, and
SHALL offer the actions of every enabled feature that contributes one. An action the
operator may not take, or that the connection cannot perform, SHALL remain in the menu,
reachable by keyboard, with its reason. Activating it SHALL explain the reason in full,
including the `broker.xml` that enables it where one exists.

An action SHALL open the same previewed, confirmed flow as the screen that owns it. Its
outcome SHALL remain visible after the row leaves the grid. On dismissal, focus SHALL
return to the row's Actions control, or to the grid when the row is gone.

#### Scenario: Deleting a subscription from the keyboard

- **WHEN** an operator tabs into the subscriptions grid, moves to the row `orders`, presses
  Shift+F10, chooses "Delete subscription…" and presses Escape in the dialog
- **THEN** the menu opens naming `orders`, the delete dialog opens with its preview, and
  focus returns to the Actions control of `orders`

#### Scenario: A blocked action explains itself

- **WHEN** an operator without the delete permission opens a subscription's menu
- **THEN** "Delete subscription…" is listed as unavailable with its reason, can be reached with
  the arrow keys, and activating it shows the full reason

#### Scenario: The outcome survives the row

- **WHEN** an operator closes a connection from its row and the refreshed listing no longer
  contains it
- **THEN** the close outcome remains on screen until the operator dismisses it

#### Scenario: Copying still works

- **WHEN** an operator selects text in a row and right-clicks it
- **THEN** the browser's own menu opens

### Requirement: A data grid is one tab stop navigated with the arrow keys

A grid SHALL be one stop in the tab order, named for what it lists. Within it:
- the arrow keys SHALL move between cells, and Up and Down SHALL keep the column;
- Home and End SHALL move to the row's first and last cell;
- Ctrl+Home and Ctrl+End SHALL move to the first and last row;
- Page Up and Page Down SHALL move by a page.

The header row SHALL be reachable the same way. Enter SHALL activate the focused row as a
click would. Space SHALL toggle the row's selection where the grid is selectable. A cell
holding a single control SHALL give it focus.

Focus SHALL NOT be lost when the grid refreshes, re-sorts or scrolls. A focused cell whose
value is clipped SHALL show its full value. Its value SHALL be copyable from the keyboard,
and the copy SHALL be announced.

#### Scenario: Opening a subscription without a pointer

- **WHEN** an operator tabs into the subscriptions grid, presses Down twice and Enter
- **THEN** the second subscription's detail opens

#### Scenario: One press leaves the grid

- **WHEN** focus is in a grid and the operator presses Tab
- **THEN** focus moves to the next control after the grid

#### Scenario: Focus survives a refresh

- **WHEN** a focused row moves position because the listing refreshed
- **THEN** focus stays on that row

### Requirement: Related resources link to each other

Wherever a view names a subscription, destination, channel or connection that another view
presents, the name SHALL be a link to that resource. The link SHALL open the exact
resource, not a list filtered by its name, even when the resource is not on the first page.
When the feature presenting the resource is disabled, the name SHALL be plain text.

#### Scenario: From a consumer to its subscription

- **WHEN** an operator activates the subscription name on a consumer row
- **THEN** the subscription's detail opens

### Requirement: The console states where the operator is

Every page SHALL carry a document title naming the open resource where there is one, the
view, the cluster and the product. Every cluster view SHALL show a breadcrumb of the
cluster, the view's group, the view and the open resource. The last crumb SHALL be marked
as the current page.

#### Scenario: A tab says what it holds

- **WHEN** an operator opens the subscription `orders` on the Subscriptions view of cluster `prod-eu`
- **THEN** the document title reads `orders · Subscriptions · prod-eu · <product>`

### Requirement: Switching cluster keeps the current view

Choosing another cluster SHALL open the same view on that cluster, without the current
view's filters and open resource.

#### Scenario: Comparing subscriptions across clusters

- **WHEN** an operator on the Subscriptions view of `prod-eu` chooses `prod-us`
- **THEN** the Subscriptions view of `prod-us` opens

### Requirement: The command palette finds resources without loading the brokers

The command palette SHALL:
- be openable from a visible control;
- search subscriptions as the operator types, opening the chosen subscription itself;
- offer to search the live-read resources (connections, consumers, channels, producers,
  destinations) for the typed text, by navigating to their view;
- list the views the operator recently opened.

It SHALL NOT read from any broker to answer a keystroke, and SHALL NOT fetch while it is
closed. A view the operator lacks permission for SHALL be listed as unavailable, with the
reason.

#### Scenario: Jumping to a subscription

- **WHEN** an operator opens the palette and types `ord`
- **THEN** subscriptions matching `ord` are listed, and choosing one opens that subscription

#### Scenario: A closed palette is silent

- **WHEN** the palette has not been opened
- **THEN** it issues no request

### Requirement: Single-key shortcuts are listed and can be turned off

The console SHALL list every keyboard shortcut in a popover anchored to a keyboard button in
the header, opened by that button and by `?`. It SHALL offer `g` followed by a view's letter to go to that view,
and `/` to focus the current view's filter. Single-key shortcuts SHALL be ignored:
- while typing;
- with a modifier;
- inside a dialog or a menu.

They SHALL NOT take a key the browser reserves. A personal setting SHALL turn them off.

#### Scenario: Going to queues

- **WHEN** an operator presses `g` then `q` outside an input
- **THEN** the Queues view of the open cluster opens

#### Scenario: Never inside a confirmation

- **WHEN** a destructive confirmation dialog is open and the operator presses `g` then `q`
- **THEN** nothing navigates

#### Scenario: Turned off

- **WHEN** an operator turns single-key shortcuts off and presses `g` then `q`
- **THEN** nothing happens

### Requirement: Grid columns fit their content and can be resized

A data table's columns SHALL size to their content: each column's width is its widest rendered value among the loaded rows or its header, within bounds set by the kind of value it holds. Spare width SHALL be shared among the free-text columns.

In a window 1280 pixels wide or wider at 100% zoom, a table SHALL fit its container without horizontal scrolling when its values are within their columns' bounds. When it does not fit, the table SHALL, in this order:
1. shorten the longest values, keeping each one readable in full on demand, and shortening identifiers in the middle so that names sharing a prefix stay distinguishable;
2. hide its least important columns, starting at the inline end, while stating how many are hidden and offering to show them; the column that identifies a row and any column that names the node a row came from SHALL NOT be hidden;
3. only then scroll horizontally, keeping the identifying column, the selection column and the actions column in view, with header and rows aligned.

Sizing SHALL happen when rows first render, when the columns change, when the typefaces finish loading and when the density changes. It SHALL NOT happen while the table scrolls, and new rows arriving in a live table SHALL NOT reflow its columns while the operator is reading or scrolling it.

Every column SHALL be resizable:
- by dragging its header's end border;
- by double-clicking that border, which fits the column to its content;
- from the keyboard, with Ctrl+Shift+Left and Ctrl+Shift+Right on a focused header cell. The new width SHALL be announced, and the table SHALL stay one tab stop.

A width the operator set SHALL be kept exactly; fitting SHALL NOT shrink it. Widths, the columns the operator hid or showed, and column order SHALL be remembered per viewer and per table in browser storage when the table names a storage key. They SHALL fall back to fitting when storage is empty, unavailable or out of date, and SHALL NOT be written to the URL.

#### Scenario: Long names are not cut by default
- **WHEN** a subscriptions table lists a subscription whose name is 300 pixels wide, in a window with room for it
- **THEN** the name column is at least that wide and the name shows without being shortened

#### Scenario: A table fits a 1280-pixel window
- **WHEN** the subscriptions table shows typical subscriptions in a 1280 by 800 window with the navigation expanded
- **THEN** the table has no horizontal scroll bar and every column is visible

#### Scenario: Columns that do not fit are hidden and named
- **WHEN** the columns' shortest widths together exceed the table's width
- **THEN** the least important columns are hidden, the table states how many are hidden, and the operator can show any of them

#### Scenario: A column the operator shows stays shown
- **WHEN** an operator shows a hidden column
- **THEN** it stays visible after a reload, and another column is hidden or the table scrolls instead

#### Scenario: The identifying column stays in view while scrolling
- **WHEN** a table scrolls horizontally
- **THEN** its identifying, selection and actions columns stay in view and every header cell stays above its column

#### Scenario: Header and rows stay aligned
- **WHEN** a column is resized while the table is scrolled horizontally
- **THEN** every header cell stays above its column's cells

#### Scenario: Identifiers that share a prefix stay distinguishable
- **WHEN** two subscription names differ only in their last characters and both are shortened
- **THEN** the shortened names show those last characters

#### Scenario: A live table does not reflow under the reader
- **WHEN** new rows with longer values arrive while the operator is scrolled into a live table
- **THEN** the column widths do not change until the operator returns to the top or leaves the table

#### Scenario: Resizing without a pointer
- **WHEN** an operator focuses the Subscription header cell and presses Ctrl+Shift+Right twice
- **THEN** the column is 32 pixels wider, the new width is announced, and pressing Tab leaves the table

#### Scenario: A width survives a reload
- **WHEN** an operator drags a column wider and reloads the view
- **THEN** the column keeps its width, and clearing the browser's storage returns it to fitting

### Requirement: The SDK offers a read-only diagram

The plugin SDK SHALL export a diagram component that lays out given nodes and edges automatically (layered, off the main thread) and renders them in Rahdar's theme in both colour schemes. The component SHALL NOT change the diagram itself: it SHALL NOT allow dragging, connecting or deleting, and it reports every choice to its caller. Nodes SHALL be focusable and selectable by keyboard, and a node's accessible name SHALL include its kind, label and any stated problem. A node with a problem SHALL show it in words as well as colour. A change that keeps the same nodes and edges SHALL NOT move anything. A layout failure SHALL be stated in place.

The component SHALL offer zoom in, zoom out and fit controls with accessible names, and SHALL be able to fill the height of its container.

When the caller gives a list of things that can be inserted, each edge the caller marks as insertable SHALL show an insert control that offers that list and reports the edge and the choice, and Insert pressed on a focused node SHALL offer the same choices for the insertable edges into it. When the caller gives actions for nodes, each node SHALL offer them in a menu that opens from a control on the node, from the pointer's secondary button, and from the keyboard (Shift+F10 or the context-menu key) on the focused node, and SHALL report the node and the action. A disabled action SHALL be shown disabled with its reason, not hidden. Without either, the component SHALL show no editing controls.

#### Scenario: Keyboard selection
- **WHEN** a user tabs to a node and presses Enter
- **THEN** the component reports that node as selected, and the selection is visible and announced

#### Scenario: A problem is stated in words
- **WHEN** a node is given a problem with a reason
- **THEN** the node shows the word "Invalid" and the reason is part of its accessible name

#### Scenario: Inserting on an edge
- **WHEN** a caller marks an edge insertable, gives the insert choices "A" and "B", and a user activates that edge's insert control and chooses "B"
- **THEN** the component reports that edge and "B", and changes nothing itself

#### Scenario: Inserting from the keyboard
- **WHEN** an edge into a node is insertable and a user focuses that node and presses Insert
- **THEN** the insert choices are offered, and a choice reports that edge

#### Scenario: A node's actions from the keyboard
- **WHEN** a caller gives a node the actions "Move up" (disabled) and "Remove", and a user focuses that node and presses Shift+F10
- **THEN** a menu opens with "Move up" disabled and "Remove" available, and choosing "Remove" reports that node and "Remove"

#### Scenario: Read-only by default
- **WHEN** a caller gives neither insert choices nor node actions
- **THEN** the diagram shows no insert controls and no action menus

### Requirement: The operator chooses the colour scheme from the header

The header's user menu SHALL offer, on every authenticated screen, the colour scheme as a choice of three: following the operating system, light and dark, with the one in use marked. The command palette SHALL offer an action that cycles the same three; its name SHALL state the scheme it switches to and, while the console follows the operating system, the scheme the system is using. The choice SHALL be remembered in that browser across reloads. A first visit SHALL follow the operating system. The page SHALL open in the chosen or system scheme without first showing the other one.

#### Scenario: A first visit follows the system
- **WHEN** an operator whose operating system uses a light scheme opens the console for the first time
- **THEN** the console shows the light scheme

#### Scenario: Choosing a scheme
- **WHEN** an operator whose console follows the operating system chooses Light in the user menu
- **THEN** the console uses the light scheme regardless of the system, and the menu marks Light as the scheme in use

#### Scenario: The choice survives a reload
- **WHEN** an operator chooses the dark scheme and reloads
- **THEN** the console opens in the dark scheme with no light frame first

### Requirement: The role editor's permission picker is grouped, searchable and explained

The picker SHALL group permissions by module or plugin. It SHALL filter them by a search box, and show each permission's description and whether it acts only at global scope. It SHALL offer select-all and clear for each group. A permission the role holds that is not in the catalogue SHALL stay selected and be shown as not in the catalogue, so saving never drops it silently.

#### Scenario: Search narrows the list

- **WHEN** the operator types in the search box
- **THEN** only permissions matching name or description remain, groups with no match are hidden

#### Scenario: Search matches nothing

- **WHEN** the search matches no permission
- **THEN** the picker says that nothing matches and offers to clear the search

#### Scenario: Bulk select a group

- **WHEN** the operator selects a group
- **THEN** every permission in that group is granted in the draft and the count is announced

#### Scenario: A held permission is not in the catalogue

- **WHEN** a role holds a permission of an inactive plugin and the operator edits it
- **THEN** that permission is shown selected under "Not in the catalogue" and is kept on save unless the operator clears it

#### Scenario: Keyboard use

- **WHEN** the operator uses only the keyboard
- **THEN** every group, chip and checkbox is reachable and operable

### Requirement: An administrator can preview a user's effective permissions and compare two roles

The users view SHALL offer each user's effective permissions, grouped by scope, with the source role and the wildcard each one came through. A permission that has no effect at its scope SHALL be marked in words. The roles view SHALL offer a comparison of two roles, listing the permissions only one of them holds.

#### Scenario: Preview a user

- **WHEN** the administrator opens a user's effective permissions
- **THEN** each permission is listed under its scope with its source role, and a global-only permission held at cluster scope reads "No effect at this scope"

#### Scenario: Compare two roles

- **WHEN** the administrator picks two roles to compare
- **THEN** the permissions held only by the first and only by the second are listed in two labelled columns, and identical roles say they hold the same permissions

### Requirement: The viewer chooses table density

The console SHALL offer two table densities, compact and comfortable, chosen by the viewer from the user menu, from any table's column menu and from the command palette. The choice SHALL apply to every table, be remembered in that browser, take effect before the first paint of the next page load, and SHALL NOT be written to the URL. Interactive controls in a compact table SHALL keep a target of at least 24 by 24 CSS pixels.

#### Scenario: Density applies everywhere
- **WHEN** an operator switches to the comfortable density on the subscriptions page and opens the audit page
- **THEN** the audit table uses the comfortable density too

#### Scenario: Density survives a reload without a jump
- **WHEN** an operator who chose comfortable reloads a page with a table
- **THEN** the table first renders at the comfortable row height

### Requirement: The console is laid out for desktop windows and stays usable when zoomed

The console SHALL be laid out for desktop windows 1280 pixels wide and wider, and SHALL NOT scroll the page horizontally in a 1280 by 800 window at 100% zoom with the navigation expanded. It SHALL NOT offer separate layouts for phones or tablets. At 200% zoom it SHALL remain usable with no content or function lost: page content wraps, and the navigation collapses to its icon rail. Data tables, graph canvases and the code editor MAY scroll in two dimensions.

#### Scenario: No sideways page scroll at 1280
- **WHEN** any page is shown in a 1280 by 800 window
- **THEN** the page has no horizontal scroll bar

#### Scenario: Zoomed to 200%
- **WHEN** an operator zooms a 1280-pixel window to 200%
- **THEN** every control on the page can still be reached and read, and the navigation shows its icon rail

### Requirement: Every page states what it is with one heading

Every page SHALL have exactly one top-level heading that names the view, and its section headings SHALL follow it without skipping a level. The cluster a page belongs to SHALL be stated next to that heading, not as a second top-level heading.

#### Scenario: A cluster page has one top-level heading
- **WHEN** an operator opens the subscriptions page of a cluster
- **THEN** the page's only top-level heading is "Subscriptions", and the cluster's name and environment are shown beside it

### Requirement: The console's typefaces ship with Rahdar

The console SHALL render with the typefaces it ships with, served from Rahdar itself. It SHALL NOT request fonts, scripts or styles from any other host, so it renders the same on every machine and on installations without internet access.

#### Scenario: An air-gapped installation renders the console's typefaces
- **WHEN** the console is opened on an installation with no internet access
- **THEN** it renders in its own typefaces and makes no request to another host

### Requirement: The SQL console is one workspace for writing, costing and reading a query

The SQL console SHALL show the query editor and its results in one workspace that fills the
window below the header, whose split the operator can resize, remembered per browser. The page
itself SHALL NOT scroll; the result grid SHALL scroll inside its pane with its header in view.
The operator SHALL be able to maximise the results over the editor, from a button and from the
keyboard, and restore them with the same control or Escape. Before a query runs, the console
SHALL state its estimated cost in words and numbers, and an estimate that is not available SHALL
be stated as unavailable, never as zero. A running query SHALL show its progress and SHALL be
cancellable from a button and from the keyboard. A query error SHALL be shown at its position in
the editor as well as in words. Results SHALL use the product's data table, with column
visibility and order chosen from its column menu. Escape SHALL keep moving focus out of the
editor when the results are not maximised.

#### Scenario: Cost before running
- **WHEN** an operator writes a query that scans a large subscription
- **THEN** the console states the estimated cost before the query is run

#### Scenario: Cancel a running query from the keyboard
- **WHEN** a query is running and the operator presses Mod+.
- **THEN** the query is cancelled and the console says so

#### Scenario: An error points at its position
- **WHEN** a query has a syntax error
- **THEN** the error is marked at its position in the editor and described in words

#### Scenario: A large result is read in place
- **WHEN** a query returns 500 rows in a 1280 by 800 window
- **THEN** the grid scrolls through all 500 rows inside its pane, its header stays in view, and the page does not scroll

#### Scenario: Results maximised
- **WHEN** an operator maximises the results and then presses Escape
- **THEN** the grid fills the workspace while maximised, and Escape restores the editor and the previous split

### Requirement: Topology states each node's role and health in words

The Topology view SHALL show, for every broker node, its high-availability role, its liveness, the node it is paired with and its version, with the state in words and colour only as emphasis. An unreachable node SHALL be shown as unreachable. Above the level-of-detail bound the view SHALL say what it is not showing. The view SHALL be operable from the keyboard, SHALL offer the same information as a table, and SHALL teach how to register a cluster when there is none.

#### Scenario: A backup node is labelled
- **WHEN** a cluster has a primary and a backup node
- **THEN** each node is labelled with its role and its pair in words

#### Scenario: The topology as a table
- **WHEN** an operator switches the Topology view to its table
- **THEN** every node, role, pairing, liveness and version shown in the graph is listed

### Requirement: A long name never collapses its column

A name a broker supplies (a subscription, a destination, a match pattern, a role) SHALL be shown on one
line in tables and lists, shortened in the middle when it does not fit and readable in full on
demand, and copyable. Such a column SHALL keep a minimum width that fits a readable name, so
that no layout ever breaks a name into one word or a few characters per line. Names MAY wrap
only in a detail view that has the whole width for them.

#### Scenario: A 200-character subscription name in the configuration view
- **WHEN** the configuration view lists a subscription whose name is 200 characters with no spaces
- **THEN** the name occupies one line, shortened in the middle, the full name is available on demand, and no other cell in the row wraps character by character

### Requirement: A plugin page is never covered by the host

A plugin's page SHALL be laid out in the same content area as Rahdar's own pages, below the
header and beside the navigation, so that nothing the host draws covers any part of it,
including its first element, in either colour scheme and when zoomed to 200%.

#### Scenario: A notice at the top of a plugin page
- **WHEN** a plugin page begins with a notice
- **THEN** the whole notice is visible below the header without scrolling

### Requirement: Plugins align their fields with the host's field row

The plugin SDK SHALL export the field row that Rahdar's own forms use, so that fields on one
line keep their input boxes aligned whatever labels, descriptions or messages they carry, and the
notice Rahdar's own pages use, so that a plugin's page-level notices read, contrast and announce
like Rahdar's.

#### Scenario: A plugin notice is announced like Rahdar's
- **WHEN** a plugin shows the SDK's notice with a warning tone
- **THEN** its title is stated in words and it is announced as a status

#### Scenario: A field with a description beside one without
- **WHEN** a plugin places a field with a description beside a field without one in a field row
- **THEN** both input boxes start on the same line

### Requirement: Administration SHALL use grouped navigation

The Administration page SHALL list its sections in a vertical navigation grouped under Access, Installation, Governance and Support. Encryption keys SHALL be listed under Installation and Rahdar's own health under Support. A plugin's section SHALL declare its group. The open section SHALL be held in the address. The open section SHALL be marked on its start edge with the accent and the selection tint, as the sidebar marks the open page, and the list SHALL stay in view while a long section scrolls.

#### Scenario: Plugin section

- **WHEN** a plugin contributes an administration section in the Governance group
- **THEN** it is listed under Governance

### Requirement: Held operations SHALL be visible and decidable in the UI

Rahdar SHALL show the outcome when an operation is held, not as a failure. The notice SHALL link to the request. Each request SHALL have a page showing:

- what will happen: changed values and the expected effect;
- the scope;
- the requester and how they signed in;
- the reason, the policy and the time left;
- a timeline of what happened to it.

The requester SHALL be able to cancel the request from that page. A provider SHALL be able to add its decision controls there. The Account page SHALL list the user's own requests. While break-glass is on, a banner SHALL say so on every page.

#### Scenario: Held purge in the UI

- **WHEN** a purge is held
- **THEN** the operator sees "Sent for approval" with a link to the request, not an error

#### Scenario: Requester cancels

- **WHEN** the requester cancels from the request page
- **THEN** the request shows as cancelled, and the approvers' notices are resolved

### Requirement: Sections are one navigation component with one alignment

A page that offers its sections as a list (Settings, Administration, Configuration) SHALL use the
console's one navigation component: every row a full-width, left-aligned link with the same padding as
its group heading, so labels and headings share one start edge; the open row marked with a full-row
highlight, the accent bar and `aria-current="page"`; hover and keyboard focus visible; labels in sentence
case, truncated with an ellipsis and a tooltip only when cut off. The list SHALL scroll within the window
when it is taller, with its group headings sticky.

Choosing a section SHALL NOT move the page: the scroll position SHALL be kept unless the section's top is out
of sight, the section SHALL NOT flash a loading state for a load that is quick, and the section's height
SHALL be held while it loads. The open section SHALL be in the address.

#### Scenario: Labels and headings share an edge

- **WHEN** Settings or Administration is shown
- **THEN** every row's label and every group heading start at the same edge, left-aligned

#### Scenario: Choosing a section keeps the page still

- **WHEN** an operator scrolled partway down a page chooses another section
- **THEN** the scroll position is unchanged and the page does not blink

### Requirement: Pages outside a cluster have no sidebar and lead back

A page that is not about one cluster (Administration, the inbox, the account, approvals) SHALL NOT show the
cluster sidebar. It SHALL show a line that offers to return to the cluster view the operator came from, with
the filters they left, and says where they are; with no previous view it SHALL offer to open a cluster.

#### Scenario: Returning from Administration

- **WHEN** an operator opens Administration from a cluster's Subscriptions view
- **THEN** one click on the line above the page returns to those Subscriptions

### Requirement: Paired fields clear together

A pair of fields that go together SHALL report a problem on the member that is missing, and SHALL be checked
again whenever either member changes, so the message clears as soon as the pair is complete.

#### Scenario: A username without a password

- **WHEN** an operator enters a username, then a password
- **THEN** no message remains on either field

### Requirement: Large lists render in time independent of their length
Every grid that can list more than a few hundred rows SHALL render only what is visible, so that opening and scrolling it stays responsive at the sizes in the sizing guide. Measuring column widths SHALL NOT cost more as the list grows.

#### Scenario: A very large grid opens
- **WHEN** a grid receives 50,000 rows
- **THEN** it becomes interactive within the guide's stated time, only a bounded number of rows are in the page, and scrolling holds the guide's stated frame rate

#### Scenario: Keyboard and selection still work
- **WHEN** a user selects rows or moves with the keyboard in a large grid
- **THEN** focus and selection behave as in a small grid

### Requirement: A virtualized grid can be searched in place
A grid that renders only visible rows SHALL provide its own find, which searches all rows and not only those in the page, shows the number of matches, and moves to each match, since the browser's find cannot see rows that are not rendered.

#### Scenario: A match is off screen
- **WHEN** a user searches a large grid for a value in a row that is not rendered
- **THEN** the grid shows the match count and scrolls to the row

#### Scenario: No match
- **WHEN** a search matches no row
- **THEN** the grid says there are no matches

### Requirement: A view that needs many broker reads makes them in batches
Views that read the same kind of value from many resources SHALL request them in batches of at most 200 rather than one call per resource, within the existing rate limits. Broker calls SHALL be counted in a metric by kind.

#### Scenario: A large cluster view loads
- **WHEN** a view covers 1,000 subscriptions
- **THEN** it makes five batched calls of at most 200, not 1,000 calls

#### Scenario: Calls are visible
- **WHEN** an operator reads the metrics
- **THEN** broker calls are counted by kind
