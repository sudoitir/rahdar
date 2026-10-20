import { Button, Code, Group, Modal, Stack, Text } from '@mantine/core';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { Section } from '../../ui/Section.tsx';
import { COLUMNS, EVALUATION_WORDS, EXAMPLES, FULL_TEXT, FUNCTIONS } from './catalogue.ts';

/**
 * The dialect, in the page rather than behind a documentation link. An operator
 * writing a query at 3am should not have to leave the console to find out what
 * `props.` means or why one predicate is free and another is not.
 *
 * <p>Mantine's `Modal` traps focus while open and returns it to whatever opened
 * it on close, so the keyboard path in and back out is the component's, not a
 * hand-rolled one.
 */
export function SyntaxHelp({
  opened,
  onClose,
  onLoadExample,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  /** Loads an example into the editor and closes — one action, per the spec. */
  onLoadExample: (sql: string) => void;
}>) {
  return (
    <Modal opened={opened} onClose={onClose} title="The console's dialect" size="xl">
      <Stack gap="lg">
        <Stack gap="xs">
          <Text size="sm">
            A restricted subset of SQL: <Code>SELECT</Code> only, one <Code>FROM</Code>, no join, no subquery, no union.
            It cannot express a mutation — acting on a result row goes back through the message operations, which carry
            their own dry run and audit record.
          </Text>
          <Text size="sm">
            A subscription name is not a SQL identifier — <Code>ORDER.IN</Code> is a reserved word and a dot — so it is
            always double-quoted. Wildcards are Artemis&apos;, not SQL&apos;s: <Code>*</Code> matches one dot-delimited
            level and <Code>#</Code> matches many.
          </Text>
        </Stack>

        <Section title="Where the query reads" headingLevel={3}>
          <Text size="sm">
            The schema qualifier picks the backend. <Code>FROM broker.&quot;Q&quot;</Code> reads the live brokers —
            current truth. <Code>FROM index.&quot;Q&quot;</Code> reads the historical index, which still holds messages
            that have since been consumed, and only covers subscriptions with a subscription.{' '}
            <Code>FROM &quot;Q&quot;</Code> reads the index when every subscription it names is captured, and the live
            brokers otherwise — a subscription that is only sampled is read live. The plan says which it picked.
          </Text>
        </Section>

        <Section title="What a predicate costs" headingLevel={3}>
          <Text size="sm">
            This is the one thing worth knowing before running anything. A predicate over a header or an application
            property becomes a JMS selector, so the broker filters and Rahdar never sees the messages that did not
            match. A predicate over the body cannot be pushed down at all: every message the broker returns has to be
            read and examined. The cost line under the editor says which of the two your query is, before it runs.
          </Text>
          <Text size="sm">
            One trap worth stating: a predicate that is free on its own stops being free inside an <Code>OR</Code> with
            a body predicate. Pushing down one side of an <Code>OR</Code> would narrow the set the other side gets to
            see, so the whole disjunction is scanned.
          </Text>
        </Section>

        <Section title="Columns" headingLevel={3}>
          <DescriptionList
            label="Columns"
            items={COLUMNS.map((c) => ({
              term: c.name,
              value: `${c.description}${c.indexOnly ? ' Index only.' : ''}`,
              hint: EVALUATION_WORDS[c.evaluation],
            }))}
          />
          <Text size="sm">
            Application properties are destinationed by name as <Code>props.tenant</Code>, and a JSON field inside the
            body as <Code>body-&gt;&gt;&apos;orderId&apos;</Code>. The only functions the dialect accepts are{' '}
            {FUNCTIONS.map((f) => (
              <Code key={f}>{f}()</Code>
            ))}
            , plus <Code>interval</Code> in a relative time.
          </Text>
          <Text size="sm">
            Over the index, <Code>{FULL_TEXT.predicate}</Code> searches stored bodies, and <Code>{FULL_TEXT.rank}</Code>{' '}
            orders by how well each row matched — it needs a <Code>MATCH()</Code> in the same query to rank against.{' '}
            {FULL_TEXT.note}
          </Text>
        </Section>

        <Section title="Examples" headingLevel={3}>
          {EXAMPLES.map((example) => (
            <Stack key={example.title} gap={4}>
              <Group justify="space-between" align="flex-start" wrap="nowrap">
                <Text size="sm" fw={600}>
                  {example.title}
                </Text>
                <Button
                  size="compact-xs"
                  variant="default"
                  onClick={() => {
                    onLoadExample(example.sql);
                    onClose();
                  }}
                >
                  Load into editor
                </Button>
              </Group>
              <Code block>{example.sql}</Code>
              <Text size="xs" c="dimmed">
                {example.note}
              </Text>
            </Stack>
          ))}
        </Section>
      </Stack>
    </Modal>
  );
}
