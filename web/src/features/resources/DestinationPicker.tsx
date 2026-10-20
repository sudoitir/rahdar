import type React from 'react';
import { useMemo, useRef, useState } from 'react';
import { Chip, Combobox, Group, Text, TextInput, useCombobox } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';

import styles from './DestinationPicker.module.css';

import { destinationOf, routingTypeOf, useDestinationOptions, useSubscriptions, type SubscriptionView } from './api.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';

const SUGGESTION_LIMIT = 300;
const ROUTING_TYPES = ['ANYCAST', 'MULTICAST'] as const;
type RoutingType = (typeof ROUTING_TYPES)[number];

export interface DestinationPickerProps {
  clusterId: string;
  value: string;
  onChange: (value: string) => void;
  label?: string;
  description?: string;
  placeholder?: string;
  /**
   * Offer only the destinations the caller holds this permission on (such as `message:send`), and say so when a typed
   * name is an destination they do not hold it on. A name that is not on the broker yet is left to the server.
   */
  permission?: string;
  /** Shown under the field when what was typed matches no destination on the broker. */
  unknownHint?: string;
  w?: number | string;
  disabled?: boolean;
  /** So a form can move focus here when this is the first invalid field. */
  inputRef?: React.Ref<HTMLInputElement>;
  error?: React.ReactNode;
  onBlur?: () => void;
  onFocus?: () => void;
  /** Set by `form.getInputProps`, so `form.getInputNode` finds the input. */
  'data-path'?: string;
}

/** What stands in for the options while subscriptions fail to load or load, or when nothing matches. */
function pickerNotice(
  subscriptions: ReturnType<typeof useSubscriptions>,
  optionCount: number,
  typeCount: number,
): React.ReactNode {
  if (subscriptions.isError) {
    return (
      <Combobox.Empty>
        Could not read this cluster&rsquo;s subscriptions: {subscriptions.error.message} You can still type an
        destination by hand.
      </Combobox.Empty>
    );
  }
  if (subscriptions.isPending) return <Combobox.Empty>Loading destinations…</Combobox.Empty>;
  if (optionCount > 0) return null;
  return (
    <Combobox.Empty>
      {typeCount > 0
        ? 'No destination here matches that name and routing type. Clear the type filter, or type the name in full to use it anyway.'
        : 'No destination on this cluster matches that name. Type it in full to use it anyway — Rahdar will start tracing once it appears.'}
    </Combobox.Empty>
  );
}

/**
 * Pick an destination by name, with the broker's own list to choose from.
 *
 * Free text is deliberately allowed: an operator may be naming an destination that
 * does not exist yet, or one that only appears under load. So this suggests, it
 * never constrains — but it says so when what you typed matches nothing, because
 * a silent typo is the failure this component exists to prevent.
 *
 * Suggestions come from subscriptions rather than destinations because a subscription carries the
 * routing type and the depth, which is what tells a request subscription from a reply
 * subscription at a glance. Search runs on the server (`q`), so the list stays bounded
 * on a broker with thousands of subscriptions.
 */
export function DestinationPicker({
  clusterId,
  value,
  onChange,
  label,
  description,
  placeholder,
  permission,
  unknownHint,
  w,
  disabled,
  inputRef,
  error,
  onBlur,
  onFocus,
  'data-path': dataPath,
}: Readonly<DestinationPickerProps>) {
  const combobox = useCombobox({ onDropdownClose: () => combobox.resetSelectedOption() });
  const [types, setTypes] = useState<RoutingType[]>([]);
  const [debounced] = useDebouncedValue(value, 200);
  // The dropdown holds its own focusable controls, so closing on every blur
  // would put the routing-type filter out of reach of the keyboard entirely.
  const root = useRef<HTMLDivElement>(null);

  const subscriptions = useSubscriptions(clusterId, { q: debounced || undefined, size: SUGGESTION_LIMIT });

  const destinations = useDestinationOptions(
    clusterId,
    { q: debounced || undefined, size: SUGGESTION_LIMIT },
    Boolean(permission),
  );
  // Until the destinations arrive nothing is withheld: an answer not yet known is no refusal.
  const barred = useMemo(() => {
    if (!permission || !destinations.data) return new Set<string>();
    return new Set(destinations.data.data.filter((a) => !a.allowedActions.includes(permission)).map((a) => a.name));
  }, [destinations.data, permission]);

  const options = useMemo(() => {
    const rows = (subscriptions.data?.data ?? []).filter((q) => !barred.has(destinationOf(q)));
    const wanted = types.length === 0 ? null : new Set<string>(types);
    // One row per destination: the same destination on several nodes is one thing to
    // whoever is picking it, so keep the busiest and count the rest as coverage.
    const byDestination = new Map<string, SubscriptionView>();
    for (const row of rows) {
      if (wanted && !wanted.has(routingTypeOf(row))) continue;
      const seen = byDestination.get(destinationOf(row));
      if (!seen || row.totalMessageCount > seen.totalMessageCount) byDestination.set(destinationOf(row), row);
    }
    return [...byDestination.values()].sort((a, b) => destinationOf(a).localeCompare(destinationOf(b)));
  }, [subscriptions.data, types, barred]);

  const matchesKnownDestination = options.some((o) => destinationOf(o) === value);
  const unknown = Boolean(unknownHint) && value.trim() !== '' && !subscriptions.isFetching && !matchesKnownDestination;
  const notAllowed =
    permission && barred.has(value) ? `You do not hold ${permission} on the destination ${value}.` : undefined;

  return (
    <div ref={root} className={styles.root}>
      <Combobox
        store={combobox}
        withinPortal={false}
        onOptionSubmit={(v) => {
          onChange(v);
          combobox.closeDropdown();
        }}
      >
        <Combobox.Target>
          <TextInput
            ref={inputRef}
            data-path={dataPath}
            label={label}
            description={description}
            placeholder={placeholder}
            value={value}
            disabled={disabled}
            w={w}
            autoComplete="off"
            spellCheck={false}
            onChange={(e) => {
              onChange(e.currentTarget.value);
              combobox.openDropdown();
              combobox.updateSelectedOptionIndex();
            }}
            onClick={() => combobox.openDropdown()}
            onFocus={() => {
              combobox.openDropdown();
              onFocus?.();
            }}
            onBlur={(e) => {
              // Keep the dropdown open while focus moves to the filter inside it.
              if (root.current?.contains(e.relatedTarget)) return;
              combobox.closeDropdown();
              onBlur?.();
            }}
            rightSection={
              subscriptions.isFetching ? (
                <LoadingState variant="inline" label="Loading destinations" />
              ) : (
                <Combobox.Chevron />
              )
            }
            rightSectionPointerEvents="none"
            error={error ?? notAllowed ?? (unknown ? unknownHint : undefined)}
            classNames={{ error: styles.error }}
          />
        </Combobox.Target>

        <Combobox.Dropdown className={styles.dropdown}>
          <div className={styles.filters}>
            <Chip.Group multiple value={types} onChange={(v) => setTypes(v as RoutingType[])}>
              <Group gap="xs" wrap="nowrap">
                <Text size="xs" c="dimmed" component="span" id={`${label ?? 'destination'}-type-filter`}>
                  Type
                </Text>
                {ROUTING_TYPES.map((t) => (
                  <Chip key={t} value={t} size="xs" variant="outline">
                    {t.toLowerCase()}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
          </div>

          <Combobox.Options>
            <div className={styles.options}>
              {pickerNotice(subscriptions, options.length, types.length) ??
                options.map((o) => (
                  <Combobox.Option
                    value={destinationOf(o)}
                    key={destinationOf(o)}
                    // Built explicitly: the visual row separates these with flex
                    // gaps, which a screen reader reads as one run-on word.
                    aria-label={`${destinationOf(o)}, ${routingTypeOf(o).toLowerCase()}, ${o.totalMessageCount.toLocaleString()} messages, on ${o.nodesPresent} of ${o.nodesTotal} nodes`}
                  >
                    <div className={styles.option}>
                      <span className={styles.name} title={destinationOf(o)}>
                        {destinationOf(o)}
                      </span>
                      <span className={styles.meta}>
                        <Text size="xs" span>
                          {routingTypeOf(o).toLowerCase()}
                        </Text>
                        <Text size="xs" span>
                          {o.totalMessageCount.toLocaleString()} msg
                        </Text>
                        <Text size="xs" span>
                          {o.nodesPresent}/{o.nodesTotal} nodes
                        </Text>
                      </span>
                    </div>
                  </Combobox.Option>
                ))}
            </div>
          </Combobox.Options>
        </Combobox.Dropdown>
      </Combobox>
    </div>
  );
}
