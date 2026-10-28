/** A version without its trailing zero patch: "3.0.0" is "3.0", "2.30.1" stays. */
const short = (version: string): string => version.replace(/(\.0)+$/, '').replace(/^(\d+)$/, '$1.0');

/**
 * A driver's supported range, written as Maven does (`[3.0.0,)`), in words: "3.0 and later",
 * "2.30 to 2.60", "before 4.0". A string that is not a range is returned as it came.
 */
export function versionRangeWords(range: string): string {
  const match = /^([[(])\s*([^,\s]*)\s*,\s*([^\])\s]*)\s*([\])])$/.exec(range.trim());
  if (!match) return range;
  const [, , low, high, close] = match;
  const from = low ? short(low) : null;
  const to = high ? short(high) : null;
  if (from && to) return close === ']' ? `${from} to ${to}` : `${from} up to, not including, ${to}`;
  if (from) return `${from} and later`;
  if (to) return close === ']' ? `${to} and earlier` : `before ${to}`;
  return range;
}
