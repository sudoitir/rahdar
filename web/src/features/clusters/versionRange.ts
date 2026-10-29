/** A version without its trailing zero patch: "3.0.0" is "3.0", "2.30.1" stays. */
const short = (version: string): string => version.replace(/(\.0)+$/, '').replace(/^(\d+)$/, '$1.0');

const isSpace = (ch: string): boolean => /\s/.test(ch);

const endsLow = (ch: string): boolean => ch === ',' || isSpace(ch);

const endsHigh = (ch: string): boolean => ch === ']' || ch === ')' || isSpace(ch);

/** The index after the spaces that may sit around a comma or a bracket. */
const skipSpace = (text: string, index: number, end: number): number => {
  while (index < end && isSpace(text[index])) index += 1;
  return index;
};

/** The index of the next comma, bracket or space, which ends a version. */
const readUntil = (text: string, index: number, end: number, stop: (ch: string) => boolean): number => {
  while (index < end && !stop(text[index])) index += 1;
  return index;
};

/**
 * Maven range bounds, or null when `text` is not one range: an opening `[` or `(`, a low version, a comma,
 * a high version, and a closing `]` or `)`. Whitespace may sit around the comma and the brackets; a version
 * itself has none. The same strings the range words used to accept.
 */
function boundsOf(text: string): { low: string; high: string; close: string } | null {
  const open = text[0];
  const close = text.at(-1);
  if ((open !== '[' && open !== '(') || (close !== ']' && close !== ')')) return null;
  const end = text.length - 1;
  let index = skipSpace(text, 1, end);
  const lowStart = index;
  index = readUntil(text, index, end, endsLow);
  const low = text.slice(lowStart, index);
  index = skipSpace(text, index, end);
  if (text[index] !== ',') return null;
  index += 1;
  index = skipSpace(text, index, end);
  const highStart = index;
  index = readUntil(text, index, end, endsHigh);
  const high = text.slice(highStart, index);
  index = skipSpace(text, index, end);
  if (index !== end) return null;
  return { low, high, close };
}

/**
 * A driver's supported range, written as Maven does (`[3.0.0,)`), in words: "3.0 and later",
 * "2.30 to 2.60", "before 4.0". A string that is not a range is returned as it came.
 */
export function versionRangeWords(range: string): string {
  const bounds = boundsOf(range.trim());
  if (!bounds) return range;
  const from = bounds.low ? short(bounds.low) : null;
  const to = bounds.high ? short(bounds.high) : null;
  if (from && to) return bounds.close === ']' ? `${from} to ${to}` : `${from} up to, not including, ${to}`;
  if (from) return `${from} and later`;
  if (to) return bounds.close === ']' ? `${to} and earlier` : `before ${to}`;
  return range;
}
