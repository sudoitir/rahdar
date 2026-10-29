import { Link } from '@tanstack/react-router';

import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { Notice } from '../../ui/Notice.tsx';
import { alreadyRegistered, type ProblemDetail } from './api.ts';

/** A failed check or registration: brokers that are registered already get their own notice. */
export function RegistrationFailure({ error }: Readonly<{ error: unknown }>) {
  const registered = alreadyRegistered(error);
  return registered ? <AlreadyRegistered problem={registered} /> : <ErrorState variant="inline" error={error} />;
}

/**
 * The brokers already belong to a registered cluster (ADR-0167). Registering them again would show the
 * same brokers twice, so the form says which cluster has them and links to it; when the operator may not
 * see that cluster, the server leaves it unnamed and there is no link.
 */
function AlreadyRegistered({ problem }: Readonly<{ problem: ProblemDetail }>) {
  const { existingClusterId: id, existingClusterName: name } = problem;
  return (
    <Notice
      title={problem.title ?? 'These brokers are already registered'}
      tone="danger"
      action={
        id ? (
          <Link to={`/clusters/${id}`} className={linkClasses.link}>
            {`Open ${name ?? 'that cluster'}`}
          </Link>
        ) : undefined
      }
    >
      {problem.detail}
    </Notice>
  );
}
