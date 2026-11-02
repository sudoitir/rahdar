import { randomId } from '@mantine/hooks';

export interface ArgumentRow {
  id: string;
  k: string;
  v: string;
}

export const newArgument = (): ArgumentRow => ({ id: randomId(), k: '', v: '' });

/** An argument's value as the broker takes it: a number or a flag when it reads as one, else the text. */
function argumentValue(text: string): string | number | boolean {
  const t = text.trim();
  if (t === 'true' || t === 'false') return t === 'true';
  if (t !== '' && Number.isFinite(Number(t))) return Number(t);
  return text;
}

/** The arguments the rows name, by name; a row with no name is dropped. */
export function argumentsOf(rows: ArgumentRow[]): Record<string, string | number | boolean> {
  return Object.fromEntries(rows.filter((r) => r.k.trim()).map((r) => [r.k.trim(), argumentValue(r.v)]));
}
