import { describe, expect, it } from 'vitest';

import { versionRangeWords } from './versionRange.ts';

describe('versionRangeWords', () => {
  it.each([
    ['[3.0.0,)', '3.0 and later'],
    ['[2.30.0,2.60.0]', '2.30 to 2.60'],
    ['[2.30.0,3.0.0)', '2.30 up to, not including, 3.0'],
    ['(,4.0.0)', 'before 4.0'],
    ['[2.30.1,)', '2.30.1 and later'],
    ['anything', 'anything'],
  ])('writes %s as %s', (range, words) => {
    expect(versionRangeWords(range)).toBe(words);
  });
});
