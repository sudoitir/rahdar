import { describe, expect, it } from 'vitest';

import { parseBootstrapServers } from './bootstrapServers.ts';

describe('parseBootstrapServers', () => {
  it('keeps every server typed in one field and defaults the port', () => {
    const parsed = parseBootstrapServers('k1:9092, k2\nk3:9093 ; k4');
    expect(parsed.map((s) => s.address)).toEqual(['k1:9092', 'k2:9092', 'k3:9093', 'k4:9092']);
  });

  it('keeps a server once', () => {
    expect(parseBootstrapServers('k1 k1:9092')).toHaveLength(1);
  });

  it('accepts an IPv6 address in brackets', () => {
    expect(parseBootstrapServers('[::1]:9094 [fe80::1]')[1]?.address).toBe('[fe80::1]:9092');
  });

  it.each([
    ['PLAINTEXT://k1:9092', /Remove the scheme/],
    ['user:pw@k1:9092', /Remove the account/],
    ['k1:abc', /must be a number/],
    ['k1:70000', /between 1 and 65535/],
    ['bad_host!', /not a host name/],
  ])('refuses %s', (raw, problem) => {
    const [first] = parseBootstrapServers(raw);
    expect(first?.address).toBeNull();
    expect(first?.problem).toMatch(problem);
  });

  it('is empty for nothing', () => {
    expect(parseBootstrapServers('  ,\n ')).toEqual([]);
  });
});
