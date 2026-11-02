# RabbitMQ screenshot sweep (S2)

Run: `just qa-up <name> <port>`, then `npm --prefix web run sweep -- --only rabbitmq,rabbitmq-shared,rabbitmq-regression,onboarding`.
Final matrix: 1350 captures (light 600, dark 600, system 150) over 1920, 1440, 1280 and 200% zoom.

### rabbitmq-1 [S1 · reliability · page] Deleting a queue from the subscription screens was refused
- Where: `features/resources/api.ts` lifecycle hooks.
- Evidence: the delete preview answered "rabbitmq.classic-queue is named by 2 id segment(s), not 1": the call rebuilt the id from the name and dropped the virtual host.
- Fix: the hooks take the subscription's own id.
- Status: fixed (6 hooks and their call sites; resources tests pass).

### rabbitmq-2 [S2 · design · page] The second row action (Delete, Clear) was invisible
- Where: `VhostsView`, `PermissionsView`, `PoliciesView` Change column.
- Evidence: two buttons stacked in a one-line row, the second clipped; then "Ed" and "Del" cut by a narrow column.
- Fix: a horizontal group and a column wide enough for both.
- Status: fixed.

### rabbitmq-3 [S2 · reliability · page] A read-only account got a 409 listing channels and connections
- Where: `PagedListService.readableProducers`.
- Evidence: "This cluster's system has no producer resources to list."
- Fix: a system with no producer kind has no producers to see.
- Status: fixed (no new test: the service's tests are Artemis fixtures).

### rabbitmq-4 [S2 · design · page] Queues and exchanges of one name in two virtual hosts were indistinguishable; the panel title began with "/"; ids showed percent signs
- Fix: a Virtual host column for RabbitMQ, a title built from the parts present, decoded references. Status: fixed (resourceId tests).

### rabbitmq-5 [S2 · design · page] A stream's messages page spoke of partitions and consumer groups; the page jumped when the cluster loaded
- Fix: stream wording, the write-uncertainty notice left off logs, stream layout chosen from the route before the cluster loads. Status: fixed.

### rabbitmq-6 [S3 · design · page] Filtering by an unknown virtual host showed "Not found"; the virtual host list covered the Name field; the registration buttons were cut off
- Status: fixed.

### Open
- Consumer health on a RabbitMQ cluster labels every row "Classic queue" and has no virtual host: the triage view builds ids from names (design D6 notes the gap).
- The delete dialog still says "forward route into this subscription's destination" and states the identity basis twice.
- The vhost "counts are not complete" notice moves the table when it arrives (stub-only).
- Zoom captures of row-level scenes (Kafka's too) time out on the click.
