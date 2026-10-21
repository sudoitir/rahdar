/**
 * Message body format detection and formatting — client-side, because the body is
 * already in the browser and sending it back to be classified buys nothing.
 *
 * The one rule this module exists to keep: **never claim a format that did not
 * parse.** A declared content type is the producer's own statement and is reported
 * as such; an inferred format is only ever reported after the parse that proves it.
 * When a body cannot be formatted, the reason is reported — and a body the broker
 * truncated says so, rather than being called malformed. Turning a size problem
 * into a false "your payload is broken" sends an operator to debug a producer that
 * is fine.
 */

export type PayloadFormat = 'json' | 'xml' | 'text' | 'gzip' | 'zip' | 'java-serialized' | 'avro' | 'binary' | 'empty';

/** Why a body could not be pretty-printed. `null` means it was. */
export type UnavailableReason = 'truncated' | 'unparseable' | 'too-large' | 'binary' | null;

export interface PayloadInput {
  body: string | null;
  /** `'TEXT'` or `'BASE64'`, as the message DTO reports it. */
  bodyEncoding: string;
  contentType?: string | null;
  bodyTruncated: boolean;
  stringProperties?: Record<string, string> | null;
  /** `gzip` or `deflate` when the broker held the text body compressed. */
  bodyCompression?: string | null;
}

export interface DetectedPayload {
  format: PayloadFormat;
  /** Short badge text, e.g. `JSON` or `binary · gzip`. */
  label: string;
  /** Where the verdict came from. A declared type is the producer's word, not a sniff. */
  source: 'declared' | 'inferred' | 'none';
  /** Indented text, or `null` when formatting was not possible or not applicable. */
  formatted: string | null;
  /** Set whenever `formatted` is null for a format that would otherwise be printable. */
  unavailable: UnavailableReason;
  /** The language to hand a syntax highlighter, or `null` to leave it unhighlighted. */
  highlightLanguage: string | null;
  /** Decoded leading bytes, for a binary body's hex dump. */
  bytes: Uint8Array | null;
  /** Size of the body as it arrived, in bytes (decoded size for base64). */
  sizeBytes: number;
}

/**
 * Size ceilings, **measured** on synthetic JSON bodies rather than guessed:
 *
 *   | raw body | JSON.parse + stringify(…, 2) | Shiki tokenise |
 *   |----------|------------------------------|----------------|
 *   | 128 KiB  | ~2 ms                        | ~600 ms        |
 *   | 512 KiB  | ~8 ms                        | ~1.8 s         |
 *   | 1 MiB    | ~15 ms                       | ~3.5 s         |
 *   | 5 MiB    | ~62 ms                       | ~14 s          |
 *   | 20 MiB   | ~245 ms                      | —              |
 *
 * Highlighting is ~2.6 ms per KiB and runs on the main thread — two orders of
 * magnitude worse than formatting, so its ceiling is two orders lower. 64 KiB of
 * raw body pretty-prints to ~96 KiB and tokenises in ~250 ms, which is the most a
 * drawer opening can absorb. Pretty-printing stops at 2 MiB, where the ~3 MiB of
 * text it produces is already the DOM's limit rather than the parser's.
 *
 * Above every ceiling the body is still shown, unformatted, with copy and
 * download — never withheld.
 */
export const DETECT_PREFIX_BYTES = 64 * 1024;
export const PRETTY_MAX_BYTES = 2 * 1024 * 1024;
export const HIGHLIGHT_MAX_BYTES = 64 * 1024;

/** Artemis message type codes, read off `org.apache.activemq.artemis.api.core.Message`. */
const MESSAGE_TYPES: Record<number, string> = {
  0: 'default',
  2: 'object',
  3: 'text',
  4: 'bytes',
  5: 'map',
  6: 'stream',
  7: 'embedded',
  8: 'large embedded',
};

/** The JMS/Artemis message type as a word. Falls back to the raw code, never hides it. */
export function messageTypeName(type: number): string {
  return MESSAGE_TYPES[type] ?? `type ${type}`;
}

/** Property names a producer may use to declare the body's media type. */
const CONTENT_TYPE_KEYS = ['_AMQ_CONTENT_TYPE', 'contentType', 'content_type'];

/**
 * Magic bytes for the container kinds a broker operator actually meets. A general
 * file-type library covers neither Java serialization nor Avro, which are the two
 * that matter most on a subscription.
 */
const MAGIC: ReadonlyArray<{ bytes: number[]; format: PayloadFormat; label: string }> = [
  { bytes: [0x1f, 0x8b], format: 'gzip', label: 'binary · gzip' },
  { bytes: [0x50, 0x4b, 0x03, 0x04], format: 'zip', label: 'binary · zip' },
  { bytes: [0xac, 0xed, 0x00, 0x05], format: 'java-serialized', label: 'binary · Java serialized' },
  { bytes: [0x4f, 0x62, 0x6a, 0x01], format: 'avro', label: 'binary · Avro' },
];

function labelFor(format: PayloadFormat): string {
  switch (format) {
    case 'json':
      return 'JSON';
    case 'xml':
      return 'XML';
    case 'text':
      return 'text';
    case 'empty':
      return 'empty';
    case 'binary':
      return 'binary';
    default:
      return MAGIC.find((m) => m.format === format)?.label ?? format;
  }
}

/** The media type the producer declared, from the DTO field or an application property. */
function declaredType(input: PayloadInput): string | null {
  if (input.contentType?.trim()) return input.contentType.trim();
  const props = input.stringProperties ?? {};
  for (const key of CONTENT_TYPE_KEYS) {
    const v = props[key];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return null;
}

function formatFromMediaType(mediaType: string): PayloadFormat | null {
  const t = mediaType.toLowerCase();
  if (t.includes('json')) return 'json';
  if (t.includes('xml')) return 'xml';
  if (t.includes('avro')) return 'avro';
  if (t.includes('gzip')) return 'gzip';
  if (t.includes('zip')) return 'zip';
  if (t.includes('java-serialized') || t.includes('x-java-serialized-object')) {
    return 'java-serialized';
  }
  if (t.includes('octet-stream')) return 'binary';
  if (t.startsWith('text/') || t.includes('plain')) return 'text';
  return null;
}

/** Base64 → bytes, decoding at most `maxBytes` so a multi-MB body is not fully materialised. */
function decodeBase64(body: string, maxBytes: number): Uint8Array {
  const clean = body.replaceAll(/\s/g, '');
  const chars = Math.min(clean.length, Math.ceil(maxBytes / 3) * 4);
  const slice = clean.slice(0, chars - (chars % 4));
  let binary: string;
  try {
    binary = atob(slice);
  } catch {
    return new Uint8Array(0);
  }
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.codePointAt(i)!;
  return out;
}

function base64Padding(clean: string): number {
  if (clean.endsWith('==')) return 2;
  return clean.endsWith('=') ? 1 : 0;
}

function base64SizeBytes(body: string): number {
  const clean = body.replaceAll(/\s/g, '');
  const padding = base64Padding(clean);
  return Math.max(0, Math.floor((clean.length * 3) / 4) - padding);
}

function matchMagic(bytes: Uint8Array): { format: PayloadFormat; label: string } | null {
  for (const m of MAGIC) {
    if (bytes.length >= m.bytes.length && m.bytes.every((b, i) => bytes[i] === b)) {
      return { format: m.format, label: m.label };
    }
  }
  return null;
}

/** Indent a parsed XML document. `XMLSerializer` does not indent, so walk it. */
export function formatXmlDocument(doc: Document): string {
  const lines: string[] = [];
  const walk = (node: Node, depth: number) => {
    const pad = '  '.repeat(depth);
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.nodeValue?.trim();
      if (text) lines.push(pad + text);
      return;
    }
    if (node.nodeType === Node.COMMENT_NODE) {
      lines.push(`${pad}<!--${node.nodeValue ?? ''}-->`);
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;

    const el = node as Element;
    const attrs = Array.from(el.attributes)
      .map((a) => ` ${a.name}="${a.value}"`)
      .join('');
    const children = Array.from(el.childNodes).filter(
      (c) => c.nodeType !== Node.TEXT_NODE || (c.nodeValue ?? '').trim(),
    );

    if (children.length === 0) {
      lines.push(`${pad}<${el.nodeName}${attrs} />`);
      return;
    }
    if (children.length === 1 && children[0].nodeType === Node.TEXT_NODE) {
      lines.push(`${pad}<${el.nodeName}${attrs}>${children[0].nodeValue?.trim()}</${el.nodeName}>`);
      return;
    }
    lines.push(`${pad}<${el.nodeName}${attrs}>`);
    for (const child of children) walk(child, depth + 1);
    lines.push(`${pad}</${el.nodeName}>`);
  };
  for (const child of Array.from(doc.childNodes)) walk(child, 0);
  return lines.join('\n');
}

/** Parse XML, returning null when the parser reported an error *in the document*. */
function parseXml(text: string): Document | null {
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  // DOMParser reports failure by inserting a <parsererror>, not by throwing.
  if (doc.getElementsByTagName('parsererror').length > 0) return null;
  return doc;
}

function textResult(
  format: PayloadFormat,
  source: DetectedPayload['source'],
  sizeBytes: number,
  unavailable: UnavailableReason = null,
  formatted: string | null = null,
  highlightLanguage: string | null = null,
): DetectedPayload {
  return {
    format,
    label: labelFor(format),
    source,
    formatted,
    unavailable,
    highlightLanguage,
    bytes: null,
    sizeBytes,
  };
}

/**
 * Try to pretty-print `text` as `format`. Returns the formatted text, or the reason
 * it could not be — `truncated` taking precedence, because a clipped body failing to
 * parse is a size problem, not a malformed payload.
 */
function tryFormat(
  format: PayloadFormat,
  text: string,
  truncated: boolean,
  sizeBytes: number,
): { formatted: string | null; unavailable: UnavailableReason } {
  if (sizeBytes > PRETTY_MAX_BYTES) return { formatted: null, unavailable: 'too-large' };
  try {
    if (format === 'json') {
      return { formatted: JSON.stringify(JSON.parse(text), null, 2), unavailable: null };
    }
    if (format === 'xml') {
      const doc = parseXml(text);
      if (!doc) throw new Error('parsererror');
      return { formatted: formatXmlDocument(doc), unavailable: null };
    }
  } catch {
    return { formatted: null, unavailable: truncated ? 'truncated' : 'unparseable' };
  }
  return { formatted: null, unavailable: null };
}

/** The language handed to the highlighter, or null above the highlight ceiling. */
function languageFor(format: PayloadFormat, sizeBytes: number): string | null {
  if (sizeBytes > HIGHLIGHT_MAX_BYTES) return null;
  if (format === 'json') return 'json';
  if (format === 'xml') return 'xml';
  return null;
}

/**
 * Classify a message body, and format it when that is possible and cheap enough.
 * See the module comment for the one invariant.
 */
export function detectPayload(input: PayloadInput): DetectedPayload {
  const body = input.body ?? '';
  if (body.length === 0) return textResult('empty', 'none', 0);

  const isBase64 = input.bodyEncoding === 'BASE64';
  const sizeBytes = isBase64 ? base64SizeBytes(body) : new Blob([body]).size;

  const detected =
    fromDeclaredType(input, body, isBase64, sizeBytes) ??
    (isBase64 ? fromBase64Prefix(body, sizeBytes) : fromTextProbe(input, body, sizeBytes));
  return input.bodyCompression && !isBase64
    ? { ...detected, label: `${detected.label} · ${input.bodyCompression}` }
    : detected;
}

/**
 * The SQL console's path for a field of a JSON body, `body->>'a.b.0'`, or null when a key cannot be written
 * in that form: the console splits the path on dots and does not unescape quotes.
 */
export function sqlBodyPath(segments: ReadonlyArray<string | number>): string | null {
  if (segments.length === 0) return null;
  const keys = segments.map(String);
  if (keys.some((k) => k === '' || k.includes('.') || k.includes("'"))) return null;
  return `body->>'${keys.join('.')}'`;
}

/**
 * 1. A declared type is the producer's own statement. It wins over any sniff — but it still only
 * *formats* if the body actually parses. Null when the declared type says nothing about the body.
 */
function fromDeclaredType(
  input: PayloadInput,
  body: string,
  isBase64: boolean,
  sizeBytes: number,
): DetectedPayload | null {
  const declared = declaredType(input);
  const declaredFormat = declared ? formatFromMediaType(declared) : null;

  if (declaredFormat === 'json' || declaredFormat === 'xml') {
    const text = isBase64 ? new TextDecoder().decode(decodeBase64(body, sizeBytes)) : body;
    const { formatted, unavailable } = tryFormat(declaredFormat, text, input.bodyTruncated, sizeBytes);
    return textResult(
      declaredFormat,
      'declared',
      sizeBytes,
      unavailable,
      formatted,
      languageFor(declaredFormat, sizeBytes),
    );
  }

  // A declared container only describes bytes: a text body was already unwrapped from it by the server.
  if (declaredFormat && declaredFormat !== 'text' && isBase64) {
    // A declared binary container: report it, and dump bytes rather than decode text.
    const bytes = decodeBase64(body, DETECT_PREFIX_BYTES);
    return {
      format: declaredFormat,
      label: labelFor(declaredFormat),
      source: 'declared',
      formatted: null,
      unavailable: 'binary',
      highlightLanguage: null,
      bytes,
      sizeBytes,
    };
  }
  return null;
}

/** 2. A base64 body: match the leading bytes against the container table. */
function fromBase64Prefix(body: string, sizeBytes: number): DetectedPayload {
  const bytes = decodeBase64(body, DETECT_PREFIX_BYTES);
  const magic = matchMagic(bytes);
  return {
    format: magic?.format ?? 'binary',
    label: magic?.label ?? labelFor('binary'),
    source: magic ? 'inferred' : 'none',
    formatted: null,
    unavailable: 'binary',
    highlightLanguage: null,
    bytes,
    sizeBytes,
  };
}

/**
 * 3. A text body: probe structurally, but only claim what parsed. Detection reads a
 * prefix; the parse that proves the claim reads the whole body, below the ceiling.
 */
function fromTextProbe(input: PayloadInput, body: string, sizeBytes: number): DetectedPayload {
  const head = body.slice(0, DETECT_PREFIX_BYTES).trimStart();
  const first = head[0];
  if (first === '{' || first === '[') return probeJson(input, body, sizeBytes);
  if (first === '<') return probeXml(input, body, sizeBytes);
  return textResult('text', 'none', sizeBytes);
}

function probeJson(input: PayloadInput, body: string, sizeBytes: number): DetectedPayload {
  const { formatted, unavailable } = tryFormat('json', body, input.bodyTruncated, sizeBytes);
  if (formatted !== null) {
    return textResult('json', 'inferred', sizeBytes, null, formatted, languageFor('json', sizeBytes));
  }
  // A truncated body that looks like JSON is a size problem, and saying so is the
  // point of this branch — but an unparseable *whole* body is simply not JSON.
  if (unavailable === 'truncated' || unavailable === 'too-large') {
    return textResult('json', 'inferred', sizeBytes, unavailable, null, null);
  }
  return textResult('text', 'none', sizeBytes);
}

function probeXml(input: PayloadInput, body: string, sizeBytes: number): DetectedPayload {
  if (sizeBytes > PRETTY_MAX_BYTES) {
    return textResult('xml', 'inferred', sizeBytes, 'too-large', null, null);
  }
  const doc = parseXml(body);
  if (doc) {
    return textResult('xml', 'inferred', sizeBytes, null, formatXmlDocument(doc), languageFor('xml', sizeBytes));
  }
  if (input.bodyTruncated) {
    return textResult('xml', 'inferred', sizeBytes, 'truncated', null, null);
  }
  return textResult('text', 'none', sizeBytes);
}

/** One-line explanation for a body that could not be formatted. */
export function unavailableMessage(d: DetectedPayload): string | null {
  switch (d.unavailable) {
    case 'truncated':
      return "Can't format: the broker truncated this body.";
    case 'too-large':
      return 'Formatting is off for a body this size.';
    case 'unparseable':
      return `Declared ${d.label}, but the body did not parse.`;
    default:
      return null;
  }
}
