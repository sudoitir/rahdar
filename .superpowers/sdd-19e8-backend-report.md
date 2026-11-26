# 19e8 Rahdar backend report

Branch `wip/local-snapshot` in `worktrees/rahdar-19e8-kafka-schema-registry`. Not pushed. No PR.

## Commits

| Hash | Subject |
| --- | --- |
| `3af04d109d70c2412329af747b0b25b5f5719b5a` | feat(kafka): record a cluster's schema registry and topic bindings |
| `a9b524f124683253406a6a4caa2149d580b72525` | feat(kafka): give schema registry its own permissions |
| `e5dc62d6c6bd7f4aaa1fdc70ec7b1f6abc04dae8` | feat(kafka): preview a schema change before it is applied |
| `3afd12558e08d3bae23d43a49ddf23eebccad6d7` | feat(kafka): add the schema registry API |
| `bdbfa2539ef0e404b9fe784511d4cae74cbbdffa` | feat(kafka): show a decoded record beside the wire bytes |
| `d84903dda949966add028d3b1814a7bb700bd331` | test(kafka): cover schema preview, credentials and decoded browse |
| `daee889a` | fix(kafka): refuse a schema preview without schema:write |
| `a22a06c3` | docs: record schema permissions and the decoded-record field |

`daee889a` and `a22a06c3` are the short hashes from `git log --oneline`. Full hashes were not re-read after those two commits.

## Checks

| Command | Result |
| --- | --- |
| `./mvnw -q -DskipTests compile` after persistence | exit 0 |
| `./mvnw -q -DskipTests test-compile` after persistence | exit 0 |
| `./mvnw -q -Dtest=BuiltInRolesCoverageTest test` | Tests run: 7, Failures: 0, Errors: 0 |
| `./mvnw -q -DskipTests compile` after reviews | exit 0 |
| `./mvnw -q -DskipTests compile` after controller | exit 0 |
| `./mvnw -Dtest=OpenApiSnapshotTest test` first try | Errors: 3, Ryuk could not connect at localhost:54880. Retried. |
| `./mvnw -Dtest=OpenApiSnapshotTest test` second try | Failures: 2. Snapshot rewritten (expected). GET `/kafka/schemas/bindings` returned a bare array. Wrapped in `SchemaBindingPage` and retried. |
| `./mvnw -q -Dtest=OpenApiSnapshotTest test` after envelope | Failures: 1, snapshot rewritten (expected) |
| `npm --prefix web run gen:api` | exit 0 after `npm install` in `web/` (worktree had no `node_modules`; `openapi-typescript` was missing, exit 127, before install) |
| `./mvnw -q -Dtest=OpenApiSnapshotTest test` after gen:api | Tests run: 3, Failures: 0, Errors: 0. EXIT:0 |
| `./mvnw -q -DskipTests compile` after decode | exit 0, then OpenAPI regen again, final snapshot green (Tests run: 3, Failures: 0) |
| `./mvnw -q -Dtest=KafkaSchemaReviewsTest,KafkaSchemaCredentialTest,KafkaSchemaApprovalTest,KafkaSchemaBrowseTest,PolicyEngineTest test` | all green. Reviews 6, Credential 3, Approval 1, Browse 5, PolicyEngine 13. Failures: 0 |
| `openspec validate 19e8-kafka-schema-registry --strict` | Change '19e8-kafka-schema-registry' is valid |
| `just api-diff` | No breaking changes to report. Specs differ (additive). "No breaking change." |
| `./mvnw -q -Dtest=KafkaSchemaReviewsTest,KafkaSchemaApprovalTest test` after the write-permission fix | exit 0 |

`just fmt` was started once and exited 2. Spotless apply succeeded (0 files changed). The prettier/eslint tail was truncated by the tool, so the eslint half was not confirmed. Later compiles ran `spotless:apply` and kept the Java clean. `schema.d.ts` was passed through prettier after each `gen:api`.

## Not run

- `SchemaLintTest`. The binding table uses a composite primary key `(cluster_id, topic, side)`, which the lint exempts from `uuidv7()` and which leads the foreign-key index. Not executed against Postgres.
- MCP tools (`KafkaMcpSchemaTools`). Left unchecked in `tasks.md`. The web screen is also unchecked.
- A full `./mvnw verify`. CI runs that.

## What a reviewer should check

- Mutation previews require `schema:write`. Listing subjects and reading one subject require `schema:read`. A reader cannot preview a registration.
- `SchemaPreview.statement` is always "Nothing was written." and `writesNothing` is always true.
- Permanent delete preview forces the note "The schemas cannot be restored." even if the driver note does not.
- Registry credential is sealed with AAD `clusterId|kafka.registry`, is not on `KafkaConnectionDetail`, and is redacted by `Problems.of`. Empty on edit keeps the stored secret unless the registry kind, URL, region or name changed.
- The installed `openbroker-kafka` 0.9.0-SNAPSHOT no longer has `REGISTRY_GLUE`, `REGISTRY_GLUE_REGION` or `REGISTRY_GLUE_REGISTRY`. Glue region and registry name are still stored. They are not copied onto driver options the driver does not read. A kind other than `confluent` is treated by the current driver as no registry.
- Decode is filled only in `BrowsedMessages.of(Message, SchemaDecoding)`. `browseLog` passes the catalog and the value binding. Capture and queue browse still call `of(Message)`, which does not decode. SQL rows built from the index have no decoded field.
- `DecodedRecordView` lives in `kernel.core` because `platform.broker` and `platform.governance` cannot import each other.
- Gate types are `schema.write` (one params record for register, compatibility, default, delete) and `schema.binding`.
- Idempotency-Key is the existing filter, not a new parameter.
