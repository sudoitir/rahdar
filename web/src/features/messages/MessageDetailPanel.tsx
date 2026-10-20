import { useMemo, useState } from 'react';
import { Button, CopyButton, Drawer, Group, SegmentedControl, Stack, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { useMessageDetail, type MessageDetailView } from './api.ts';
import { HexDump } from './HexDump.tsx';
import { JsonTree } from './JsonTree.tsx';
import { detectPayload, messageTypeName, unavailableMessage } from './payload.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { GovernedValue, RedactionMarks, WithheldNotice } from '../../ui/RedactedValue.tsx';
import { redactionsAt } from '../../ui/redactions.ts';
import { download } from '../../ui/download.ts';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';

type Redactions = MessageDetailView['redactions'];

const FILE_EXTENSION: Record<string, string> = { json: 'json', xml: 'xml' };

/**
 * Raises the per-message body/property cap. Mirrors
 * {@code BrokerXmlSnippets.forMessageBodyLimit()} on the backend — shown next to
 * a truncated body so the operator has the exact change to make (non-negotiable
 * #5). This is a per-message disclosure, not a capability gate.
 */
const RAISE_LIMIT_SNIPPET = `<address-settings>
  <address-setting match="#">
    <management-message-attribute-size-limit>-1</management-message-attribute-size-limit>
  </address-setting>
</address-settings>`;

/** One typed group of the message's properties, as terms and values. */
function PropertySection({
  title,
  entries,
  redactions,
}: Readonly<{
  title: string;
  entries: [string, unknown][];
  redactions: Redactions;
}>) {
  if (entries.length === 0) return null;
  const items: DescriptionItem[] = entries.map(([k, v]) => ({
    term: k,
    value: <GovernedValue value={v} redactions={redactionsAt(redactions, 'PROPERTY', k)} />,
  }));
  return (
    <Section headingLevel={3} title={title}>
      <DescriptionList items={items} label={title} />
    </Section>
  );
}

/**
 * The body, with its format named and — when the format actually parsed — indented
 * and highlighted. Everything here is client-side: the body is already in the
 * browser, so nothing is sent back to be classified.
 */
function MessageBody({
  body,
  bodyEncoding,
  bodyCompression,
  contentType,
  bodyTruncated,
  stringProperties,
  messageId,
  redactions,
  withheld,
}: Readonly<{
  body: string | null;
  bodyEncoding: string;
  bodyCompression?: string | null;
  contentType?: string | null;
  bodyTruncated: boolean;
  stringProperties: Record<string, string>;
  messageId: string;
  redactions: Redactions;
  withheld: MessageDetailView['withheld'];
}>) {
  const bodyRedactions = redactionsAt(redactions, 'BODY');
  const masked = bodyRedactions.some((r) => !r.clear);
  const [view, setView] = useState<BodyView>('formatted');
  const detected = useMemo(
    () => detectPayload({ body, bodyEncoding, bodyCompression, contentType, bodyTruncated, stringProperties }),
    [body, bodyEncoding, bodyCompression, contentType, bodyTruncated, stringProperties],
  );
  const tree = useMemo(() => parsedJson(detected), [detected]);

  const raw = body ?? '';
  const shown = view !== 'raw' && detected.formatted !== null ? detected.formatted : raw;
  const note = unavailableMessage(detected);

  const downloadBody = () => downloadMessageBody(messageId, bodyEncoding, raw, detected.format);

  return (
    <Section
      headingLevel={3}
      title="Body"
      description={
        <Group gap="xs">
          <StatusBadge>{detected.label}</StatusBadge>
          {detected.source === 'declared' ? <span>declared by the producer</span> : null}
        </Group>
      }
      actions={
        <>
          {detected.formatted !== null ? (
            <SegmentedControl
              size="xs"
              aria-label="Body view"
              value={view}
              onChange={(v) => setView(v as BodyView)}
              data={viewOptions(tree !== null)}
            />
          ) : null}
          <CopyButton value={raw}>
            {({ copied, copy }) => (
              <Button size="compact-xs" variant="default" onClick={copy}>
                {copied ? 'Copied' : 'Copy'}
              </Button>
            )}
          </CopyButton>
          <Button size="compact-xs" variant="default" onClick={downloadBody}>
            Download
          </Button>
        </>
      }
    >
      <RedactionMarks redactions={bodyRedactions} />
      {masked ? (
        <Text size="xs" c="dimmed">
          Copy and Download carry the body as shown here, with sensitive values masked.
        </Text>
      ) : null}
      <WithheldNotice withheld={withheld} />

      {body === null && withheld.length > 0 ? null : (
        <BodyContent
          tree={view === 'tree' ? tree : null}
          bytes={detected.bytes}
          code={shown || '(empty)'}
          language={(view === 'formatted' && detected.highlightLanguage) || 'text'}
        />
      )}

      {note ? (
        <Text size="xs" c="dimmed">
          {note}
          {detected.unavailable === 'truncated' ? ' See the truncation notice below.' : ''}
        </Text>
      ) : null}
    </Section>
  );
}

type BodyView = 'formatted' | 'tree' | 'raw';

/** Formatted and Raw always; Tree only for a body that parsed as JSON. */
function viewOptions(withTree: boolean): { label: string; value: BodyView }[] {
  return [
    { label: 'Formatted', value: 'formatted' },
    ...(withTree ? [{ label: 'Tree', value: 'tree' as const }] : []),
    { label: 'Raw', value: 'raw' },
  ];
}

/** The body as a value for the tree. Offered only for JSON that parsed, so it inherits every formatting ceiling. */
function parsedJson(detected: ReturnType<typeof detectPayload>): unknown {
  return detected.format === 'json' && detected.formatted !== null ? JSON.parse(detected.formatted) : null;
}

/** A binary body is handed over as its bytes, not as the base64 that carried it. */
function downloadMessageBody(messageId: string, bodyEncoding: string, raw: string, format: string): void {
  if (bodyEncoding === 'BASE64') {
    download(`message-${messageId}.bin`, new Blob([base64Bytes(raw)]));
  } else {
    download(`message-${messageId}.${FILE_EXTENSION[format] ?? 'txt'}`, raw);
  }
}

function base64Bytes(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64.replaceAll(/\s/g, ''));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.codePointAt(i)!;
  return out;
}

/** The body as bytes when it is binary, as a tree when asked for, otherwise as highlighted text. */
function BodyContent({
  tree,
  bytes,
  code,
  language,
}: Readonly<{ tree: unknown; bytes: Uint8Array | null; code: string; language: string }>) {
  if (bytes) return <HexDump bytes={bytes} />;
  if (tree !== null) return <JsonTree value={tree} />;
  return <CodeHighlight code={code} language={language} />;
}

/** What the message is and when it was enqueued, plus the identifying headers it carries. */
function Headers({ m }: Readonly<{ m: MessageDetailView }>) {
  const header = (term: string, value: string | null | undefined, name: string): DescriptionItem[] =>
    value
      ? [{ term, value: <GovernedValue value={value} redactions={redactionsAt(m.redactions, 'HEADER', name)} /> }]
      : [];
  const items: DescriptionItem[] = [
    { term: 'Type', value: messageTypeName(m.type) },
    { term: 'Durability', value: m.durable ? 'durable' : 'non-durable' },
    { term: 'Priority', value: m.priority },
    { term: 'Size', value: `${m.size} bytes` },
    { term: 'Enqueued', value: absoluteLabel(m.timestamp) },
    { term: 'Expiration', value: m.expiration > 0 ? absoluteLabel(m.expiration) : 'never' },
    ...header('Group', m.groupId, 'groupId'),
    ...header('Correlation ID', m.correlationId, 'correlationId'),
    ...header('User ID', m.userId, 'userId'),
  ];
  return (
    <Section headingLevel={3} title="Headers">
      <DescriptionList items={items} columns={2} label="Message headers" />
    </Section>
  );
}

/** What the broker clipped, and the exact change that lifts the limit. */
function TruncationNotice({ m }: Readonly<{ m: MessageDetailView }>) {
  if (!m.bodyTruncated) return null;
  return (
    <Notice tone="warning" title="This message is truncated">
      <Stack gap="xs">
        <Text size="sm">
          The broker clipped this message's body and property values at {m.observedLimitBytes ?? 'its'} bytes (
          <code>management-message-attribute-size-limit</code>). To see the whole message, raise the limit in{' '}
          <code>broker.xml</code> and re-browse, or connect the Core client so Rahdar can read it faithfully:
        </Text>
        <CodeHighlight code={RAISE_LIMIT_SNIPPET} language="xml" />
      </Stack>
    </Notice>
  );
}

/** The message itself: its headers, every property table, the body, and the truncation notice. */
function MessageDetailContent({ m }: Readonly<{ m: MessageDetailView }>) {
  return (
    <Stack gap="lg">
      <Headers m={m} />

      <PropertySection
        title="String properties"
        entries={Object.entries(m.stringProperties)}
        redactions={m.redactions}
      />
      <PropertySection title="Integer properties" entries={Object.entries(m.intProperties)} redactions={m.redactions} />
      <PropertySection title="Long properties" entries={Object.entries(m.longProperties)} redactions={m.redactions} />
      <PropertySection
        title="Double properties"
        entries={Object.entries(m.doubleProperties)}
        redactions={m.redactions}
      />
      <PropertySection
        title="Boolean properties"
        entries={Object.entries(m.booleanProperties)}
        redactions={m.redactions}
      />

      <MessageBody
        body={m.body ?? null}
        bodyEncoding={m.bodyEncoding}
        bodyCompression={m.bodyCompression}
        contentType={m.contentType}
        bodyTruncated={m.bodyTruncated}
        stringProperties={m.stringProperties}
        messageId={m.position}
        redactions={m.redactions}
        withheld={m.withheld}
      />

      <TruncationNotice m={m} />
    </Stack>
  );
}

export function MessageDetailPanel({
  clusterId,
  queueName,
  messageId,
  node,
  filter,
  onClose,
}: Readonly<{
  clusterId: string;
  queueName: string;
  messageId: string | null;
  node?: string;
  filter?: string;
  onClose: () => void;
}>) {
  const detail = useMessageDetail(clusterId, queueName, messageId, node, filter);
  const m = detail.data;
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();

  return (
    <Drawer
      opened={messageId !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={messageId ? `Message ${messageId}` : ''}
    >
      {detail.isPending ? <LoadingState label="Loading the message" blockSize="20rem" /> : null}
      {detail.isError ? <ErrorState error={detail.error} onRetry={() => void detail.refetch()} /> : null}
      {m ? <MessageDetailContent m={m} /> : null}
    </Drawer>
  );
}
