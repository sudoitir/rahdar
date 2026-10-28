import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { holdButton } from '../../test/hold.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { mockKafkaCluster } from '../../test/kafkaCluster.ts';

const ACL = {
  principal: 'User:app',
  host: '*',
  operation: 'READ',
  permission: 'ALLOW',
  resourceType: 'TOPIC',
  pattern: 'LITERAL',
  name: 'orders',
};

function serve(list: object, handlers: Parameters<typeof server.use> = []) {
  mockKafkaCluster();
  server.use(
    http.get('*/api/v1/clusters/k1/kafka/acls', () => HttpResponse.json(list)),
    ...handlers,
  );
}

describe('the ACLs', () => {
  it('lists them with their permission in words', async () => {
    serve({ available: true, items: [ACL, { ...ACL, principal: 'User:bad', permission: 'DENY' }], next: null });
    renderAppAt('/clusters/k1/kafka/acls');
    const grid = await screen.findByRole('grid', { name: 'ACLs' });
    expect(await within(grid).findByText('User:app')).toBeInTheDocument();
    expect(within(grid).getByText('Deny')).toBeInTheDocument();
  });

  it('says there is no authoriser, with the reason, instead of an empty list', async () => {
    serve({ available: false, reason: 'The cluster runs without an authorizer.', items: [], next: null });
    renderAppAt('/clusters/k1/kafka/acls');
    expect(await screen.findByText('This cluster has no authoriser to read')).toBeInTheDocument();
    expect(screen.getAllByText('The cluster runs without an authorizer.').length).toBeGreaterThan(0);
    expect(await screen.findByText('ACLs are not available')).toBeInTheDocument();
  });

  it('reviews the access a removal would take away, and applies it after a hold with the etag', async () => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    serve({ available: true, items: [ACL], next: null }, [
      http.post('*/api/v1/clusters/k1/kafka/acls\\:preview', async ({ request }) => {
        calls.push({ path: 'preview', body: (await request.json()) as never });
        return HttpResponse.json({
          effects: [{ principal: 'User:app', resource: 'topic orders', before: ['READ'], after: [] }],
          losses: ['User:app loses READ on topic orders'],
          notes: [],
          etag: 'a1',
        });
      }),
      http.post('*/api/v1/clusters/k1/kafka/acls\\:apply', async ({ request }) => {
        calls.push({ path: 'apply', body: (await request.json()) as never });
        return HttpResponse.json({});
      }),
    ]);
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/acls');
    await user.click(await screen.findByRole('button', { name: /^Remove Allow User:app READ on topic orders/ }));
    expect(screen.getByText('1 change to review')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review changes' }));

    const dialog = await screen.findByRole('dialog', { name: 'Apply 1 ACL change' });
    expect(within(dialog).getByText('User:app loses READ on topic orders')).toBeInTheDocument();
    await holdButton(within(dialog).getByRole('button', { name: 'Apply 1 change' }));
    await waitFor(() => expect(calls.map((c) => c.path)).toEqual(['preview', 'apply']));
    expect(calls[0].body).toEqual({ add: [], remove: [ACL] });
    expect(calls[1].body).toMatchObject({ etag: 'a1' });
  });

  it('adds an ACL to the changes after checking the principal beside its field', async () => {
    serve({ available: true, items: [], next: null });
    const user = userEvent.setup();
    renderAppAt('/clusters/k1/kafka/acls');
    await user.click(await screen.findByRole('button', { name: 'Add an ACL' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add an ACL' });

    await user.type(within(dialog).getByRole('textbox', { name: 'Principal' }), 'app');
    await user.type(within(dialog).getByRole('textbox', { name: 'Resource name' }), 'orders');
    await user.click(within(dialog).getByRole('button', { name: 'Add to the changes' }));
    expect(await within(dialog).findByText(/Write the principal as Type:name/)).toBeInTheDocument();

    await user.clear(within(dialog).getByRole('textbox', { name: 'Principal' }));
    await user.type(within(dialog).getByRole('textbox', { name: 'Principal' }), 'User:app');
    await user.click(within(dialog).getByRole('button', { name: 'Add to the changes' }));
    expect(await screen.findByText('1 ACL to add')).toBeInTheDocument();
    expect(screen.getByText(/Allow User:app READ on topic orders/)).toBeInTheDocument();
  });
});
