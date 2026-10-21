import { alertingFeature } from '../features/alerting/feature.ts';
import { apitokensFeature } from '../features/apitokens/feature.ts';
import { auditFeature } from '../features/audit/feature.ts';
import { bulkFeature } from '../features/bulk/feature.ts';
import { brokerconfigFeature } from '../features/brokerconfig/feature.ts';
import { clustersFeature } from '../features/clusters/feature.ts';
import { diagnosticsFeature } from '../features/diagnostics/feature.ts';
import { eventsFeature } from '../features/events/feature.ts';
import { flowFeature } from '../features/flow/feature.ts';
import { governanceFeature } from '../features/governance/feature.ts';
import { identityLocalFeature } from '../features/identity-local/feature.ts';
import { mcpFeature } from '../features/mcp/feature.ts';
import { lifecycleFeature } from '../features/lifecycle/feature.ts';
import { pluginsFeature } from '../features/plugins/feature.ts';
import { messagesFeature } from '../features/messages/feature.ts';
import { metricsFeature } from '../features/metrics/feature.ts';
import { resourcesFeature } from '../features/resources/feature.ts';
import { routingFeature } from '../features/routing/feature.ts';
import { rrFeature } from '../features/rr/feature.ts';
import { securityFeature } from '../features/security/feature.ts';
import { setupreviewFeature } from '../features/setupreview/feature.ts';
import { settingsFeature } from '../features/settings/feature.ts';
import { sqlFeature } from '../features/sql/feature.ts';
import { transferFeature } from '../features/transfer/feature.ts';
import { triageFeature } from '../features/triage/feature.ts';
import type { RahdarFeature } from '../kernel/feature.ts';

/**
 * The composition root (ADR-0069): the one list of frontend features. Each feature uses its backend
 * module's id, so the manifest decides which of them this installation offers. The order is the order
 * of their palette groups; nav entries and slot contributions carry their own.
 */
export const FEATURES: RahdarFeature[] = [
  clustersFeature,
  metricsFeature,
  alertingFeature,
  flowFeature,
  triageFeature,
  rrFeature,
  messagesFeature,
  bulkFeature,
  transferFeature,
  sqlFeature,
  resourcesFeature,
  routingFeature,
  setupreviewFeature,
  brokerconfigFeature,
  settingsFeature,
  lifecycleFeature,
  eventsFeature,
  auditFeature,
  securityFeature,
  governanceFeature,
  identityLocalFeature,
  apitokensFeature,
  pluginsFeature,
  diagnosticsFeature,
  mcpFeature,
];
