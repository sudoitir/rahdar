import { useState } from 'react';
import { TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { Toolbar } from '../../ui/Toolbar.tsx';
import type { FilterSearch } from './search.ts';

/** The filter fields of a list, kept in the address so a filtered list can be shared. */
export function FilterBar({
  label,
  fields,
  end,
}: Readonly<{
  label: string;
  fields: { name: keyof FilterSearch; label: string }[];
  end?: React.ReactNode;
}>) {
  const search = useSearch({ strict: false }) as FilterSearch;
  const navigate = useNavigate();
  const [typed, setTyped] = useState<FilterSearch>(search);
  const write = useDebouncedCallback(
    (name: string, value: string) =>
      void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, [name]: value || undefined }) }),
    250,
  );
  return (
    <Toolbar
      label={label}
      start={fields.map((f) => (
        <TextInput
          key={f.name}
          label={f.label}
          size="xs"
          w="13rem"
          value={typed[f.name] ?? ''}
          onChange={(e) => {
            const value = e.currentTarget.value;
            setTyped((t) => ({ ...t, [f.name]: value }));
            write(f.name, value);
          }}
        />
      ))}
      end={end}
    />
  );
}
