import { useState } from 'react';
import { Button, ColorSwatch, List, Stack, Text } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCluster, useDeleteCluster, useEnvironments } from './api.ts';
import classes from './Clusters.module.css';
import styles from './RemoveClusterSection.module.css';

const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };

/**
 * Settings section: remove this cluster from Rahdar (cluster-registration spec). It lives here, last in the
 * cluster's settings, rather than above every view of the cluster. What goes and what stays is stated before
 * the typed name arms the button (non-negotiable #2).
 */
export function RemoveClusterSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  const remove = useDeleteCluster();
  const navigate = useNavigate();
  const { can, loading } = useCan();
  const [confirming, setConfirming] = useState(false);
  // While grants load the control is offered; the server is the enforcement point.
  const denied = !loading && !can('cluster:write', clusterId);
  const name = cluster.data?.name;
  const environment = useEnvironments().data?.find((e) => e.id === cluster.data?.environmentId);

  if (cluster.isError) return <ErrorState error={cluster.error} onRetry={() => void cluster.refetch()} />;
  if (!name) return <LoadingState label="Loading the cluster" blockSize="16rem" />;

  return (
    <div className={styles.danger}>
      <Stack gap="sm">
        <Text size="sm">
          Removing <strong>{name}</strong> deletes what Rahdar keeps for it:
        </Text>
        <List size="sm" spacing={2}>
          <List.Item>its registration, nodes and stored broker credentials;</List.Item>
          <List.Item>
            its alert rules, request-reply flows, message-index subscriptions and configuration history.
          </List.Item>
        </List>
        <Text size="sm">
          Nothing on the broker changes: subscriptions, messages and any capture diverts stay. The audit trail is kept.
          Registering the cluster again starts from scratch.
        </Text>

        {denied ? (
          <Text size="sm" c="dimmed">
            You cannot remove this cluster: that needs the <code>cluster:write</code> permission on it.
          </Text>
        ) : null}

        <Button
          variant="default"
          disabled={denied}
          className={classes.start}
          onClick={() => {
            remove.reset();
            setConfirming(true);
          }}
        >
          Remove cluster…
        </Button>
      </Stack>

      <ConfirmDialog
        opened={confirming}
        onClose={() => setConfirming(false)}
        title="Remove cluster"
        consequence={
          <Stack gap="xs">
            {/* Which environment this is, beside the name to type, so a production cluster is not removed for a test one. */}
            {environment ? (
              <Text component="span" size="sm" className={styles.environment}>
                <ColorSwatch component="span" color={environment.colour ?? 'var(--as-border)'} size="0.75rem" />
                In {environment.name}
              </Text>
            ) : null}
            <span>
              Removes <strong>{name}</strong> from Rahdar, with its registration, nodes, stored broker credentials,
              alert rules, request-reply flows, message-index subscriptions and configuration history. Nothing on the
              broker changes.
            </span>
            {remove.isError ? (
              <>
                <ErrorState variant="inline" error={remove.error} />
                <span>
                  It is still registered; try again, or check that you still hold <code>cluster:write</code> on it.
                </span>
              </>
            ) : null}
          </Stack>
        }
        confirmLabel="Remove cluster"
        tone="danger"
        pending={remove.isPending}
        onConfirm={() =>
          remove.mutate(clusterId, {
            onSuccess: () => {
              notify.succeeded({ action: REMOVE, subject: `cluster ${name}` });
              setConfirming(false);
              void navigate({ to: '/' });
            },
          })
        }
      />
    </div>
  );
}
