import type { ApiError } from '../../kernel/api/request.ts';
import type { PatternConflict, PatternKind } from './api.ts';

/** A count with its noun, `1 pattern` or `3 patterns`. */
export function countOf(count: number, noun: string, plural = `${noun}s`): string {
  return `${count} ${count === 1 ? noun : plural}`;
}

export const KIND_WORDS: Record<PatternKind, string> = {
  SUBSCRIPTION: 'Subscriptions',
  DESTINATION: 'Destinations',
  ANY: 'Subscriptions and destinations',
};

export const KIND_OPTIONS = (Object.keys(KIND_WORDS) as PatternKind[]).map((value) => ({
  value,
  label: KIND_WORDS[value],
}));

/**
 * What is wrong with a name pattern's shape, or null when it is well formed: words separated by dots, each a
 * name, `*` (one word) or `#` (any number of words). The server checks it too; this answers while typing.
 */
export function patternFault(pattern: string): string | null {
  if (pattern.trim() === '') {
    return 'Enter a name pattern. Words are separated by dots; * stands for one word and # for any number of words.';
  }
  const words = pattern.split('.');
  if (words.includes('')) {
    return 'The pattern has an empty word. Remove the leading, trailing or doubled dot.';
  }
  const mixed = words.find((w) => w.length > 1 && /[*#]/.test(w));
  return mixed ? `"${mixed}" mixes a wildcard with other characters. Make * or # a whole word.` : null;
}

/** An overlap with another team, naming the team and the pattern that it collides with. */
export function conflictText(conflict: PatternConflict): string {
  return `Overlaps the ${KIND_WORDS[conflict.kind].toLowerCase()} pattern "${conflict.pattern}" of team ${conflict.teamName}. Two teams cannot own the same name on one cluster.`;
}

/** The problem's last path segment: `…/problems/duplicate-team-name` is `duplicate-team-name`. */
export const problemSlug = (error: ApiError): string => error.type.slice(error.type.lastIndexOf('/') + 1);

/** Why the server refused a change to a team, in a sentence that says what to do, for the problems it names. */
export function teamProblem(error: ApiError): string | null {
  switch (problemSlug(error)) {
    case 'duplicate-team-name':
      return 'A team with that name already exists. Choose another name.';
    case 'team-pattern-overlap':
      return error.message;
    case 'duplicate-team-pattern':
      return 'The team already owns that pattern.';
    case 'share-outside-owner':
      return 'The pattern is not inside what this team owns. Share a pattern that its own patterns contain.';
    case 'share-with-self':
      return 'A team cannot share with itself. Choose another team.';
    case 'duplicate-team-share':
      return 'That pattern is already shared with that team on that cluster.';
    case 'not-a-team-role':
      return 'That role is not a team role. Choose one marked as a team role in Roles.';
    case 'team-member-exists':
      return 'That user or group is already a member of this team.';
    default:
      return null;
  }
}
