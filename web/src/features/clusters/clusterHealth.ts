import type { ClusterSummary } from './api.ts';

type Health = ClusterSummary['health'];

/**
 * A cluster's health in words, from its conditions' level. The level is the same whatever system the
 * cluster runs, so clusters of different systems compare on it.
 */
export const HEALTH_WORD: Record<Health, string> = {
  OK: 'Healthy',
  DEGRADED: 'Degraded',
  CRITICAL: 'Critical',
  UNKNOWN: 'Unknown',
};

/** Colour only emphasises a fault. */
export const HEALTH_TONE: Record<Health, 'neutral' | 'warning' | 'danger'> = {
  OK: 'neutral',
  DEGRADED: 'warning',
  CRITICAL: 'danger',
  UNKNOWN: 'neutral',
};
