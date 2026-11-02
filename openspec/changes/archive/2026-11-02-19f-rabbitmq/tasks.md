# Tasks

## Task list (file-disjoint units)

Rules for every implementer: work in the change's worktree, read narrowly (grep, then the lines), run only the
narrow check named below inside `systemd-run --user --scope -q -p MemoryMax=3G -p MemorySwapMax=2G --`, at most
two heavy commands across all agents, Java at `/opt/jdk25` (`JAVA_HOME=/opt/jdk25`), `./mvnw -o` once
dependencies are cached. **No Docker on this machine**: real-broker tests are written and compiled
(`./mvnw -q test-compile`) and run only on CI. Web: `cd web && npx vitest run --project unit <path>`. Commit on
the unit's branch with the commit types of D16; no PRs.

**Wave 0 (lead, inline).** `openspec/changes/19f-rabbitmq/**`, `docs/adr/0223-*.md`, `docs/adr/0224-*.md`;
OpenBroker 0.9.0-SNAPSHOT installed into `~/.m2` (`cd openbroker && ./mvnw -q -DskipTests install`).

| # | Unit | Files it owns | Depends on | Finish line (local) |
|---|---|---|---|---|
| B1 | SDK 0.9 bump | `pom.xml`; the two tests building `Preview`; `MessageService.requireOffered` lines only (capability constants) | W0 | `./mvnw -q test-compile` and `./mvnw -o test -Dtest='TruncateServiceTest,KafkaHangingTransactionsTest'` green |
| B2 | Registration contract (shared) | `ConnectionOptions.java`, `ClusterRegistrar.java`, `SystemRegistration.java`, `ClusterService.java` (connectionView), `ClusterRequests.java`, `ClusterViews.java`, `KafkaRegistrar.java` (detail type only) | B1 | `./mvnw -o test -Dtest='KafkaRegistrarTest,SystemsControllerTest,ClusterRequestsJsonTest'` green (`ClusterRequestsJsonTest` is new: the RabbitMQ union round trip and the redacted toString) |
| B3 | Whole-cluster path (shared) | `WholeClusterOperations.java`, `IdentityBasis.java`, `ClusterKinds.java`, `PagedListService.java`, `QueueLifecycleService.java`, `QueueGatedOperations.java`, `ResourceLifecycleController.java`, lifecycle outcome view, `BulkService.java`, `BulkRunner.java`, `PluginMessagingReconciler.java`, their tests | B1 | `-Dtest='ClusterKindsTest,WholeClusterOperationsTest,QueueLifecycleServiceTest,QueueLifecycleServiceWholeClusterTest,BulkLogPurgeTest,BulkWholeClusterTest,PluginMessagingReconcilerChangeTest'` green (`WholeClusterOperationsTest`, `QueueLifecycleServiceWholeClusterTest`, `BulkWholeClusterTest` are new) |
| B4 | Messages on a cluster read as one (shared) | `BrokerMessages.java` (minus B1 lines), `BrokerFailures.java`, `QueueRow.java`, `LogBounds.java`, `MessageService.java` (minus B1 lines), `MessageActionParams.java`, `MessageController.java`, `MessageRequests.java`, `MessageViews.java`, their tests | B1, B3 (`readAsOne`, `WholeClusterOperations` signatures fixed by this doc, so B4 may start on stubs) | `-Dtest='BrokerMessagesTest,QueueRowTest,MessageServiceTest,MessageControllerWholeClusterTest,BacklogTest'` green (`QueueRowTest` and `MessageControllerWholeClusterTest` are new; the latter proves a classic-queue browse makes no broker read) |
| B5 | RabbitMQ module core | `system/rabbitmq/{package-info,RabbitMqModule,RabbitMqConnectionOptions,RabbitMqRegistrar,ManagementUrls,AmqpEndpoints,AmqpProbe,RabbitMqFailures,RabbitMqClusterIdentity,RabbitMqReservedNames}.java`, `internal/persistence/**`, the changelog files, master include, `RahdarFeatures.java`, `BoundaryRulesTest.java`, unit tests and `RabbitMqModuleTest` | B2 | `-Dtest='RabbitMqRegistrarTest,ManagementUrlsTest,RabbitMqReservedNamesTest,RabbitMqModuleTest,BoundaryRulesTest,ModularityTest'` green (`ModularityTest` and `SchemaLintTest` need Docker → CI; run `ModularityTest` if it does not) |
| B6 | Scrape and health | `RabbitMqScrape.java`, `RabbitMqHealth.java`, `RabbitMqHealthStore.java`, `RabbitMqClusterDeleted.java`, `RabbitMqLogBounds.java`, tests | B4 (QueueRow helper), B5 | `-Dtest='RabbitMqScrapeTest,RabbitMqHealthTest,RabbitMqLogBoundsTest'` green |
| B7 | Namespace API | `RabbitMqNodes, RabbitMqVhosts, RabbitMqQueues, RabbitMqExchanges, RabbitMqBindings, RabbitMqPolicies, PolicyMatches, RabbitMqPermissions, RabbitMqShovels, RabbitMqFederation, RabbitMqGatedOperations`, `web/RabbitMqController`, `web/RabbitMqViews`, `ResourcePermissions.java` (access-rule), `KafkaModule.java`, security baseline grants, tests | B3, B5 | `-Dtest='PolicyMatchesTest,RabbitMqPoliciesTest,RabbitMqPermissionsTest,RabbitMqGatedOperationsTest,RabbitMqAuditClosureTest'` green; `OpenApiSnapshotTest` (or the test that writes `web/openapi.json`) run once at the end of this unit |
| B8 | MCP | `system/rabbitmq/mcp/**`, `McpToolSchemaBudgetTest` | B7 | `-Dtest='RabbitMqMcpViewsTest,McpToolSchemaBudgetTest'` green |
| B9 | Real-broker tests (CI) | `RabbitMqBrokers.java`, the four `*RealBrokerTest`s | B5–B7 | `./mvnw -q test-compile` green; CI runs them |
| W1 | Shared web | `kernel/slots.ts`, `kernel/feature.ts`, `kernel/auth/kindVerbs.ts`, `ui/IdentityBasisNote.tsx`, `features/resources/*` touched files, `features/messages/{MessagesView,PartitionControls,PurgeSubscription}.tsx`, `test/wire.ts`, `kernel/api/schema.d.ts` (regenerate from B7's snapshot) | B7 (snapshot) | `npx vitest run --project unit src/features/resources src/features/messages src/kernel` green; `npm run typecheck` (or `tsc -b`) green |
| W2 | Registration web | `features/clusters/{RegisterClusterPage,ClusterSettings,ConnectionCheckPanel,RegistrationFailure,checkColumns,connectionCheck}`, new `RabbitMq*` and `rabbitmqConnection.ts` with tests | W1 (wire.ts) | `npx vitest run --project unit src/features/clusters` green |
| W3 | RabbitMQ feature web | `features/rabbitmq/**`, `app/features.ts` | W1 | `npx vitest run --project unit src/features/rabbitmq` green |
| S1 | Stacks and seed | compose files, `deploy/compose/dev/rabbitmq/*`, `scripts/lib/rabbitmq-seed.sh`, `qa-seed.sh`, `demo-seed.sh` | B5 | `bash -n` on each script; `docker compose -f … config -q` where Docker CLI exists, else review; shellcheck if installed |
| S2 | Sweep | `web/scripts/sweep/rabbitmq.ts`, `routes.ts`, `sweep.ts` | W2, W3, S1 | `npx tsc --noEmit -p web/scripts` (or the repo's sweep typecheck); captures on a machine with Docker |
| D1 | Docs | README, NOTICE, Branding pair, dockerhub, guide pages, site config, fa pages | W3 | `npm run format:check` in `web` and the site's build if quick (`cd site && npm run build`) |

Order: **W0 → B1 → (B2 ∥ B3) → (B4 ∥ B5) → (B6 ∥ B7) → (B8 ∥ B9 ∥ W1 ∥ S1) → (W2 ∥ W3) → (S2 ∥ D1) → lead**.
The lead merges the unit branches into one session branch, runs `just fmt`, regenerates the OpenAPI snapshot
once, rebases on `main`, opens **one** PR with auto-merge after OpenBroker 0.9.0 is on Central and the pom
points at it, and fixes every Sonar issue on the PR.

---

