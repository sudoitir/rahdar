import { Button, NumberInput, Select, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import type { MessagePageView } from './api.ts';
import { BROWSE_FROM, startProblem, type BrowseFrom, type PartitionBrowse } from './partitionBrowse.ts';

interface Fields {
  partition: number | string;
  from: BrowseFrom;
  value: string;
}

/**
 * Where to read a partitioned log: the partition and a start (the earliest record, the latest, an offset or
 * a time). It replaces the selector and the node of a queue. The address holds the browse, so Browse writes
 * it; a start that needs a value is checked beside its field first.
 */
export function PartitionControls({
  browse,
  partitions,
  onBrowse,
}: Readonly<{
  browse: PartitionBrowse;
  /** How many partitions the destination has, when known: the choice is then a list. */
  partitions?: number;
  onBrowse: (next: PartitionBrowse) => void;
}>) {
  const form = useForm<Fields>({
    initialValues: { partition: browse.partition, from: browse.from, value: browse.value ?? '' },
    validateInputOnBlur: true,
    validate: {
      partition: (v) => (Number.isInteger(Number(v)) && Number(v) >= 0 ? null : 'Enter a partition number, from 0.'),
      value: (v, values) => startProblem(values.from, v),
    },
  });
  const needsValue = form.values.from === 'offset' || form.values.from === 'time';
  const submit = form.onSubmit(
    (values) =>
      onBrowse({
        partition: Number(values.partition),
        from: values.from,
        value: values.from === 'offset' || values.from === 'time' ? values.value.trim() : undefined,
      }),
    focusFirstInvalid(form.getInputNode),
  );
  return (
    <form noValidate onSubmit={submit} style={{ display: 'contents' }}>
      {partitions ? (
        <Select
          size="xs"
          w="7rem"
          label="Partition"
          allowDeselect={false}
          data={Array.from({ length: partitions }, (_, i) => String(i))}
          value={String(form.values.partition)}
          onChange={(v) => form.setFieldValue('partition', Number(v ?? 0))}
        />
      ) : (
        <NumberInput
          size="xs"
          w="7rem"
          label="Partition"
          min={0}
          allowDecimal={false}
          {...form.getInputProps('partition')}
        />
      )}
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
          {...form.getInputProps('value')}
        />
      ) : null}
      <Button type="submit" size="xs" variant="default" style={{ alignSelf: 'end' }}>
        Browse
      </Button>
    </form>
  );
}

/**
 * What a read of a partitioned log has to say before the rows: an offset past the end, a start that retention
 * moved, and that records of an open or aborted transaction are not shown.
 */
export function PartitionNotices({ page }: Readonly<{ page: MessagePageView | undefined }>) {
  const log = page?.log;
  return (
    <>
      {log && log.requestedOffset != null && log.requestedOffset > log.endOffset ? (
        <Notice tone="info" title="That offset is past the end of the partition">
          {`Offset ${log.requestedOffset} does not exist yet in partition ${log.partition}. The log ends at offset ${log.endOffset}.`}
        </Notice>
      ) : null}
      {log?.retentionMovedStart ? (
        <Notice tone="info" title="Retention removed the start of the partition">
          {`Records before offset ${log.startOffset} are gone, so the browse starts there, not where it was asked to.`}
        </Notice>
      ) : null}
      <Notice tone="neutral" title="Only committed records are shown">
        Records of a transaction that is still open or was aborted are not shown.
      </Notice>
    </>
  );
}
