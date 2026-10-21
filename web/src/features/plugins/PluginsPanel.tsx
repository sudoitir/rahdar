import { useCallback, useMemo, useState, type DragEvent } from 'react';
import { Button, FileButton, Text } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useCheckUpdates, usePlugins, type PluginUpdateView, type PluginView } from './api.ts';
import { pluginColumns } from './columns.ts';
import { InstallDialog, type Source } from './InstallDialog.tsx';
import { InstallersDialog } from './InstallersDialog.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { TrustedKeysDialog } from './TrustedKeysDialog.tsx';
import { PluginDrawer } from './PluginDrawer.tsx';
import styles from './Plugins.module.css';
import { RestartControl } from './RestartControl.tsx';
import { GUIDE_URL, STATUS, needsAttention } from './words.ts';

type PluginsInventory = NonNullable<ReturnType<typeof usePlugins>['data']>;

const rowKey = (p: PluginView) => p.id;

const TEMPLATE_URL = `${branding.projectUrl}/tree/main/examples/plugin-template`;

/** Why a restart is wanted: plugins waiting for one, ones that did not stop, and versions still in memory. */
function restartReasonsOf(rows: PluginView[], unreleasedLabels: string[]): string[] {
  // "acme-notes 1.0.0" labels, grouped per plugin: "acme-notes (1.0.0, 1.0.1)".
  const unreleased = new Map<string, Set<string>>();
  for (const label of unreleasedLabels) {
    const [id, version] = label.split(' ');
    unreleased.set(id, (unreleased.get(id) ?? new Set()).add(version ?? ''));
  }
  return [
    ...rows.filter((p) => p.status === 'needs_restart').map((p) => `${p.info.title} starts after a restart.`),
    ...rows.filter((p) => p.stuck).map((p) => `${p.info.title} did not stop cleanly.`),
    ...[...unreleased].map(
      ([id, versions]) =>
        `Stopped versions of ${rows.find((p) => p.id === id)?.info.title ?? id} (${[...versions].join(', ')}) are still in memory; a restart frees it.`,
    ),
  ];
}

/** What the section says it is, in the page from the first paint. */
export function PluginsIntro() {
  return (
    <>
      Plugins add screens, assistant tools and data of their own to {branding.productShortName}. A plugin runs inside{' '}
      {branding.productShortName} with its access, so only install ones you trust.{' '}
      <a href={GUIDE_URL} target="_blank" rel="noopener noreferrer" className={linkClasses.link}>
        How plugins work
      </a>
    </>
  );
}

/** The outcome of the update check, in one sentence. */
function updatesSummary(checked: PluginUpdateView[]): string {
  if (checked.length === 0) return 'No installed plugin names an update URL to check.';
  const available = checked.filter((u) => u.availableVersion).length;
  if (available === 0) return 'Every plugin with an update URL is up to date.';
  return `${available} ${available === 1 ? 'update is' : 'updates are'} available.`;
}

/**
 * The plugins inventory (design.md §8). Near-monochrome while everything is well; a plugin that
 * needs someone sorts to the top with its state in words and its fix in its row. The whole panel
 * takes a dropped jar; nothing is installed until the operator has reviewed and confirmed it.
 */
function PluginsBody({ view }: Readonly<{ view: PluginsInventory }>) {
  const checkUpdates = useCheckUpdates();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { plugin?: string; upload?: string };
  const [source, setSource] = useState<Source | null>(() =>
    search.upload ? { kind: 'resume', sha: search.upload } : null,
  );
  const [installersOpen, setInstallersOpen] = useState(false);
  const [keysOpen, setKeysOpen] = useState(false);
  const [dragging, setDragging] = useState(false);

  const setSearch = useCallback(
    (next: { plugin?: string; upload?: string }) =>
      navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...next }), replace: true }),
    [navigate],
  );

  const updates = useMemo(
    () => new Map((checkUpdates.data ?? []).map((u: PluginUpdateView) => [u.id, u])),
    [checkUpdates.data],
  );
  const rows = useMemo(
    () =>
      [...(view?.plugins ?? [])].sort(
        (a, b) => Number(needsAttention(b)) - Number(needsAttention(a)) || a.info.title.localeCompare(b.info.title),
      ),
    [view],
  );
  const showLicense = rows.some((p) => p.license);
  const columns = useMemo(
    () =>
      pluginColumns({
        updates,
        onUpdate: (id) => setSource({ kind: 'update', id }),
        onOpen: (id) => void setSearch({ plugin: id }),
        showLicense,
      }),
    [updates, setSearch, showLicense],
  );

  const canInstall = view.canInstall && view.uploadEnabled;
  const cannotInstall = !view.uploadEnabled
    ? 'Installing and updating plugins is switched off on this installation (rahdar.plugins.upload.enabled=false).'
    : view.cannotInstall?.message;
  const attention = rows.filter(needsAttention);
  const open = rows.find((p) => p.id === search.plugin);

  const choose = (file: File | null) => {
    if (!file) return;
    setSource({ kind: 'file', file });
  };
  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    if (!canInstall) return;
    const file = [...event.dataTransfer.files].find((f) => f.name.endsWith('.jar'));
    choose(file ?? null);
  };

  const restartReasons = restartReasonsOf(rows, view.restart.unreleased);

  return (
    <div
      className={styles.drop}
      onDragOver={(e) => {
        if (!canInstall) return;
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setDragging(false);
      }}
      onDrop={onDrop}
    >
      {dragging ? (
        <div className={styles.overlay}>
          <Text fw={600}>Drop to inspect. Nothing is installed until you confirm.</Text>
        </div>
      ) : null}

      <Section
        title="Plugins"
        description={<PluginsIntro />}
        actions={
          <>
            <FileButton onChange={choose} accept=".jar,application/java-archive">
              {(props) => (
                <Button {...props} disabled={!canInstall}>
                  Install plugin…
                </Button>
              )}
            </FileButton>
            <Button
              variant="default"
              disabled={!canInstall}
              loading={checkUpdates.isPending}
              onClick={() => checkUpdates.mutate()}
            >
              Check for updates
            </Button>
            {view.canInstall ? (
              <Button variant="subtle" onClick={() => setInstallersOpen(true)}>
                Who can install
              </Button>
            ) : null}
            <Button variant="subtle" disabled={!view.canInstall} onClick={() => setKeysOpen(true)}>
              Trusted keys
            </Button>
          </>
        }
      >
        {!canInstall && cannotInstall ? (
          <Text size="sm" c="dimmed">
            {cannotInstall}
          </Text>
        ) : null}
        {view.canInstall ? null : (
          <Text size="sm" c="dimmed">
            Only someone who can install plugins can manage trusted keys.
          </Text>
        )}

        {view.safeMode ? (
          <Notice title="Safe mode: no plugin is running" tone="warning">
            {view.safeModeReason ?? 'Rahdar started without plugins.'} Fix or remove the plugin that failed, then
            restart Rahdar normally.
          </Notice>
        ) : null}

        <RestartControl restart={view.restart} reasons={restartReasons} canAct={view.canInstall} />

        {attention.length > 0 ? (
          <Text size="sm" className={styles.warning} role="status">
            {attention.length === 1 ? '1 plugin needs attention' : `${attention.length} plugins need attention`}:{' '}
            {attention.map((p) => `${p.info.title} (${(STATUS[p.status] ?? p.status).toLowerCase()})`).join(', ')}.
          </Text>
        ) : null}

        {checkUpdates.isError ? <ErrorState error={checkUpdates.error} onRetry={() => checkUpdates.mutate()} /> : null}
        {checkUpdates.data ? (
          <Text size="sm" role="status">
            {updatesSummary(checkUpdates.data)}
            {checkUpdates.data
              .filter((u) => u.error)
              .map((u) => ` ${u.id}: could not check (${u.error}).`)
              .join('')}
          </Text>
        ) : null}

        <DataTable
          label="Plugins"
          storageKey="plugins"
          height="fill"
          columns={columns}
          data={rows}
          rowKey={rowKey}
          onRowClick={(p) => setSearch({ plugin: p.id })}
          empty={
            <EmptyState
              kind="empty"
              title="No plugins yet"
              description={
                <>
                  A plugin is a single <code>.jar</code>. Drop one anywhere on this page, or choose Install plugin; you
                  see everything it will be able to do before anything is installed, and most plugins start without a
                  restart.
                </>
              }
              action={
                <>
                  <a href={GUIDE_URL} target="_blank" rel="noopener noreferrer" className={linkClasses.link}>
                    Build a plugin →
                  </a>
                  <a href={TEMPLATE_URL} target="_blank" rel="noopener noreferrer" className={linkClasses.link}>
                    Start from the template →
                  </a>
                </>
              }
            />
          }
        />

        <Text size="xs" c="dimmed" className={styles.num}>
          Database connections: {view.budget.inUse} in use of {view.budget.limit} allowed ({view.budget.maxConnections}{' '}
          maximum; each active plugin uses {view.budget.perPlugin}).
        </Text>
      </Section>

      <InstallDialog
        source={source}
        canInstall={canInstall}
        onClose={() => {
          setSource(null);
          if (search.upload) void setSearch({ upload: undefined });
        }}
      />
      <InstallersDialog opened={installersOpen} onClose={() => setInstallersOpen(false)} />
      <TrustedKeysDialog opened={keysOpen} onClose={() => setKeysOpen(false)} />
      <PluginDrawer
        plugin={open}
        canInstall={canInstall}
        cannotInstall={cannotInstall ?? undefined}
        onClose={() => setSearch({ plugin: undefined })}
        onUpdate={(id) => setSource({ kind: 'update', id })}
      />
    </div>
  );
}

/** Administration → Plugins (design.md §8). */
export function PluginsPanel() {
  const plugins = usePlugins();
  const view = plugins.data;
  if (view) return <PluginsBody view={view} />;
  // The section is in the page before its content, so nothing moves when the inventory arrives.
  return (
    <Section title="Plugins" description={<PluginsIntro />}>
      {plugins.isError ? (
        <ErrorState error={plugins.error} onRetry={() => void plugins.refetch()} />
      ) : (
        <LoadingState label="Loading plugins" blockSize="24rem" />
      )}
    </Section>
  );
}
