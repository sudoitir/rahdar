import { describe, expect, it } from 'vitest';

import type { RabbitMqConnectionDetail } from './api.ts';
import {
  RABBITMQ_EMPTY,
  parseAmqpEndpoints,
  parseManagementUrls,
  rabbitMqConnection,
  rabbitMqFieldsOf,
  rabbitMqProblems,
  rabbitMqUpdate,
  secretRequired,
  type RabbitMqFields,
} from './rabbitmqConnection.ts';

const STORED: RabbitMqConnectionDetail = {
  managementUrls: ['http://r1:15672'],
  amqpEndpoints: ['amqp://r1:5672'],
  username: 'guest',
  tokenAuth: false,
  secretStored: true,
  tlsBundle: null,
  eventVhost: '/',
  watchIntervalSeconds: 10,
};

const fields = (over: Partial<RabbitMqFields> = {}): RabbitMqFields => ({ ...rabbitMqFieldsOf(STORED), ...over });

describe('parseManagementUrls', () => {
  it('defaults the port by scheme, lower-cases, drops trailing slashes and keeps a prefix', () => {
    const parsed = parseManagementUrls('http://R1, https://r2/ http://r3:8080/rabbit/ \n http://r1:15672');
    expect(parsed.map((e) => e.address)).toEqual(['http://r1:15672', 'https://r2:15671', 'http://r3:8080/rabbit']);
  });

  it('accepts an IPv6 address in brackets', () => {
    expect(parseManagementUrls('http://[::1]')[0]?.address).toBe('http://[::1]:15672');
  });

  it.each([
    ['http://guest:pw@r1', /Remove the account/],
    ['r1:15672', /is not an address/],
    ['amqp://r1', /must start with http/],
    ['http://r1?x=1', /Remove the query or fragment/],
    ['http://r1:abc', /must be a number/],
    ['http://r1:70000', /between 1 and 65535/],
    ['http://bad_host!', /no valid host name/],
  ])('refuses %s', (raw, problem) => {
    expect(parseManagementUrls(raw)[0]?.problem).toMatch(problem);
  });
});

describe('parseAmqpEndpoints', () => {
  it('defaults the port by scheme and refuses a path', () => {
    expect(parseAmqpEndpoints('amqp://r1 amqps://r2').map((e) => e.address)).toEqual([
      'amqp://r1:5672',
      'amqps://r2:5671',
    ]);
    expect(parseAmqpEndpoints('amqp://r1/vhost')[0]?.problem).toMatch(/Remove the path/);
    expect(parseAmqpEndpoints('amqp://u:p@r1')[0]?.problem).toMatch(/Remove the account/);
  });
});

describe('secretRequired', () => {
  it('is always true for a registration, and when no secret is stored', () => {
    expect(secretRequired(fields(), null)).toBe(true);
    expect(secretRequired(fields(), { ...STORED, secretStored: false })).toBe(true);
  });

  it('is false for an unchanged connection and for new values that change nothing the server compares', () => {
    expect(secretRequired(fields(), STORED)).toBe(false);
    expect(secretRequired(fields({ tlsBundle: 'x', eventVhost: 'v', watchIntervalSeconds: '3' }), STORED)).toBe(false);
  });

  it.each([
    ['a management URL', { managementUrls: 'http://r1:15672 http://r2:15672' }],
    ['an AMQP endpoint', { amqpEndpoints: '' }],
    ['the user', { username: 'other' }],
    ['the kind of credential', { auth: 'token' as const }],
  ])('is true when %s changes', (_, over) => {
    expect(secretRequired(fields(over), STORED)).toBe(true);
  });

  it('ignores the user name of a token account', () => {
    const token = { ...STORED, username: null, tokenAuth: true };
    expect(secretRequired(fields({ auth: 'token', username: 'x' }), token)).toBe(false);
  });
});

describe('rabbitMqProblems', () => {
  it('asks for a management URL, the account and the secret on a registration', () => {
    expect(rabbitMqProblems(RABBITMQ_EMPTY)).toMatchObject({
      managementUrls: 'Add at least one management URL.',
      username: 'Enter the user name.',
    });
    expect(rabbitMqProblems({ ...RABBITMQ_EMPTY, managementUrls: 'http://r1', username: 'u' })).toMatchObject({
      password: 'Enter the password for this user name, or clear the user name.',
    });
    expect(rabbitMqProblems({ ...RABBITMQ_EMPTY, managementUrls: 'http://r1', auth: 'token' })).toEqual({
      token: 'Enter the token.',
    });
  });

  it('reports a bad AMQP endpoint and a bad interval by field', () => {
    const problems = rabbitMqProblems(fields({ amqpEndpoints: 'http://r1', watchIntervalSeconds: '0' }), STORED);
    expect(problems.amqpEndpoints).toMatch(/must start with amqp/);
    expect(problems.watchIntervalSeconds).toMatch(/at least 1/);
  });

  it('asks for the password again only when the connection changed', () => {
    expect(rabbitMqProblems(fields(), STORED)).toEqual({});
    expect(rabbitMqProblems(fields({ managementUrls: 'http://r9' }), STORED)).toEqual({
      password: 'Enter the password again: the connection changed.',
    });
  });
});

describe('request bodies', () => {
  it('leaves out what is empty on a registration', () => {
    expect(
      rabbitMqConnection({ ...RABBITMQ_EMPTY, managementUrls: 'http://r1', username: 'u', password: 'p' }),
    ).toEqual({
      system: 'rabbitmq',
      managementUrls: ['http://r1:15672'],
      credentials: { username: 'u', password: 'p' },
    });
  });

  it('sends the TLS bundle, vhost and interval that were set', () => {
    expect(
      rabbitMqConnection({
        ...RABBITMQ_EMPTY,
        managementUrls: 'https://r1',
        amqpEndpoints: 'amqps://r1',
        auth: 'token',
        token: 't',
        tlsBundle: 'b',
        eventVhost: 'v',
        watchIntervalSeconds: '5',
      }),
    ).toEqual({
      system: 'rabbitmq',
      managementUrls: ['https://r1:15671'],
      amqpEndpoints: ['amqps://r1:5671'],
      credentials: { token: 't' },
      tlsBundle: 'b',
      eventVhost: 'v',
      watchIntervalSeconds: 5,
    });
  });

  it('sends a whole update without a stored secret, clearing a bundle that no longer applies', () => {
    expect(rabbitMqUpdate(fields({ tlsBundle: 'gone' }))).toEqual({
      system: 'rabbitmq',
      managementUrls: ['http://r1:15672'],
      amqpEndpoints: ['amqp://r1:5672'],
      credentials: { username: 'guest' },
      tlsBundle: '',
      eventVhost: '/',
      watchIntervalSeconds: 10,
    });
  });
});
