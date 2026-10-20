import { useId, type ReactNode } from 'react';
import { Button, Text } from '@mantine/core';

import classes from './EmptyState.module.css';

/**
 * What a view shows when it has no rows, in one of three kinds. Each says what the resource is, why
 * there is none and what to do next, so an empty view is never a bare "No data".
 *
 * <ul>
 *   <li>`empty`: there is nothing yet. `description` says what the resource is and how one comes to
 *       exist; `action` is the control that creates one, only where the operator may.
 *   <li>`filtered`: a filter excludes every row. It offers "Clear filters".
 *   <li>`unreachable`: rows are absent because `nodes` could not be reached, so the view names them
 *       instead of presenting the absence as a fact.
 * </ul>
 *
 * The title is a plain line, not a heading, so the state fits under whatever heading level its page
 * is at.
 */
export function EmptyState(props: EmptyStateProps) {
  const titleId = useId();
  const { title, action } = props;
  let description: ReactNode;
  let extra: ReactNode = null;
  switch (props.kind) {
    case 'empty':
      description = props.description;
      break;
    case 'filtered':
      description = props.description ?? 'No rows match the current filters.';
      extra = (
        <Button variant="default" size="xs" onClick={props.onClearFilters}>
          Clear filters
        </Button>
      );
      break;
    case 'unreachable':
      description = props.description ?? 'Rahdar could not reach these nodes, so this may not be everything.';
      break;
  }
  return (
    // A region named by its title, announced politely when it replaces the rows: <output> holds only
    // inline content, and an empty state holds a list and actions.
    <section className={classes.root} data-kind={props.kind} aria-live="polite" aria-labelledby={titleId}>
      <div id={titleId} className={classes.title}>
        {title}
      </div>
      <Text size="sm" component="div" className={classes.description}>
        {description}
      </Text>
      {props.kind === 'unreachable' ? (
        <ul className={classes.nodes} aria-label="Nodes that could not be reached">
          {props.nodes.map((node) => (
            <li key={node}>{node}</li>
          ))}
        </ul>
      ) : null}
      {extra || action ? (
        <div className={classes.actions}>
          {extra}
          {action}
        </div>
      ) : null}
    </section>
  );
}

export type EmptyStateProps = Readonly<
  | {
      kind: 'empty';
      /** What is missing, such as "No subscriptions". */
      title: string;
      /** What the resource is and why there is none. */
      description: ReactNode;
      /** The control that creates one; omit it where the operator may not. */
      action?: ReactNode;
    }
  | {
      kind: 'filtered';
      title: string;
      /** Replaces "No rows match the current filters." */
      description?: ReactNode;
      /** Resets every filter on the view. */
      onClearFilters: () => void;
      action?: ReactNode;
    }
  | {
      kind: 'unreachable';
      title: string;
      /** Replaces the default sentence. */
      description?: ReactNode;
      /** The nodes that could not be reached. */
      nodes: readonly string[];
      action?: ReactNode;
    }
>;
