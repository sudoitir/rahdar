import type React from 'react';
import { useMemo, useRef, useState } from 'react';
import { Chip, Combobox, Group, Text, TextInput, useCombobox } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';

import styles from './AddressPicker.module.css';

import { addressOf, routingTypeOf, useAddresses, useQueues, type SubscriptionView } from './api.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';

const SUGGESTION_LIMIT = 300;
const ROUTING_TYPES = ['ANYCAST', 'MULTICAST'] as const;
type RoutingType = (typeof ROUTING_TYPES)[number];

export interface AddressPickerProps {
  clusterId: string;
  value: string;
  onChange: (value: string) => void;
  label?: string;
  description?: string;
  placeholder?: string;
  /**
   * Offer only the addresses the caller holds this permission on (such as `message:send`), and say so when a typed
   * name is an address they do not hold it on. A name that is not on the broker yet is left to the server.
   */
  permission?: string;
  /** Shown under the field when what was typed matches no address on the broker. */
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

/** What stands in for the options while queues fail to load or load, or when nothing matches. */
function pickerNotice(queues: ReturnType<typeof useQueues>, optionCount: number, typeCount: number): React.ReactNode {
  if (queues.isError) {
    return (
      <Combobox.Empty>
        Could not read this cluster&rsquo;s queues: {queues.error.message} You can still type an address by hand.
      </Combobox.Empty>
    );
  }
  if (queues.isPending) return <Combobox.Empty>Loading addresses…</Combobox.Empty>;
  if (optionCount > 0) return null;
  return (
    <Combobox.Empty>
      {typeCount > 0
        ? 'No address here matches that name and routing type. Clear the type filter, or type the name in full to use it anyway.'
        : 'No address on this cluster matches that name. Type it in full to use it anyway — Rahdar will start tracing once it appears.'}
    </Combobox.Empty>
  );
}

/**
 * Pick an address by name, with the broker's own list to choose from.
 *
 * Free text is deliberately allowed: an operator may be naming an address that
 * does not exist yet, or one that only appears under load. So this suggests, it
 * never constrains — but it says so when what you typed matches nothing, because
 * a silent typo is the failure this component exists to prevent.
 *
 * Suggestions come from queues rather than addresses because a queue carries the
 * routing type and the depth, which is what tells a request queue from a reply
 * queue at a glance. Search runs on the server (`q`), so the list stays bounded
 * on a broker with thousands of queues.
 */
export function AddressPicker({
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
}: Readonly<AddressPickerProps>) {
  const combobox = useCombobox({ onDropdownClose: () => combobox.resetSelectedOption() });
  const [types, setTypes] = useState<RoutingType[]>([]);
  const [debounced] = useDebouncedValue(value, 200);
  // The dropdown holds its own focusable controls, so closing on every blur
  // would put the routing-type filter out of reach of the keyboard entirely.
  const root = useRef<HTMLDivElement>(null);

  const queues = useQueues(clusterId, { q: debounced || undefined, size: SUGGESTION_LIMIT });

  const addresses = useAddresses(clusterId, { q: debounced || undefined, size: SUGGESTION_LIMIT }, Boolean(permission));
  // Until the addresses arrive nothing is withheld: an answer not yet known is no refusal.
  const barred = useMemo(() => {
    if (!permission || !addresses.data) return new Set<string>();
    return new Set(addresses.data.data.filter((a) => !a.allowedActions.includes(permission)).map((a) => a.name));
  }, [addresses.data, permission]);

  const options = useMemo(() => {
    const rows = (queues.data?.data ?? []).filter((q) => !barred.has(addressOf(q)));
    const wanted = types.length === 0 ? null : new Set<string>(types);
    // One row per address: the same address on several nodes is one thing to
    // whoever is picking it, so keep the busiest and count the rest as coverage.
    const byAddress = new Map<string, SubscriptionView>();
    for (const row of rows) {
      if (wanted && !wanted.has(routingTypeOf(row))) continue;
      const seen = byAddress.get(addressOf(row));
      if (!seen || row.totalMessageCount > seen.totalMessageCount) byAddress.set(addressOf(row), row);
    }
    return [...byAddress.values()].sort((a, b) => addressOf(a).localeCompare(addressOf(b)));
  }, [queues.data, types, barred]);

  const matchesKnownAddress = options.some((o) => addressOf(o) === value);
  const unknown = Boolean(unknownHint) && value.trim() !== '' && !queues.isFetching && !matchesKnownAddress;
  const notAllowed =
    permission && barred.has(value) ? `You do not hold ${permission} on the address ${value}.` : undefined;

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
              queues.isFetching ? <LoadingState variant="inline" label="Loading addresses" /> : <Combobox.Chevron />
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
                <Text size="xs" c="dimmed" component="span" id={`${label ?? 'address'}-type-filter`}>
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
              {pickerNotice(queues, options.length, types.length) ??
                options.map((o) => (
                  <Combobox.Option
                    value={addressOf(o)}
                    key={addressOf(o)}
                    // Built explicitly: the visual row separates these with flex
                    // gaps, which a screen reader reads as one run-on word.
                    aria-label={`${addressOf(o)}, ${routingTypeOf(o).toLowerCase()}, ${o.totalMessageCount.toLocaleString()} messages, on ${o.nodesPresent} of ${o.nodesTotal} nodes`}
                  >
                    <div className={styles.option}>
                      <span className={styles.name} title={addressOf(o)}>
                        {addressOf(o)}
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
