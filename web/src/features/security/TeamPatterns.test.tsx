import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { TeamPatterns } from './TeamPatterns.tsx';
import { choose, preview, serveLookups, team } from '../../test/teams.ts';
import { holdButton } from '../../test/hold.ts';

afterEach(() => act(() => notifications.clean()));

function renderPatterns(props: Partial<Parameters<typeof TeamPatterns>[0]> = {}) {
  return renderWithProviders(
    <>
      <Notifications />
      <TeamPatterns team={team()} {...props} />
    </>,
  );
}

const patternBox = () => screen.getByRole('textbox', { name: /^Pattern/ });

/** Serves the preview for whatever pattern is asked, recording what was asked. */
function servePreview(answer: (pattern: string) => ReturnType<typeof preview> | Response) {
  const asked: string[] = [];
  server.use(
    http.get('*/api/v1/teams/t-orders/patterns/preview', ({ request }) => {
      const pattern = new URL(request.url).searchParams.get('pattern') ?? '';
      asked.push(pattern);
      const result = answer(pattern);
      return result instanceof Response ? result : HttpResponse.json(result);
    }),
  );
  return asked;
}

describe('TeamPatterns preview', () => {
  it('shows how many subscriptions match, with examples, before anything is saved', async () => {
    serveLookups();
    servePreview((pattern) => preview({ pattern }));
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'payments.#');

    const live = await screen.findByRole('status', { name: 'Pattern preview' });
    await waitFor(() => expect(live).toHaveTextContent('Matches 12 subscriptions on prod now.'));
    expect(within(live).getByText('orders.in')).toBeInTheDocument();
    expect(within(live).getByText('orders.dlq')).toBeInTheDocument();
  });

  it('names the other team and pattern as an inline error, and sends nothing on Add', async () => {
    serveLookups();
    servePreview((pattern) =>
      preview({
        pattern,
        conflicts: [{ teamId: 't-billing', teamName: 'Billing', kind: 'SUBSCRIPTION', pattern: 'orders.audit.#' }],
      }),
    );
    let posted = false;
    server.use(
      http.post('*/api/v1/teams/t-orders/patterns', () => {
        posted = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'orders.audit.*');

    expect(await screen.findByText(/Overlaps the subscriptions pattern "orders.audit.#" of team Billing/)).toBeInTheDocument();
    await person.click(screen.getByRole('button', { name: 'Add pattern' }));

    expect(posted).toBe(false);
    expect(patternBox()).toHaveFocus();
  });

  it('names a malformed pattern at once, without asking the server about it', async () => {
    serveLookups();
    const asked = servePreview((pattern) => preview({ pattern }));
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'orders..in');
    await person.tab();

    expect(await screen.findByText(/The pattern has an empty word/)).toBeInTheDocument();
    expect(asked).toEqual([]);
  });

  it('says when a pattern matches nothing yet', async () => {
    serveLookups();
    servePreview((pattern) => preview({ pattern, subscriptionMatches: 0, subscriptionExamples: [] }));
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'later.#');

    expect(await screen.findByText(/Matches nothing on prod today/)).toBeInTheDocument();
  });

  it('shows why a preview is unavailable instead of a blank', async () => {
    serveLookups();
    servePreview(() => HttpResponse.json({ title: 'Boom', detail: 'The broker did not answer.' }, { status: 502 }));
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'orders.#');

    expect(await screen.findByText(/The broker did not answer/)).toBeInTheDocument();
  });

  it('adds the pattern and announces it', async () => {
    serveLookups();
    servePreview((pattern) => preview({ pattern }));
    let body: unknown;
    server.use(
      http.post('*/api/v1/teams/t-orders/patterns', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(
          { id: 'p2', clusterId: 'c-prod', kind: 'SUBSCRIPTION', pattern: 'orders.v2.#' },
          { status: 201 },
        );
      }),
    );
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'orders.v2.#');
    await screen.findByText(/Matches 12 subscriptions/);
    await person.click(screen.getByRole('button', { name: 'Add pattern' }));

    await waitFor(() => expect(body).toEqual({ clusterId: 'c-prod', kind: 'SUBSCRIPTION', pattern: 'orders.v2.#' }));
    expect(await screen.findByText(/Added pattern orders\.v2\.# to Orders/)).toBeInTheDocument();
  });

  it("puts the server's overlap refusal beside the pattern field", async () => {
    serveLookups();
    servePreview((pattern) => preview({ pattern }));
    server.use(
      http.post('*/api/v1/teams/t-orders/patterns', () =>
        HttpResponse.json(
          {
            type: 'https://rahdar/problems/team-pattern-overlap',
            title: 'Overlap',
            detail: 'orders.x.# overlaps orders.# of team Billing.',
          },
          { status: 409 },
        ),
      ),
    );
    const person = userEvent.setup();
    renderPatterns();

    await choose(person, screen, /Cluster/, 'prod');
    await person.type(patternBox(), 'orders.x.#');
    await screen.findByText(/Matches 12 subscriptions/);
    await person.click(screen.getByRole('button', { name: 'Add pattern' }));

    expect(
      await screen.findByText('orders.x.# overlaps orders.# of team Billing.', { selector: 'p' }),
    ).toBeInTheDocument();
    expect(patternBox()).toHaveFocus();
  });

  it('pre-fills the form from a name picked in the unowned list', async () => {
    serveLookups();
    servePreview((pattern) => preview({ pattern }));
    renderPatterns({ draft: { clusterId: 'c-prod', kind: 'DESTINATION', pattern: 'legacy.inbox', nonce: 1 } });

    expect(patternBox()).toHaveValue('legacy.inbox');
    expect(await screen.findByRole('radio', { name: 'Destinations' })).toBeChecked();
  });
});

describe('TeamPatterns removal', () => {
  it('states what the pattern matches now before it can be armed', async () => {
    serveLookups();
    servePreview((pattern) => preview({ pattern, subscriptionMatches: 7 }));
    let removed = '';
    server.use(
      http.delete('*/api/v1/teams/t-orders/patterns/:id', ({ params }) => {
        removed = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderPatterns();

    await person.click(await screen.findByRole('button', { name: 'Remove pattern orders.#' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove orders.# from Orders' });
    await waitFor(() => expect(dialog).toHaveTextContent('It matches 7 subscriptions and 0 destinations on prod now.'));
    const confirm = within(dialog).getByRole('button', { name: 'Remove pattern' });
    await holdButton(confirm);

    await waitFor(() => expect(removed).toBe('p1'));
  });

  it('says the count is unavailable when the preview fails', async () => {
    serveLookups();
    servePreview(() => HttpResponse.json({ title: 'Boom', detail: 'down' }, { status: 502 }));
    const person = userEvent.setup();
    renderPatterns();

    await person.click(await screen.findByRole('button', { name: 'Remove pattern orders.#' }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toHaveTextContent('is unavailable right now'));
  });
});

describe('TeamPatterns as a team admin', () => {
  it('shows the patterns read-only, with the reason', async () => {
    serveLookups(['team:admin']);
    renderPatterns();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove pattern orders.#' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Add pattern' })).toBeDisabled();
    expect(patternBox()).toBeDisabled();
    expect(screen.getByText(/needs the user:admin permission/)).toBeInTheDocument();
  });

  it('offers the controls while the grants are still loading', async () => {
    server.use(http.get('*/api/v1/auth/me', () => new Promise(() => {})));
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json({ data: [], page: 1, pageSize: 500, count: 0, hasNext: false }),
      ),
    );
    renderPatterns();

    expect(await screen.findByRole('button', { name: 'Remove pattern orders.#' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Add pattern' })).toBeEnabled();
  });
});
