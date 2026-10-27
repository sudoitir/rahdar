import { afterEach, describe, expect, it } from 'vitest';
import { act, screen } from '@testing-library/react';
import { Notifications, notifications } from '@mantine/notifications';

import { axeViolations, renderThemed, SCHEMES } from '../test/browser.tsx';
import { notify } from './notify.ts';

const SEND = { verb: 'Send', past: 'Sent', progressive: 'Sending' };
const SUBJECT = 'a message to subscription "orders.payments.eu-west-1.settlement-requests"';
const DETAIL =
  "The message was sent, but the broker's confirmation never came back: it may or may not be on the broker. " +
  'Look for it before sending it again, or it may be there twice.';

describe('notify.inDoubt', () => {
  afterEach(() => {
    act(() => notifications.clean());
  });

  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('says the outcome is not confirmed, in words, assertively, with no accessibility violation', async () => {
      renderThemed(<Notifications position="top-right" />, scheme);
      act(() => {
        notify.inDoubt({ action: SEND, subject: SUBJECT, detail: DETAIL });
      });

      const toast = await screen.findByRole('alert');
      expect(toast).toHaveTextContent(`Not confirmed: sending ${SUBJECT}`);
      expect(toast).toHaveTextContent('Look for it before sending it again');
      expect(toast).not.toHaveTextContent(/^Sent /);
      await new Promise((r) => setTimeout(r, 400));
      expect(await axeViolations(document.body)).toEqual([]);
    });
  });
});
