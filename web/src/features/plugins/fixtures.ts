import { http, HttpResponse } from 'msw';

import type { PluginInfoView, PluginPlanView, PluginsView, PluginView } from './api.ts';

/** Test fixtures for the plugin screens. Placeholder names only. */

export const INFO: PluginInfoView = {
  name: 'acme-notes',
  title: 'Notes',
  description: 'Shared notes on subscriptions.',
  vendor: { name: 'Acme', url: 'https://acme.example', email: null },
  license: 'Apache-2.0',
  changeNotes: null,
  since: '2026.01.0',
  until: null,
  restartToActivate: false,
  updateUrl: null,
  requires: [],
  requiresLicense: false,
  contributions: {
    ui: true,
    permissions: [{ action: 'acme-notes:write', description: 'Write notes' }],
    settingKeys: [],
    streamTopics: [],
    mcpTools: [{ name: 'acme_notes_search', posture: 'read', description: null }],
    identityProviders: [],
    auditReceive: false,
    auditRetentionHold: false,
  },
};

export function info(over: Partial<PluginInfoView> = {}): PluginInfoView {
  return { ...INFO, ...over };
}

export function plan(over: Partial<PluginPlanView> = {}): PluginPlanView {
  return {
    pluginId: 'acme-notes',
    fromVersion: null,
    toVersion: '1.0.0',
    activationClass: 'INSTANT',
    pendingChangesets: [],
    updateSql: '',
    reversible: true,
    diff: {
      permissionsAdded: [],
      permissionsRemoved: [],
      settingKeysAdded: [],
      settingKeysRemoved: [],
      streamTopicsAdded: [],
      streamTopicsRemoved: [],
      mcpToolsAdded: [],
      mcpToolsRemoved: [],
      identityProvidersAdded: [],
      identityProvidersRemoved: [],
      auditAdded: [],
      auditRemoved: [],
    },
    rolesLosingPermission: {},
    compatible: true,
    missingRequires: [],
    restart: 'NONE',
    trust: {
      status: 'TRUSTED',
      fingerprint: 'AB:CD:EF',
      subject: 'CN=Acme',
      keyName: 'Acme',
      previousFingerprint: null,
      signerChanged: false,
      allowed: true,
    },
    acknowledgements: [],
    info: INFO,
    ...over,
  };
}

export function plugin(over: Partial<PluginView> = {}): PluginView {
  return {
    id: 'acme-notes',
    version: '1.0.0',
    status: 'active',
    failure: null,
    progress: null,
    stepStartedAt: null,
    installedAt: new Date().toISOString(),
    activatedAt: new Date().toISOString(),
    installedBy: 'ops',
    sha256: 'a'.repeat(64),
    rollbackAvailable: false,
    stuck: false,
    iconUrl: null,
    dependants: [],
    signerFingerprint: 'AB:CD:EF',
    signerSubject: 'CN=Acme',
    verified: true,
    license: null,
    info: INFO,
    ...over,
  };
}

export function inventory(plugins: PluginView[], restart: Partial<PluginsView['restart']> = {}): PluginsView {
  return {
    canInstall: true,
    uploadEnabled: true,
    safeMode: false,
    safeModeReason: null,
    budget: { maxConnections: 100, inUse: 13, limit: 80, perPlugin: 3 },
    restart: {
      supervised: true,
      needed: false,
      restarting: false,
      allowedAt: null,
      command: 'docker compose restart rahdar',
      unreleased: [],
      ...restart,
    },
    plugins,
  };
}

/** The signed-in operator; `authenticatedAt` decides whether a plugin action needs a fresh sign-in. */
export function me(authenticatedAt: string | null = new Date().toISOString()) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );
}
