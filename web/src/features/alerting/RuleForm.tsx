import { Button, Checkbox, MultiSelect, NumberInput, Select, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { focusFirstInvalid } from '../../ui/formErrors.ts';

import type { AlertRuleRequest, AlertRuleView, NotificationChannelView, PluginMetricView } from './api.ts';
import {
  COMPARATORS,
  DERIVED_METRICS,
  GAUGE_METRICS,
  METRIC_NOTES,
  RATE_METRICS,
  CONFIG_DRIFT_TEMPLATE,
  INSTALLATION_CONDITIONS,
  SETUP_RISK_TEMPLATE,
  SLOW_CONSUMER_TEMPLATE,
  STATE_CONDITIONS,
  metricKind,
  metricLabel,
} from './severity.ts';
import classes from './Alerting.module.css';

const RAHDAR_METRIC_OPTIONS = [...GAUGE_METRICS, ...RATE_METRICS, ...DERIVED_METRICS].map((m) => ({
  value: m,
  label: `${metricLabel(m)} (${metricKind(m)})`,
}));

const UNIT_WORDS: Record<string, string> = { count: 'count', per_second: 'per second', ms: 'ms', ratio: 'ratio 0–1' };

/**
 * Rahdar's own metrics, then the metrics running plugins publish (ADR-0113). A rule being edited
 * whose plugin is not running keeps its metric on the list, marked, so the form never shows it
 * blank.
 */
function metricOptions(pluginMetrics: PluginMetricView[], current: string | null) {
  const plugin = pluginMetrics.map((m) => ({
    value: m.metric,
    label: `${m.metric} (${UNIT_WORDS[m.unit] ?? m.unit}, per ${m.subject})`,
  }));
  if (current?.includes(':') && !pluginMetrics.some((m) => m.metric === current)) {
    plugin.push({ value: current, label: `${current} (plugin not running)` });
  }
  return plugin.length
    ? [
        { group: 'Rahdar', items: RAHDAR_METRIC_OPTIONS },
        { group: 'Plugins', items: plugin },
      ]
    : RAHDAR_METRIC_OPTIONS;
}
const STATE_OPTIONS = STATE_CONDITIONS.map((c) => ({ value: c, label: c.replaceAll('_', ' ').toLowerCase() }));
const INSTALLATION_OPTIONS = INSTALLATION_CONDITIONS.map((c) => ({
  value: c,
  label: c.replaceAll('_', ' ').toLowerCase(),
}));
const SEVERITY_OPTIONS = ['INFO', 'WARNING', 'CRITICAL'];

function TemplateLink({ label, onClick }: Readonly<{ label: string; onClick: () => void }>) {
  return (
    <Button variant="subtle" size="compact-sm" onClick={onClick}>
      {`Start from the ${label} template`}
    </Button>
  );
}

/** The prefilled starting points that fit the chosen kind. */
function TemplateLinks({
  kind,
  onSlowConsumer,
  onConfigDrift,
  onSetupRisk,
}: Readonly<{
  kind: 'METRIC_THRESHOLD' | 'STATE';
  onSlowConsumer: () => void;
  onConfigDrift: () => void;
  onSetupRisk: () => void;
}>) {
  if (kind === 'METRIC_THRESHOLD') return <TemplateLink label="slow-consumer" onClick={onSlowConsumer} />;
  return (
    <>
      <TemplateLink label="configuration-drift" onClick={onConfigDrift} />
      <TemplateLink label="setup-risk" onClick={onSetupRisk} />
    </>
  );
}

type RuleDraft = {
  name: string;
  kind: 'METRIC_THRESHOLD' | 'STATE';
  metric: string | null;
  comparator: string | null;
  /** A number once valid; the text of a half-typed one (a lone minus sign) until then. */
  threshold: number | string;
  stateCondition: string | null;
  forSeconds: number | string;
  severity: string;
  enabled: boolean;
  channelIds: string[];
};

/** The request for a validated draft: only the fields of the chosen kind are sent. */
function ruleRequest(d: RuleDraft): AlertRuleRequest {
  const metricRule = d.kind === 'METRIC_THRESHOLD';
  return {
    name: d.name.trim(),
    kind: d.kind,
    metric: metricRule ? (d.metric ?? undefined) : undefined,
    comparator: metricRule ? (d.comparator ?? undefined) : undefined,
    threshold: metricRule && typeof d.threshold === 'number' ? d.threshold : undefined,
    stateCondition: metricRule ? undefined : (d.stateCondition ?? undefined),
    forSeconds: typeof d.forSeconds === 'number' ? d.forSeconds : 0,
    severity: d.severity,
    enabled: d.enabled,
    channelIds: d.channelIds,
  };
}

/**
 * Create/edit an alert rule — a threshold rule (metric + comparator + threshold)
 * or a state-condition rule (a closed set of HA transitions), never both
 * (alerting spec). Submitting clears the fields the other kind does not use.
 */
export function RuleForm({
  channels,
  pluginMetrics = [],
  initial,
  onSubmit,
  submitting,
  onCancel,
  disabled = false,
}: Readonly<{
  channels: NotificationChannelView[];
  /** The metrics running plugins publish. */
  pluginMetrics?: PluginMetricView[];
  initial?: AlertRuleView;
  onSubmit: (body: AlertRuleRequest) => void;
  submitting: boolean;
  onCancel?: () => void;
  /** The caller may not change rules; the form stays visible and the view says why beside it. */
  disabled?: boolean;
}>) {
  // A rule with no cluster is about Rahdar itself: always a state rule, on a storage condition (ADR-0135).
  const installation = initial !== undefined && !initial.clusterId;
  const form = useForm<RuleDraft>({
    initialValues: {
      name: initial?.name ?? '',
      kind: initial?.kind === 'STATE' ? 'STATE' : 'METRIC_THRESHOLD',
      metric: initial?.metric ?? null,
      comparator: initial?.comparator ?? 'GT',
      threshold: initial?.threshold ?? '',
      stateCondition: initial?.stateCondition ?? null,
      forSeconds: initial?.forSeconds ?? 60,
      severity: initial?.severity ?? 'WARNING',
      enabled: initial?.enabled ?? true,
      channelIds: initial?.channelIds ?? [],
    },
    validateInputOnBlur: true,
    // Only the fields of the chosen kind are checked.
    validate: {
      name: (v) => (v.trim() ? null : 'Enter a name, so the rule and its alerts can be told apart.'),
      metric: (v, values) => (values.kind === 'METRIC_THRESHOLD' && !v ? 'Choose the metric the rule watches.' : null),
      comparator: (v, values) =>
        values.kind === 'METRIC_THRESHOLD' && !v ? 'Choose how the metric is compared.' : null,
      threshold: (v, values) =>
        values.kind === 'METRIC_THRESHOLD' && typeof v !== 'number'
          ? 'Enter the value that makes the rule fire.'
          : null,
      stateCondition: (v, values) =>
        values.kind === 'STATE' && !v ? 'Choose the cluster state the rule watches.' : null,
    },
  });
  const { kind, metric } = form.values;

  /**
   * A prefilled starting point, not a seeded rule (ADR-0044): no slow-consumer rule
   * is created on cluster registration, because a meaningful threshold is
   * workload-specific and any shipped value would be wrong for most deployments.
   */
  const applySlowConsumerTemplate = () =>
    form.setValues({
      name: SLOW_CONSUMER_TEMPLATE.name,
      metric: SLOW_CONSUMER_TEMPLATE.metric,
      comparator: SLOW_CONSUMER_TEMPLATE.comparator,
      threshold: SLOW_CONSUMER_TEMPLATE.threshold,
      forSeconds: SLOW_CONSUMER_TEMPLATE.forSeconds,
      severity: SLOW_CONSUMER_TEMPLATE.severity,
    });

  const applyConfigDriftTemplate = () =>
    form.setValues({
      name: CONFIG_DRIFT_TEMPLATE.name,
      stateCondition: CONFIG_DRIFT_TEMPLATE.stateCondition,
      forSeconds: CONFIG_DRIFT_TEMPLATE.forSeconds,
      severity: CONFIG_DRIFT_TEMPLATE.severity,
    });

  const applySetupRiskTemplate = () =>
    form.setValues({
      name: SETUP_RISK_TEMPLATE.name,
      stateCondition: SETUP_RISK_TEMPLATE.stateCondition,
      forSeconds: SETUP_RISK_TEMPLATE.forSeconds,
      severity: SETUP_RISK_TEMPLATE.severity,
    });

  const pluginMetric = pluginMetrics.find((m) => m.metric === metric);

  const submit = form.onSubmit((values) => onSubmit(ruleRequest(values)), focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <fieldset className={classes.fieldset} disabled={disabled}>
        <div className={classes.form}>
          <Select
            label="Kind"
            data={[
              { value: 'METRIC_THRESHOLD', label: 'Metric threshold' },
              { value: 'STATE', label: 'Cluster state' },
            ]}
            {...form.getInputProps('kind')}
            onChange={(v) => {
              form.setFieldValue('kind', v === 'STATE' ? 'STATE' : 'METRIC_THRESHOLD');
              form.clearErrors();
            }}
            allowDeselect={false}
            disabled={installation}
          />
          <TextInput label="Name" placeholder="Deep order backlog" {...form.getInputProps('name')} />

          {kind === 'METRIC_THRESHOLD' ? (
            <>
              <Select
                label="Metric"
                placeholder="Choose a metric"
                data={metricOptions(pluginMetrics, metric)}
                {...form.getInputProps('metric')}
              />
              <Select
                label="Comparator"
                data={COMPARATORS.map((c) => ({ value: c, label: c }))}
                {...form.getInputProps('comparator')}
                allowDeselect={false}
              />
              <NumberInput label="Threshold" {...form.getInputProps('threshold')} />
            </>
          ) : (
            <Select
              label="State condition"
              placeholder="Choose a condition"
              data={installation ? INSTALLATION_OPTIONS : STATE_OPTIONS}
              {...form.getInputProps('stateCondition')}
            />
          )}

          <NumberInput
            label="For (seconds)"
            description="0 fires immediately"
            {...form.getInputProps('forSeconds')}
            min={0}
          />
          <Select label="Severity" data={SEVERITY_OPTIONS} {...form.getInputProps('severity')} allowDeselect={false} />
          <MultiSelect
            label="Notify channels"
            placeholder={channels.length ? 'None selected' : 'No channels configured yet'}
            data={channels.map((c) => ({ value: c.id, label: c.name }))}
            {...form.getInputProps('channelIds')}
            disabled={channels.length === 0}
          />

          {metric && METRIC_NOTES[metric] ? (
            <Text size="xs" c="dimmed" className={`${classes.wide} ${classes.help}`}>
              {METRIC_NOTES[metric]}
            </Text>
          ) : null}
          {kind === 'METRIC_THRESHOLD' && pluginMetric ? (
            <Text size="xs" c="dimmed" className={`${classes.wide} ${classes.help}`}>
              {pluginMetric.description} Published by the {pluginMetric.plugin} plugin and sampled on every subscription
              scrape; the rule fires per {pluginMetric.subject}.
            </Text>
          ) : null}

          <div className={`${classes.wide} ${classes.actions}`}>
            <Checkbox label="Enabled" {...form.getInputProps('enabled', { type: 'checkbox' })} />
            <Button type="submit" loading={submitting}>
              {initial ? 'Save' : 'Add rule'}
            </Button>
            {onCancel ? (
              <Button variant="default" onClick={onCancel}>
                Cancel
              </Button>
            ) : null}
            {initial ? null : (
              <TemplateLinks
                kind={kind}
                onSlowConsumer={applySlowConsumerTemplate}
                onConfigDrift={applyConfigDriftTemplate}
                onSetupRisk={applySetupRiskTemplate}
              />
            )}
          </div>
        </div>
      </fieldset>
    </form>
  );
}
