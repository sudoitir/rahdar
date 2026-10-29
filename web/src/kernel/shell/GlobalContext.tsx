import { Anchor, Text } from '@mantine/core';
import { IconArrowLeft } from '@tabler/icons-react';
import { Link, useLocation } from '@tanstack/react-router';

import { useLastPlace } from './lastPlace.ts';
import classes from './GlobalContext.module.css';

/** What a page outside any cluster is called, by the first segment of its address. */
const HERE: Record<string, string> = {
  admin: 'Administration',
  account: 'Account',
  inbox: 'Inbox',
  approvals: 'Approvals',
  'clusters/new': 'Register a cluster',
};

/**
 * The strip above a page outside any cluster: the way back to where the operator was, and where they
 * are. These pages have no sidebar, because it would only hold a cluster picker; this line is how an
 * operator leaves them, in one click, for the view they came from. With no previous place it offers
 * to open a cluster.
 */
export function GlobalContext() {
  const { pathname } = useLocation();
  const place = useLastPlace();
  const segments = pathname.split('/').filter(Boolean);
  const root = HERE[segments.slice(0, 2).join('/')] ? segments.slice(0, 2).join('/') : (segments[0] ?? '');
  const here = HERE[root];
  const nested = segments.length > root.split('/').length;

  return (
    <nav aria-label="Where you are" className={classes.line}>
      {place ? (
        <Anchor component={Link} to={place.to} search={place.search as never} size="sm" className={classes.back}>
          <IconArrowLeft size={14} aria-hidden />
          <span>
            Back to {place.clusterName} · {place.label}
          </span>
        </Anchor>
      ) : (
        <Anchor component={Link} to="/" size="sm" className={classes.back}>
          <IconArrowLeft size={14} aria-hidden />
          <span>Open a cluster</span>
        </Anchor>
      )}
      {here ? (
        <>
          <span aria-hidden className={classes.separator}>
            ›
          </span>
          {nested ? (
            <Anchor component={Link} to={`/${root}`} size="sm" c="dimmed">
              {here}
            </Anchor>
          ) : (
            <Text size="sm" c="dimmed" aria-current="page">
              {here}
            </Text>
          )}
        </>
      ) : null}
    </nav>
  );
}
