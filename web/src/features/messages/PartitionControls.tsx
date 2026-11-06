import type { ReactNode } from 'react';
import { Button, NumberInput, Select, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import type { MessagePageView } from './api.ts';
import { BROWSE_FROM, startProblem, type BrowseFrom, type PartitionBrowse } from './partitionBrowse.ts';

interface Fields {
  /** All partitions, or one partition named below. A stream never shows this choice. */
  scope: 'all' | 'one';
  partition: number | string;
  from: BrowseFrom;
  value: string;
}

const SCOPE = [
  { value: 'all', label: 'All partitions' },
  { value: 'one', label: 'One partition' },
];

/**
 * Where to read a partitioned log. The default is every partition. One partition can be focused. The start is the
 * earliest record, the latest, an offset or a time. The address holds the browse, so Browse writes it. A merged
 * page continues from each partition's own offset.
 */
export function PartitionControls({
  browse,
  partitions,
  next,
  resume,
  onBrowse,
}: Readonly<{
  browse: PartitionBrowse;
  /** How many partitions the destination has, when known. One partition needs no choice. */
  partitions?: number;
  /** The offset after this page of one focused partition, when that partition has more. */
  next?: number | null;
  /** Where a merged page continues, as partition:offset pairs. Not one offset for every partition. */
  resume?: string | null;
  onBrowse: (next: PartitionBrowse) => void;
}>) {
  const form = useForm<Fields>({
    initialValues: {
      scope: browse.partition == null ? 'all' : 'one',
      partition: browse.partition ?? 0,
      from: browse.from,
      value: browse.value ?? '',
    },
    validateInputOnBlur: true,
    validate: {
      partition: (v, values) =>
        values.scope === 'all' || partitions === 1
          ? null
          : Number.isInteger(Number(v)) && Number(v) >= 0
            ? null
            : 'Enter a partition number, from 0.',
      value: (v, values) => startProblem(values.from, v),
    },
  });
  const needsValue = form.values.from === 'offset' || form.values.from === 'time';
  const onePartition = partitions !== 1 && form.values.scope === 'one';
  const submit = form.onSubmit((values) => {
    const focused = partitions === 1 || values.scope === 'one';
    onBrowse({
      partition: focused ? (partitions === 1 ? 0 : Number(values.partition)) : null,
      from: values.from,
      value: values.from === 'offset' || values.from === 'time' ? values.value.trim() : undefined,
    });
  }, focusFirstInvalid(form.getInputNode));
  let partitionField: ReactNode = null;
  if (partitions !== 1) {
    partitionField = (
      <Select
        size="xs"
        w="13rem"
        label="Partitions"
        allowDeselect={false}
        data={SCOPE}
        value={form.values.scope}
        onChange={(v) => form.setFieldValue('scope', v === 'one' ? 'one' : 'all')}
      />
    );
  }
  return (
    <form noValidate onSubmit={submit} style={{ display: 'contents' }}>
      {partitionField}
      {onePartition ? (
        <NumberInput
          size="xs"
          w="7rem"
          label="Partition"
          min={0}
          allowDecimal={false}
          {...form.getInputProps('partition')}
        />
      ) : null}
      <Select
        size="xs"
        w="13rem"
        label="Start from"
        allowDeselect={false}
        data={BROWSE_FROM}
        {...form.getInputProps('from')}
      />
      {needsValue ? (
        <TextInput
          size="xs"
          w="15rem"
          label={form.values.from === 'offset' ? 'Offset' : 'Time (ISO-8601)'}
          description={
            form.values.from === 'offset' && form.values.scope === 'all' && partitions !== 1
              ? 'This offset applies on each partition. It is not one offset for the whole topic.'
              : undefined
          }
          {...form.getInputProps('value')}
        />
      ) : null}
      <Button type="submit" size="xs" variant="default" style={{ alignSelf: 'end' }}>
        Browse
      </Button>
      {browse.partition != null && next != null ? (
        <Button
          size="xs"
          variant="default"
          style={{ alignSelf: 'end' }}
          onClick={() => onBrowse({ partition: browse.partition, from: 'offset', value: String(next) })}
        >
          {`Next records in partition ${browse.partition}, from offset ${next}`}
        </Button>
      ) : null}
      {browse.partition == null && resume ? (
        <Button
          size="xs"
          variant="default"
          style={{ alignSelf: 'end' }}
          onClick={() => onBrowse({ ...browse, at: resume })}
        >
          Next records, continuing each partition from its own offset
        </Button>
      ) : null}
    </form>
  );
}

/**
 * What a read of a partitioned log has to say before the rows, when it is not what was asked: an offset past the end,
 * and a start that retention moved. A merged page has no single start and end, so it says so in its own words.
 */
export function PartitionNotices({
  page,
  browse,
}: Readonly<{ page: MessagePageView | undefined; browse: PartitionBrowse }>) {
  const log = page?.log;
  if (!log) return null;
  if (browse.partition == null) {
    return (
      <>
        {page.notice ? (
          <Notice tone="info" title="The start does not place this page">
            {page.notice}
          </Notice>
        ) : null}
        {log.retentionMoved ? (
          <Notice tone="info" title="Retention removed the start of a partition">
            Records before a partition's log start are gone, so that partition is read from its start, not where it was
            asked to.
          </Notice>
        ) : null}
      </>
    );
  }
  const asked = browse.from === 'offset' ? Number(browse.value) : null;
  return (
    <>
      {asked !== null && asked > log.end ? (
        <Notice tone="info" title="That offset is past the end of the partition">
          {`Offset ${asked} does not exist yet in partition ${log.partition}. The log ends at offset ${log.end}.`}
        </Notice>
      ) : null}
      {log.retentionMoved ? (
        <Notice tone="info" title="Retention removed the start of the partition">
          {`Records before offset ${log.start} are gone, so the browse starts there, not where it was asked to.`}
        </Notice>
      ) : null}
    </>
  );
}
