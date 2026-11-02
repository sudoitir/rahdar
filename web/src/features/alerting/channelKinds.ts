/**
 * What each notification channel kind needs, in the words the editor shows (ADR-0105).
 * The secret is always write-only: the API never returns it, so an edit leaves it blank
 * and a blank secret keeps the stored one.
 */
export type ChannelKind = 'SLACK' | 'TEAMS' | 'PAGERDUTY' | 'EMAIL' | 'WEBHOOK';

export interface KindInfo {
  label: string;
  description: string;
  secretLabel: string;
  secretDescription: string;
  secretPlaceholder?: string;
  /** Whether a channel of this kind can work without a secret. */
  secretOptional: boolean;
}

export const CHANNEL_KINDS: Record<ChannelKind, KindInfo> = {
  SLACK: {
    label: 'Slack',
    description: 'A Slack incoming webhook. Posts a formatted message with a link back to Rahdar.',
    secretLabel: 'Webhook URL',
    secretDescription: 'From Slack: Apps → Incoming Webhooks → Add New Webhook to Workspace.',
    secretPlaceholder: 'https://hooks.slack.com/services/…',
    secretOptional: false,
  },
  TEAMS: {
    label: 'Microsoft Teams',
    description: 'A Teams Workflows webhook ("When a Teams webhook request is received"). Posts an Adaptive Card.',
    secretLabel: 'Workflow webhook URL',
    secretDescription: 'Copy it from the workflow’s trigger. Legacy connector webhooks accept the same card.',
    secretPlaceholder: 'https://…logic.azure.com/workflows/…',
    secretOptional: false,
  },
  PAGERDUTY: {
    label: 'PagerDuty',
    description:
      'PagerDuty Events API v2, or any compatible receiver. Each firing subject opens an incident that resolves with the alert.',
    secretLabel: 'Routing key',
    secretDescription: 'The integration key of an Events API v2 integration on the service — 32 characters.',
    secretOptional: false,
  },
  EMAIL: {
    label: 'Email (SMTP)',
    description: 'One email per notification, as HTML with a plain-text alternative.',
    secretLabel: 'SMTP password',
    secretDescription: 'Only when the server needs authentication. Leave blank for an open relay.',
    secretOptional: true,
  },
  WEBHOOK: {
    label: 'Signed webhook',
    description:
      'A JSON POST signed per Standard Webhooks (webhook-id, webhook-timestamp, webhook-signature), for your own receiver.',
    secretLabel: 'Signing secret',
    secretDescription:
      'Base64, optionally prefixed whsec_, at least 16 bytes. The receiver verifies with the same secret.',
    secretPlaceholder: 'whsec_…',
    secretOptional: false,
  },
};

export const KIND_ORDER: ChannelKind[] = ['SLACK', 'TEAMS', 'PAGERDUTY', 'EMAIL', 'WEBHOOK'];

export function kindLabel(kind: string): string {
  return CHANNEL_KINDS[kind as ChannelKind]?.label ?? kind;
}

export const PAGERDUTY_ENDPOINTS = [
  { value: 'https://events.pagerduty.com/v2/enqueue', label: 'PagerDuty (US service region)' },
  { value: 'https://events.eu.pagerduty.com/v2/enqueue', label: 'PagerDuty (EU service region)' },
  { value: 'custom', label: 'Another PagerDuty-compatible receiver' },
] as const;

/** The non-secret half of a channel, as the form edits it. */
export interface ChannelFields {
  url: string;
  pagerDutyEndpoint: string;
  host: string;
  port: string;
  security: 'STARTTLS' | 'TLS' | 'NONE';
  username: string;
  from: string;
  to: string;
  subjectPrefix: string;
}

export const EMPTY_FIELDS: ChannelFields = {
  url: '',
  pagerDutyEndpoint: PAGERDUTY_ENDPOINTS[0].value,
  host: '',
  port: '587',
  security: 'STARTTLS',
  username: '',
  from: '',
  to: '',
  subjectPrefix: '[Rahdar]',
};

function parse(config: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(config || '{}');
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/** A config value as text: strings as they are, numbers and booleans stringified, anything else blank. */
const str = (v: unknown): string => {
  if (typeof v === 'string') return v;
  return typeof v === 'number' || typeof v === 'boolean' ? String(v) : '';
};

export function fieldsFromConfig(kind: string, config: string): ChannelFields {
  const c = parse(config);
  const fields = { ...EMPTY_FIELDS };
  if (kind === 'WEBHOOK') fields.url = str(c.url);
  if (kind === 'PAGERDUTY') Object.assign(fields, pagerDutyFields(c));
  if (kind === 'EMAIL') Object.assign(fields, emailFields(c));
  return fields;
}

function pagerDutyFields(c: Record<string, unknown>): Pick<ChannelFields, 'pagerDutyEndpoint' | 'url'> {
  const url = str(c.url);
  if (url === '') return { pagerDutyEndpoint: PAGERDUTY_ENDPOINTS[0].value, url: '' };
  const known = PAGERDUTY_ENDPOINTS.find((e) => e.value === url);
  return known ? { pagerDutyEndpoint: known.value, url: '' } : { pagerDutyEndpoint: 'custom', url };
}

function emailFields(c: Record<string, unknown>): Omit<ChannelFields, 'url' | 'pagerDutyEndpoint'> {
  return {
    host: str(c.host),
    port: c.port === undefined ? '587' : str(c.port),
    security: (['STARTTLS', 'TLS', 'NONE'].includes(str(c.security))
      ? str(c.security)
      : 'STARTTLS') as ChannelFields['security'],
    username: str(c.username),
    from: str(c.from),
    to: Array.isArray(c.to) ? c.to.map(str).join(', ') : str(c.to),
    subjectPrefix: str(c.subjectPrefix),
  };
}

export function recipients(to: string): string[] {
  return to
    .split(/[,;\s]+/)
    .map((a) => a.trim())
    .filter(Boolean);
}

export function configFromFields(kind: string, f: ChannelFields): string {
  switch (kind) {
    case 'WEBHOOK':
      return JSON.stringify({ url: f.url.trim() });
    case 'PAGERDUTY':
      return JSON.stringify({ url: f.pagerDutyEndpoint === 'custom' ? f.url.trim() : f.pagerDutyEndpoint });
    case 'EMAIL':
      return JSON.stringify({
        host: f.host.trim(),
        port: Number(f.port),
        security: f.security,
        username: f.username.trim() || null,
        from: f.from.trim(),
        to: recipients(f.to),
        subjectPrefix: f.subjectPrefix.trim() || null,
      });
    default:
      return '{}';
  }
}

/** Where a channel delivers, without its secret — what the list shows beside the name. */
export function destination(kind: string, config: string): string {
  const f = fieldsFromConfig(kind, config);
  switch (kind) {
    case 'WEBHOOK':
      return hostOf(f.url) ?? 'no URL';
    case 'PAGERDUTY':
      return f.pagerDutyEndpoint === 'custom'
        ? (hostOf(f.url) ?? 'custom receiver')
        : (PAGERDUTY_ENDPOINTS.find((e) => e.value === f.pagerDutyEndpoint)?.label ?? 'PagerDuty');
    case 'EMAIL': {
      const to = recipients(f.to);
      if (to.length === 0) return 'no recipients';
      const more = to.length > 1 ? ` +${to.length - 1}` : '';
      return `${to[0]}${more} via ${f.host || '?'}`;
    }
    default:
      return 'webhook URL stored as a secret';
  }
}

function hostOf(url: string): string | null {
  try {
    return new URL(url).host;
  } catch {
    return null;
  }
}

const EMAIL = /^[^\s@<>()]+@[^\s@<>()][^\s@<>().]*\.[^\s@<>()]+$/;

/**
 * Per-field validation on blur — the same rules the server applies, so a message
 * appears beside its field before the round trip. The server is still the authority.
 */
export function validateField(
  kind: string,
  field: keyof ChannelFields | 'name' | 'secret',
  value: string,
  ctx: { editing: boolean; hasSecret: boolean; fields: ChannelFields },
): string | null {
  const v = value.trim();
  switch (field) {
    case 'name':
      return v ? null : 'A name is required.';
    case 'url':
      return validateUrl(kind, v, ctx.fields);
    case 'secret':
      return validateSecret(kind, v, ctx);
    case 'host':
      return kind === 'EMAIL' && !v ? 'The SMTP server is required.' : null;
    case 'port':
      return kind === 'EMAIL' ? validatePort(v) : null;
    case 'from':
      return kind === 'EMAIL' && !EMAIL.test(v) ? 'A valid sender address.' : null;
    case 'to':
      return kind === 'EMAIL' ? validateRecipients(v) : null;
    default:
      return null;
  }
}

function validateUrl(kind: string, v: string, fields: ChannelFields): string | null {
  if (kind === 'PAGERDUTY' && fields.pagerDutyEndpoint !== 'custom') return null;
  if (kind !== 'WEBHOOK' && kind !== 'PAGERDUTY') return null;
  return isHttpUrl(v) ? null : 'An http or https URL with a host.';
}

function validateSecret(
  kind: string,
  v: string,
  ctx: { editing: boolean; hasSecret: boolean; fields: ChannelFields },
): string | null {
  const info = CHANNEL_KINDS[kind as ChannelKind];
  if (!v) {
    if (info?.secretOptional || (ctx.editing && ctx.hasSecret)) return null;
    return `${info?.secretLabel ?? 'The secret'} is required.`;
  }
  if (kind === 'SLACK' || kind === 'TEAMS') return isHttpUrl(v) ? null : 'An http or https URL.';
  if (kind === 'PAGERDUTY' && ctx.fields.pagerDutyEndpoint !== 'custom' && v.length !== 32) {
    return `A routing key is 32 characters; this is ${v.length}.`;
  }
  return kind === 'WEBHOOK' ? validateSigningSecret(v) : null;
}

function validateSigningSecret(v: string): string | null {
  const b64 = v.startsWith('whsec_') ? v.slice(6) : v;
  try {
    return atob(b64).length < 16 ? 'Must decode to at least 16 bytes.' : null;
  } catch {
    return 'Must be base64, optionally prefixed whsec_.';
  }
}

function validatePort(v: string): string | null {
  const n = Number(v);
  return Number.isInteger(n) && n >= 1 && n <= 65535 ? null : 'A port between 1 and 65535.';
}

function validateRecipients(v: string): string | null {
  const list = recipients(v);
  if (list.length === 0) return 'At least one recipient.';
  const bad = list.find((a) => !EMAIL.test(a));
  return bad ? `"${bad}" is not a valid address.` : null;
}

function isHttpUrl(v: string): boolean {
  try {
    const u = new URL(v);
    return (u.protocol === 'https:' || u.protocol === 'http:') && u.host !== '';
  } catch {
    return false;
  }
}

/** A random 32-byte Standard Webhooks secret, so an operator never invents a weak one. */
export function generateSigningSecret(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return `whsec_${btoa(String.fromCodePoint(...bytes))}`;
}

/** The field a server rejection names — `to: …` — so the message lands beside it. */
export function serverField(message: string): string | null {
  const m = /^([A-Za-z]+):\s/.exec(message);
  return m ? m[1] : null;
}

const STATE_WORDS: Record<string, string> = {
  PENDING: 'waiting',
  SENT: 'sent',
  DEAD: 'failed',
  FAILED: 'failed',
};

/** Delivery state in words; colour only where something is wrong. */
export function deliveryState(state: string): string {
  return STATE_WORDS[state] ?? state.toLowerCase();
}
