import { describe, expect, it } from 'vitest';
import { userEvent } from 'vitest/browser';
import { useForm } from '@mantine/form';
import { screen } from '@testing-library/react';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import type { KafkaConnectionCheck } from './api.ts';
import { KAFKA_EMPTY, kafkaProblems, type KafkaFields } from './kafkaConnection.ts';
import { KafkaCheckPanel } from './KafkaCheckPanel.tsx';
import { KafkaConnectionFields } from './KafkaConnectionFields.tsx';

/** The Kafka registration parts as Chromium lays them out and a keyboard walks them. */

const LONG = 'broker-with-a-very-long-advertised-host-name.eu-west-1.internal.example.com';

function check(over: Partial<KafkaConnectionCheck> = {}): KafkaConnectionCheck {
  return {
    system: 'kafka',
    version: { text: 'at least 3.5', mode: 'ZooKeeper', exact: false },
    supported: true,
    clusterId: 'Mk3OEYBSD34fcwNTJENDM2Qk',
    alreadyRegistered: null,
    nodes: [
      { id: '1', address: `${LONG}:9092`, functions: ['BROKER'], outcome: 'OK', cause: null, message: null },
      {
        id: '2',
        address: `internal-${LONG}:9092`,
        functions: ['BROKER', 'CONTROLLER'],
        outcome: 'UNREACHABLE',
        cause: 'DNS',
        message: 'unknown host',
      },
    ],
    quorum: { available: false, reason: 'the cluster runs ZooKeeper' },
    access: [
      {
        capability: 'Read consumer group offsets',
        status: 'UNAVAILABLE',
        reason: 'The account may not describe groups',
      },
    ],
    failure: null,
    ...over,
  };
}

const CAUSES = ['DNS', 'REFUSED', 'TIMEOUT', 'TLS', 'AUTHENTICATION', 'AUTHORIZATION', 'UNSUPPORTED_VERSION'] as const;

describe.each(SCHEMES)('the Kafka connection check in the %s scheme', (scheme) => {
  it.each([contentWidth(1280), 640])('is axe-clean and inside its box at %i px, partly reached', async (width) => {
    const { container } = renderThemed(
      <Frame width={width} height={900}>
        <KafkaCheckPanel check={check()} />
      </Frame>,
      scheme,
    );
    const panel = await screen.findByText('Some brokers were not reached');
    await settle(() => String(container.scrollWidth));
    const frame = panel.closest('[aria-live]')!.parentElement!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
    expect(await axeViolations(container)).toEqual([]);
  });

  it.each(CAUSES)('is axe-clean for the failure cause %s', async (cause) => {
    const { container } = renderThemed(
      <Frame width={contentWidth(1280)} height={900}>
        <KafkaCheckPanel
          check={check({ failure: { cause, message: 'It failed here.', next: 'Do this.' }, nodes: [] })}
        />
      </Frame>,
      scheme,
    );
    await screen.findByText('Next: Do this.');
    expect(await axeViolations(container)).toEqual([]);
  });
});

/** Opens the focused list and walks it with the arrow keys to the option, as a keyboard user would. */
async function chooseByKeyboard(option: string) {
  await userEvent.keyboard('{ArrowDown}');
  for (let step = 0; step < 6; step++) {
    const active = document.querySelector('[role="option"][data-combobox-selected]');
    if (active?.textContent === option) break;
    await userEvent.keyboard('{ArrowDown}');
  }
  await userEvent.keyboard('{Enter}');
}

function Fields() {
  const form = useForm<KafkaFields>({
    initialValues: KAFKA_EMPTY,
    validateInputOnBlur: true,
    validate: (values) => kafkaProblems(values),
  });
  return (
    <form noValidate>
      <KafkaConnectionFields form={form} />
    </form>
  );
}

describe('the Kafka connection fields by keyboard', () => {
  it('walks from the servers to the credentials of a SASL protocol without a pointer', async () => {
    renderThemed(
      <Frame width={contentWidth(1280)} height={900}>
        <Fields />
      </Frame>,
      'light',
    );
    const servers = await screen.findByRole('textbox', { name: /Bootstrap servers/ });
    servers.focus();
    await userEvent.keyboard('k1, k2');
    await userEvent.tab();
    expect(screen.getByRole('combobox', { name: 'Security protocol' })).toHaveFocus();

    // Open the list, move to "SASL over TLS" and choose it.
    await chooseByKeyboard('SASL over TLS');
    expect(await screen.findByRole('combobox', { name: 'SASL mechanism' })).toBeInTheDocument();
    expect(screen.getByText(/Host name verification is always on/)).toBeInTheDocument();

    await userEvent.tab();
    expect(screen.getByRole('combobox', { name: 'SASL mechanism' })).toHaveFocus();
    await chooseByKeyboard('PLAIN');
    await userEvent.tab();
    expect(screen.getByLabelText('Username')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByLabelText('Password')).toHaveFocus();
  });

  it('is axe-clean with the SASL fields open', async () => {
    const { container } = renderThemed(
      <Frame width={contentWidth(1280)} height={900}>
        <Fields />
      </Frame>,
      'dark',
    );
    const protocol = await screen.findByRole('combobox', { name: 'Security protocol' });
    protocol.focus();
    await chooseByKeyboard('SASL over plaintext');
    await screen.findByText('The credentials travel unencrypted');
    expect(await axeViolations(container)).toEqual([]);
  });
});
