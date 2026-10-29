import type { ReactNode } from 'react';
import { Button } from '@mantine/core';

import { heldOf } from './held.ts';

/** What the failure is, why, and what the operator can do about it. */
export interface Reading {
  title: string;
  cause: string;
  next: ReactNode;
  /** Whether trying again can help without the operator changing something first. */
  retry: boolean;
  fields?: readonly { field: string; message: string }[];
  requestId?: string;
  /** What the server said about this failure, when a mapping's own words win and would otherwise drop it. */
  detail?: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
const text = (value: unknown): string | undefined => (typeof value === 'string' && value ? value : undefined);

/** Broker failures, by `brokerErrorKind`. The kind wins over the status, which only says who was at fault. */
const BROKER: Readonly<Record<string, Omit<Reading, 'requestId' | 'detail'>>> = {
  UNREACHABLE: {
    title: 'The broker is unreachable',
    cause: 'Nothing answered at the broker address.',
    next: 'Check that the broker is running and that Rahdar can reach its address, then retry.',
    retry: true,
  },
  THROTTLED: {
    title: 'Rahdar is throttling calls to this broker',
    cause: 'Rahdar is already calling this broker as fast as its configured rate allows, so it held this call back.',
    next: 'Retry in a moment. The broker itself was not asked.',
    retry: true,
  },
  CREDENTIALS_REJECTED: {
    title: 'The broker rejected the credentials',
    cause: 'The broker refused the user name and password Rahdar holds for it.',
    next: "Update the account under the cluster's Connection settings, then retry.",
    retry: false,
  },
  NOT_ARTEMIS: {
    title: 'There is no Artemis broker at this agent',
    cause: 'A Jolokia agent answered, but no Artemis broker is registered on it.',
    next: 'Point the cluster at the Jolokia agent of an Artemis broker.',
    retry: false,
  },
  WRONG_PATH: {
    title: 'There is no Jolokia agent at this address',
    cause: 'The address answered, but not with a Jolokia agent.',
    next: 'Check the path. The Artemis console usually serves Jolokia at /console/jolokia.',
    retry: false,
  },
  TLS_FAILED: {
    title: 'The TLS handshake with the broker failed',
    cause: 'Rahdar could not establish a secure connection to the broker.',
    next: "Check the broker's certificate and the cluster's TLS settings, then retry.",
    retry: false,
  },
  BAD_RESPONSE: {
    title: 'The broker sent an unexpected response',
    cause: 'The broker answered in a way Rahdar does not understand.',
    next: "Retry. If it keeps happening, check the broker's logs.",
    retry: true,
  },
  UNSUPPORTED_VERSION: {
    title: 'This Artemis version is not supported',
    cause: 'Rahdar does not support the version this broker runs.',
    next: 'Connect a broker on a supported Artemis version.',
    retry: false,
  },
};

function seconds(n: number): string {
  return `${n} ${n === 1 ? 'second' : 'seconds'}`;
}

/**
 * Whether the request never got an answer: an explicit status 0, or the `TypeError` `fetch` throws
 * when the network fails (its message differs by browser). Any other `TypeError`, such as a render
 * crash, is the console's own bug, not the network.
 */
function isNetworkFailure(error: unknown, status: number | undefined): boolean {
  if (status === 0) return true;
  return error instanceof TypeError && /fetch|network|load failed/i.test(error.message);
}

/** What `read` takes from an error's problem body, for the status readings to quote. */
interface Problem {
  status: number;
  title?: string;
  detail?: string;
  permission?: string;
  requestId?: string;
  retryAfter?: number;
  /** The server's own next step for this failure. */
  hint?: string;
  fields?: NonNullable<Reading['fields']>;
}

const NETWORK_DOWN: Reading = {
  title: 'Rahdar could not be reached',
  cause: 'The request got no answer. The network may be down, or Rahdar may be restarting.',
  next: 'Check your connection, then retry.',
  retry: true,
};

function unexpected(error: unknown): Reading {
  return {
    title: 'An unexpected error occurred in the console',
    cause:
      (error instanceof Error ? text(error.message) : undefined) ?? 'The console failed in a way it did not expect.',
    next: 'Retry, or reload the page. If it keeps happening, report the message above.',
    retry: true,
  };
}

function forbidden({ permission }: Problem): Reading {
  return {
    title: 'You are not allowed to do this',
    cause: permission ? `Your role does not include the ${permission} permission.` : 'Your role does not allow it.',
    next: permission
      ? `Ask an administrator to grant ${permission}.`
      : 'Ask an administrator to grant the permission it needs.',
    retry: false,
  };
}

function invalid({ title, detail, fields }: Problem): Reading {
  // A 422 that names no field is the server's own refusal, and its title and detail say it best.
  if (!fields?.length) {
    return {
      title: title ?? 'The request was refused',
      cause: detail ?? 'Rahdar could not accept the request as sent.',
      next: 'Correct the request, then try again.',
      retry: false,
    };
  }
  return {
    title: 'Some values are not valid',
    cause: detail ?? 'Rahdar could not accept the request as sent.',
    next: 'Correct the fields listed here, then submit again.',
    retry: false,
    fields,
  };
}

function rateLimited({ detail, retryAfter }: Problem): Reading {
  return {
    title: 'Too many requests',
    cause: detail ?? 'Rahdar is limiting how fast requests can be made.',
    next: retryAfter ? `Wait ${seconds(retryAfter)}, then retry.` : 'Wait a moment, then retry.',
    retry: true,
  };
}

function serverFailure({ detail, requestId }: Problem): Reading {
  return {
    title: 'Rahdar failed to complete the request',
    cause: detail ?? 'An error occurred on the server.',
    next: 'Retry. If it keeps happening, report it with the request id.',
    retry: true,
    requestId,
  };
}

function refused({ title, detail, status }: Problem): Reading {
  return {
    title: title ?? 'The request failed',
    cause: detail ?? `Rahdar refused the request (status ${status}).`,
    next: 'Check the request, then try again.',
    retry: false,
  };
}

/** The readings of the statuses that have their own; any other 5xx is a server failure, any other status a refusal. */
const BY_STATUS: Readonly<Record<number, (problem: Problem) => Reading>> = {
  401: () => ({
    title: 'You are signed out',
    cause: 'Your session ended or was never started.',
    next: (
      <Button component="a" href="/login" variant="default" size="xs">
        Sign in again
      </Button>
    ),
    retry: false,
  }),
  403: forbidden,
  404: ({ detail }) => ({
    title: 'Not found',
    cause: detail ?? 'It does not exist, or it was removed.',
    next: 'Check the address, or go back to the list and choose it again.',
    retry: false,
  }),
  409: ({ detail }) => ({
    title: 'This conflicts with the current state',
    cause: detail ?? 'Something changed, or it conflicts with something that already exists.',
    next: 'Reload to see the current state, then try again.',
    retry: true,
  }),
  422: invalid,
  429: rateLimited,
};

/**
 * Problems that read by their type, which win over the status, which only says who was at fault: the approval
 * gate's refusals, each saying that nothing ran because an operator who meets one mid-action needs that first, and
 * a view the cluster's system does not have.
 */
const BY_TYPE: Readonly<Record<string, (problem: Problem) => Reading>> = {
  'unavailable-on-system': ({ detail }) => ({
    title: 'This system does not have this',
    cause: detail ?? 'The system this cluster runs has nothing of this kind to list.',
    next: 'Choose another view from the sidebar.',
    retry: false,
  }),
  'operation-denied': ({ detail }) => ({
    title: 'The approval policy denied this',
    cause: detail ?? 'The approval policy does not allow this operation.',
    next: 'Nothing was changed. If it must be done, ask an administrator about the policy.',
    retry: false,
  }),
  'approval-unavailable': ({ detail }) => ({
    title: 'Approvals are unavailable',
    cause: detail ?? 'The approval provider did not answer, so Rahdar did not run the operation.',
    next: 'Nothing was changed. Try again in a moment, or ask an administrator to check the approval provider.',
    retry: true,
  }),
  'approver-quorum': ({ detail }) => ({
    title: 'Too few approvers',
    cause: detail ?? 'This would leave fewer than two people who can approve, so nothing could be approved.',
    next: 'Nothing was changed. Grant the approver permission to another person first, or remove the approval policies.',
    retry: false,
  }),
  'vote-refused': ({ detail }) => ({
    title: 'You cannot decide this request',
    cause: detail ?? 'The approval rules do not let you decide this request.',
    next: 'Nothing was decided. Someone else who may approve it has to decide it.',
    retry: false,
  }),
  'session-required': ({ detail }) => ({
    title: 'Decide from a signed-in session',
    cause:
      detail ?? 'A request is decided only by a person signed in to Rahdar, not with an API token or an assistant.',
    next: 'Nothing was decided. Sign in to Rahdar in a browser and decide it there.',
    retry: false,
  }),
};

/** The last segment of a problem's `type` URI, such as `operation-denied`. */
const slugOf = (type: unknown): string | undefined =>
  typeof type === 'string' ? type.slice(type.lastIndexOf('/') + 1) || undefined : undefined;

/**
 * The reading of an approval gate refusal, or `undefined` for any other error: for a mutation's toast,
 * whose own cause and next step would otherwise read as an ordinary failure.
 */
export function readGateRefusal(error: unknown): Reading | undefined {
  const e = isRecord(error) ? error : {};
  const slug = slugOf(e.type);
  const byType = slug ? BY_TYPE[slug] : undefined;
  return byType && typeof e.status === 'number' ? byType(problemOf(e, e.status)) : undefined;
}

/** The parts of an error's problem body that a reading quotes. */
function problemOf(e: Record<string, unknown>, status: number): Problem {
  const problem = isRecord(e.problem) ? e.problem : {};
  const { retryAfter } = problem;
  return {
    status,
    title: text(problem.title) ?? text(e.title),
    detail: text(problem.detail),
    hint: text(problem.hint),
    permission: text(problem.permission),
    requestId: text(problem.requestId),
    retryAfter: typeof retryAfter === 'number' && retryAfter > 0 ? retryAfter : undefined,
    fields: Array.isArray(e.fieldErrors) ? (e.fieldErrors as Reading['fields']) : undefined,
  };
}

/** A broker failure's reading; a rejection names the account, so the operator edits the right one. */
function brokerReading(kind: string, broker: (typeof BROKER)[string], e: Record<string, unknown>): Reading {
  const problem = isRecord(e.problem) ? e.problem : {};
  const account = problem.account === 'CORE' ? 'Core' : 'management';
  const cause =
    kind === 'CREDENTIALS_REJECTED' && text(problem.account)
      ? `The broker refused the ${account} account Rahdar holds for it.`
      : broker.cause;
  return { ...broker, cause, detail: text(problem.detail) };
}

/**
 * Reads an error by its shape, for `ErrorState` and for a caller that needs only its title. An operation held
 * for approval reads as sent, not failed; an approval gate refusal reads by its problem type. An `ApiError` carries `status`, `brokerErrorKind`, `fieldErrors` and
 * the whole problem body as `problem` (`title`, `detail`, `hint`, `permission`, `retryAfter`,
 * `requestId`); a string `hint` is the next step; a 422 with no field errors reads as its own title and
 * detail; a failed
 * fetch reads as the network being down; anything else with no status is an error inside the console.
 */
export function readError(error: unknown): Reading {
  const held = heldOf(error);
  if (held) {
    // Not a failure: the operation waits for a second person. ErrorState shows it as such.
    return {
      title: 'Sent for approval',
      cause: `${held.summary} waits for a second person.`,
      next: 'Nothing has run yet. It runs once someone else approves it.',
      retry: false,
    };
  }
  const gate = readGateRefusal(error);
  if (gate) return gate;
  const e = isRecord(error) ? error : {};
  const given = typeof e.status === 'number' ? e.status : undefined;
  const kind = text(e.brokerErrorKind);
  const broker = kind ? BROKER[kind] : undefined;
  if (kind && broker) return brokerReading(kind, broker, e);
  if (isNetworkFailure(error, given)) return NETWORK_DOWN;
  if (given === undefined) return unexpected(error);
  const problem = problemOf(e, given);
  const reading = (BY_STATUS[given] ?? (given >= 500 ? serverFailure : refused))(problem);
  // The server's own next step wins, except where the next step is the sign-in control.
  return problem.hint && given !== 401 ? { ...reading, next: problem.hint } : reading;
}
