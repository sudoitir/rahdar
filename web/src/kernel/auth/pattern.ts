/**
 * Whether `name` matches a subscription or destination pattern in the broker's wildcard syntax: words separated by `.`, `*`
 * for exactly one word and `#` for zero or more. The server decides (`ResourcePattern`); this only lets a form say
 * so while a name is typed.
 */
export function matchesPattern(pattern: string, name: string): boolean {
  const words = pattern.split('.');
  const parts = name.split('.');
  const reach = (w: number, p: number): boolean => {
    if (w === words.length) return p === parts.length;
    if (words[w] === '#')
      return Array.from({ length: parts.length - p + 1 }, (_, skip) => p + skip).some((q) => reach(w + 1, q));
    if (p === parts.length) return false;
    return (words[w] === '*' || words[w] === parts[p]) && reach(w + 1, p + 1);
  };
  return reach(0, 0);
}
