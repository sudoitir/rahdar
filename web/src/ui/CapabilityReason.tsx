import { Code, Stack, Text } from '@mantine/core';

/**
 * Why a control is unavailable, in full: the reason, and the change that enables it where one exists
 * (non-negotiable #5): the `broker.xml` snippet on Artemis, the command or setting on Kafka and RabbitMQ.
 * The one rendering of it, shared by the {@link CapabilityGate} popover and the explanation a blocked
 * menu item opens, so both say the same thing the same way.
 */
export function CapabilityReason({
  reason,
  snippet,
  size = 'xs',
}: Readonly<{
  reason: string;
  snippet?: string | null;
  /** `xs` in a popover; `sm` where the explanation is the whole dialog. */
  size?: 'xs' | 'sm';
}>) {
  return (
    <Stack gap="xs">
      <Text size={size}>{reason}</Text>
      {snippet ? (
        <>
          <Text size="xs" fw={600}>
            {snippet.trimStart().startsWith('<') ? (
              <>
                Add this to <Code>broker.xml</Code>:
              </>
            ) : (
              'Make this change on the broker:'
            )}
          </Text>
          <Code block fz="xs" style={{ whiteSpace: 'pre-wrap' }}>
            {snippet}
          </Code>
        </>
      ) : null}
    </Stack>
  );
}
