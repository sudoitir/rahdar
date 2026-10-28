import { useState } from 'react';
import { Collapse, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { Link } from '@tanstack/react-router';
import linkClasses from '../../ui/InlineLink.module.css';
import type { CapabilitiesView, CapabilityView, VersionGateView } from './api.ts';
import styles from './CapabilityLedger.module.css';

type Key = Exclude<keyof CapabilitiesView, 'versionGates'>;

const LABELS: Record<Key, string> = {
  managementRead: 'Read management data',
  managementWrite: 'Change broker state',
  notifications: 'Live events',
  messageIo: 'Message browse and send',
  slowConsumerDetection: 'Slow-consumer detection',
};

/**
 * The capabilities whose snippet is — at least in part — an address or security
 * setting, which Rahdar can apply over the management API instead of an operator
 * editing broker.xml by hand (ADR-0068).
 *
 * The link goes to the recommendations panel rather than handing the raw snippet
 * to the XML importer: the panel seeds each entry from what the node is running,
 * which a pasted fragment cannot do, and a runtime write replaces the entry
 * rather than merging into it (notes §15 M2).
 */
const DECLARABLE: Partial<Record<Key, string>> = {
  notifications:
    'The security setting can be applied from the declared configuration; the plugin still needs broker.xml.',
  messageIo: 'This address setting can be applied from the declared configuration, no broker.xml edit needed.',
  slowConsumerDetection:
    'The address and security settings can be applied from the declared configuration; the plugin still needs broker.xml.',
};

const ORDER: Key[] = ['managementRead', 'managementWrite', 'notifications', 'messageIo', 'slowConsumerDetection'];

interface Row {
  key: string;
  label: string;
  cap: CapabilityView;
  word: { text: string; tone?: 'warning' | 'danger' };
  declarable?: string;
}

/**
 * "What this connection can do", as a hanging ledger. Every row shows a status
 * word on one right-aligned column; only rows that are not plainly available
 * expand, disclosing the reason and — where a `broker.xml` change would close
 * the gap — the exact snippet to paste.
 */
export function CapabilityLedger({
  capabilities,
  clusterId,
}: Readonly<{
  capabilities: CapabilitiesView;
  /** When the cluster is registered, snippets that are declarable link into its configuration. */
  clusterId?: string;
}>) {
  const [open, setOpen] = useState<string | null>(null);
  const rows: Row[] = [
    ...ORDER.map((key) => ({
      key,
      label: LABELS[key],
      cap: capabilities[key],
      word: statusWord(key, capabilities[key]),
      declarable: DECLARABLE[key],
    })),
    ...capabilities.versionGates.map((gate) => ({
      key: gate.feature,
      label: gate.label,
      cap: gate,
      word: gateWord(gate),
    })),
  ];

  return (
    <div className={styles.ledger}>
      {rows.map(({ key, label, cap, word, declarable }) => {
        const expandable = word.text !== 'Available';
        const isOpen = open === key;
        const chevron = isOpen ? ' ⌃' : ' ⌄';

        return (
          <div key={key}>
            <button
              type="button"
              className={styles.row}
              data-expandable={expandable || undefined}
              aria-expanded={expandable ? isOpen : undefined}
              disabled={!expandable}
              onClick={() => expandable && setOpen(isOpen ? null : key)}
            >
              <span className={styles.label}>{label}</span>
              <span className={styles.status} data-tone={word.tone}>
                {word.text}
                {expandable ? chevron : ''}
              </span>
            </button>

            {expandable ? (
              <Collapse expanded={isOpen}>
                <div className={styles.detail}>
                  {cap.reason}
                  {cap.brokerXmlSnippet ? (
                    <CodeHighlight className={styles.snippet} code={cap.brokerXmlSnippet.trimEnd()} language="xml" />
                  ) : null}
                  {cap.brokerXmlSnippet && clusterId && declarable ? (
                    <Text size="xs" mt="xs">
                      {declarable}{' '}
                      <Link
                        to={`/clusters/${clusterId}/artemis/configuration?tab=recommended`}
                        className={linkClasses.link}
                      >
                        Declare &amp; apply it
                      </Link>
                    </Text>
                  ) : null}
                </div>
              </Collapse>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

function statusWord(key: Key, cap: CapabilityView): { text: string; tone?: 'warning' | 'danger' } {
  if (cap.status === 'AVAILABLE') {
    if (key === 'messageIo' && /degraded|truncat|Core client/i.test(cap.reason)) {
      return { text: 'Limited', tone: 'warning' };
    }
    return { text: 'Available' };
  }
  if (cap.status === 'UNKNOWN') {
    return { text: 'Needs setup', tone: 'warning' };
  }
  return { text: 'Unavailable', tone: 'danger' };
}

/** A version gate in the ledger's words: the release it needs is the status, not a bare "Unavailable". */
function gateWord(gate: VersionGateView): Row['word'] {
  if (gate.status === 'UNAVAILABLE') {
    return { text: `Needs version ${gate.requiredVersion}`, tone: 'danger' };
  }
  if (gate.status === 'UNKNOWN') {
    return { text: 'Version unknown', tone: 'warning' };
  }
  return gate.nodes.every((n) => n.supported) ? { text: 'Available' } : { text: 'On some nodes', tone: 'warning' };
}
