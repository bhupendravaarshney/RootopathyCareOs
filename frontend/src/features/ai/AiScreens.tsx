import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldCheck,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ApiFailure } from '../../api/client';
import type {
  AiActionRequest,
  AiRow,
  AiScreen,
  BillingRow,
  BillingScreen,
  CarePlanRow,
  CarePlanScreen,
  FollowupRow,
  FollowupScreen,
  ReportingRow,
  ReportingScreen,
  WorkforceAction,
  WorkforceField,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { findScreen } from '../../data/screens';
import type { AiClient } from './ai-types';
import type { CareOsApiClient } from '../../api/client';

type WorkflowContext = {
  recordId: string | null;
  encounterId: string | null;
  patientId: string | null;
};
type UiIssue = { correlationId?: string; detail: string; status?: number; title: string };
type CarePlanProjectionClient = Pick<
  CareOsApiClient,
  'getCarePlanScreen' | 'performCarePlanAction'
>;
type FollowupProjectionClient = Pick<
  CareOsApiClient,
  'getFollowupScreen' | 'performFollowupAction'
>;
type BillingProjectionClient = Pick<CareOsApiClient, 'getBillingScreen' | 'performBillingAction'>;
type ReportingProjectionClient = Pick<
  CareOsApiClient,
  'getReportingScreen' | 'performReportingAction'
>;
type ProjectionClient =
  | AiClient
  | CarePlanProjectionClient
  | FollowupProjectionClient
  | BillingProjectionClient
  | ReportingProjectionClient;
type ProjectionRow = AiRow | CarePlanRow | FollowupRow | BillingRow | ReportingRow;
type ProjectionScreen =
  AiScreen | CarePlanScreen | FollowupScreen | BillingScreen | ReportingScreen;
type ProjectionMode = 'ai' | 'care-plan' | 'followup' | 'billing' | 'reporting';

const profiles = {
  ai: {
    actionEyebrow: 'Governed AI action',
    actionGuidance:
      'AI output remains a draft until an eligible clinician explicitly decides the exact latest version.',
    badgeLabel: 'Clinician governed',
    contextGuidance:
      'Carry only the exact patient, encounter and AI-session identifiers required for this workflow.',
    contextTitle: 'AI session context',
    emptyGuidance: 'Adjust the context or use a non-target session action.',
    errorTitle: 'AI workspace unavailable',
    evidenceLabel: 'AI',
    idempotencyNamespace: 'm8',
    loadingLabel: 'authorized AI evidence',
    nounPlural: 'AI sessions',
    nounSingular: 'AI session',
    prefix: 'P8',
    recordParameter: 'aiSessionId',
    total: 10,
  },
  'care-plan': {
    actionEyebrow: 'Governed care-plan action',
    actionGuidance:
      'Every change is attributed to the exact plan version; approval and activation remain separate clinician decisions.',
    badgeLabel: 'Clinician governed',
    contextGuidance:
      'Carry only the exact patient, encounter and care-plan identifiers required for this workflow.',
    contextTitle: 'Care-plan context',
    emptyGuidance: 'Adjust the context or create an authorized coordinated plan.',
    errorTitle: 'Care-planning workspace unavailable',
    evidenceLabel: 'Care-plan',
    idempotencyNamespace: 'm9',
    loadingLabel: 'authorized care-plan evidence',
    nounPlural: 'care plans',
    nounSingular: 'care plan',
    prefix: 'P9',
    recordParameter: 'carePlanId',
    total: 12,
  },
  followup: {
    actionEyebrow: 'Governed follow-up action',
    actionGuidance:
      'Measurements are attributed to exact definitions and events; every threshold breach creates owned escalation work.',
    badgeLabel: 'Clinician governed',
    contextGuidance:
      'Carry only the exact patient, encounter and monitoring-plan identifiers required for this workflow.',
    contextTitle: 'Follow-up context',
    emptyGuidance: 'Adjust the context or create an authorized monitoring plan.',
    errorTitle: 'Follow-up workspace unavailable',
    evidenceLabel: 'Follow-up',
    idempotencyNamespace: 'm10',
    loadingLabel: 'authorized follow-up evidence',
    nounPlural: 'monitoring records',
    nounSingular: 'monitoring record',
    prefix: 'P10',
    recordParameter: 'followupPlanId',
    total: 9,
  },
  billing: {
    actionEyebrow: 'Governed financial action',
    actionGuidance:
      'Amounts use currency minor units; settlement evidence is append-only and never changes clinical completion.',
    badgeLabel: 'Financially governed',
    contextGuidance:
      'Carry only the exact patient and invoice identifiers needed for this financial workflow.',
    contextTitle: 'Billing context',
    emptyGuidance: 'Adjust the context or create the authorized financial artifact.',
    errorTitle: 'Billing workspace unavailable',
    evidenceLabel: 'Billing',
    idempotencyNamespace: 'm11',
    loadingLabel: 'authorized financial evidence',
    nounPlural: 'financial records',
    nounSingular: 'financial record',
    prefix: 'P11',
    recordParameter: 'invoiceId',
    total: 11,
  },
  reporting: {
    actionEyebrow: 'Governed reporting action',
    actionGuidance:
      'Reports contain aggregate metrics only; schedules and exports remain purpose-bound, attributable and expiring.',
    badgeLabel: 'Aggregate only',
    contextGuidance:
      'Carry only the exact report-run identifier required for this reporting workflow.',
    contextTitle: 'Report context',
    emptyGuidance: 'Adjust the filters or create an authorized aggregate report run.',
    errorTitle: 'Reporting workspace unavailable',
    evidenceLabel: 'Reporting',
    idempotencyNamespace: 'm12',
    loadingLabel: 'authorized aggregate reporting evidence',
    nounPlural: 'reporting records',
    nounSingular: 'reporting record',
    prefix: 'P12',
    recordParameter: 'reportRunId',
    total: 10,
  },
} as const;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function issueFromFailure(failure: ApiFailure): UiIssue {
  return {
    correlationId: failure.correlationId,
    detail: failure.problem.detail,
    status: failure.status,
    title: failure.problem.title,
  };
}

function contextFromHash(recordParameter: string): WorkflowContext {
  const parameters = new URLSearchParams(window.location.hash.split('?', 2)[1] ?? '');
  const value = (key: string) => {
    const candidate = parameters.get(key);
    return candidate && uuidPattern.test(candidate) ? candidate : null;
  };
  return {
    recordId: value(recordParameter),
    encounterId: value('encounterId'),
    patientId: value('patientId'),
  };
}

function contextForRow(
  row: ProjectionRow,
  current: WorkflowContext,
  mode: ProjectionMode,
): WorkflowContext {
  return {
    recordId:
      mode === 'reporting'
        ? ('reportRunId' in row && row.reportRunId) || null
        : mode === 'billing'
          ? ('invoiceId' in row && row.invoiceId) || null
          : ('followupPlanId' in row && row.followupPlanId) || row.id,
    encounterId: ('encounterId' in row && row.encounterId) || current.encounterId,
    patientId: ('patientId' in row && row.patientId) || current.patientId,
  };
}

function contextHref(id: string, context: WorkflowContext, recordParameter: string) {
  const parameters = new URLSearchParams();
  for (const key of ['patientId', 'encounterId'] as const) {
    if (context[key]) parameters.set(key, context[key]!);
  }
  if (context.recordId) parameters.set(recordParameter, context.recordId);
  const query = parameters.toString();
  return `#/${id}${query ? `?${query}` : ''}`;
}

function humanize(value: string) {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (character) => character.toUpperCase());
}

function metricTone(tone: string) {
  return ['neutral', 'info', 'success', 'warning', 'danger'].includes(tone) ? tone : 'neutral';
}

function statusTone(status: string) {
  if (['accepted', 'resolved'].includes(status)) return 'success';
  if (['failed', 'rejected', 'cancelled'].includes(status)) return 'danger';
  if (['processing', 'draft_ready', 'under_review'].includes(status)) return 'warning';
  return 'neutral';
}

function idempotencyKey(actionKey: string, namespace: string) {
  if (!globalThis.crypto?.randomUUID) {
    throw new Error('Secure random identifiers are unavailable in this browser.');
  }
  return `${namespace}:${actionKey}:${globalThis.crypto.randomUUID()}`;
}

function fieldDefault(
  field: WorkforceField,
  selected: ProjectionRow | undefined,
  context: WorkflowContext,
) {
  const contextValue = context[field.key as keyof WorkflowContext];
  if (typeof contextValue === 'string') return contextValue;
  if (
    field.key === 'safetyFlagId' &&
    selected &&
    'safetyFlagId' in selected &&
    selected.safetyFlagId
  ) {
    return selected.safetyFlagId;
  }
  return selected?.values[field.key] ?? field.options[0]?.value ?? '';
}

function ActionField({
  field,
  onChange,
  value,
}: {
  field: WorkforceField;
  onChange(value: string): void;
  value: string;
}) {
  if (field.inputType === 'select') {
    return (
      <label>
        {field.label}
        <select
          required={field.required}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          {!field.required && <option value="">Not set</option>}
          {field.options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    );
  }
  if (field.inputType === 'textarea') {
    return (
      <label className="full-width">
        {field.label}
        <textarea
          maxLength={20000}
          required={field.required}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      </label>
    );
  }
  return (
    <label>
      {field.label}
      <input
        autoComplete="off"
        maxLength={20000}
        pattern={field.inputType === 'uuid' ? '[0-9a-fA-F-]{36}' : undefined}
        required={field.required}
        type={field.inputType === 'uuid' ? 'text' : field.inputType}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

export function AiScreenPage({
  client,
  id,
  mode = 'ai',
  organizationId,
  shell,
}: {
  client: ProjectionClient;
  id: string;
  mode?: ProjectionMode;
  organizationId: string;
  shell: ShellSessionProps;
}) {
  const profile = profiles[mode];
  const registered = findScreen(id);
  const [context, setContext] = useState<WorkflowContext>(() =>
    contextFromHash(profile.recordParameter),
  );
  const [contextDraft, setContextDraft] = useState<WorkflowContext>(context);
  const [projection, setProjection] = useState<ProjectionScreen | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [activeAction, setActiveAction] = useState<WorkforceAction | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [queryDraft, setQueryDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [filters, setFilters] = useState<{ q?: string; status?: string; limit: number }>({
    limit: 25,
  });
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [loadIssue, setLoadIssue] = useState<UiIssue | null>(null);
  const [mutationIssue, setMutationIssue] = useState<UiIssue | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const lastAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const load = async () => {
      await Promise.resolve();
      if (controller.signal.aborted) return;
      setLoading(true);
      setLoadIssue(null);
      try {
        const commonQuery = {
          ...filters,
          ...(context.patientId ? { patientId: context.patientId } : {}),
          ...(context.encounterId ? { encounterId: context.encounterId } : {}),
        };
        const result =
          mode === 'reporting'
            ? await (client as ReportingProjectionClient).getReportingScreen(
                organizationId,
                id,
                {
                  limit: filters.limit,
                  q: filters.q,
                  status: filters.status,
                  ...(context.recordId ? { reportRunId: context.recordId } : {}),
                },
                { signal: controller.signal },
              )
            : mode === 'billing'
              ? await (client as BillingProjectionClient).getBillingScreen(
                  organizationId,
                  id,
                  {
                    limit: filters.limit,
                    q: filters.q,
                    status: filters.status,
                    ...(context.patientId ? { patientId: context.patientId } : {}),
                    ...(context.recordId ? { invoiceId: context.recordId } : {}),
                  },
                  { signal: controller.signal },
                )
              : mode === 'care-plan'
                ? await (client as CarePlanProjectionClient).getCarePlanScreen(
                    organizationId,
                    id,
                    {
                      ...commonQuery,
                      ...(context.recordId ? { carePlanId: context.recordId } : {}),
                    },
                    { signal: controller.signal },
                  )
                : mode === 'followup'
                  ? await (client as FollowupProjectionClient).getFollowupScreen(
                      organizationId,
                      id,
                      {
                        ...commonQuery,
                        ...(context.recordId ? { followupPlanId: context.recordId } : {}),
                      },
                      { signal: controller.signal },
                    )
                  : await (client as AiClient).getAiScreen(
                      organizationId,
                      id,
                      {
                        ...commonQuery,
                        ...(context.recordId ? { aiSessionId: context.recordId } : {}),
                      },
                      { signal: controller.signal },
                    );
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setProjection(null);
          setLoadIssue(issueFromFailure(result));
          return;
        }
        setProjection(result.data);
        setSelectedId((current) =>
          result.data.rows.some((row) => row.id === current)
            ? current
            : result.data.rows.length === 1
              ? result.data.rows[0]!.id
              : null,
        );
      } catch (error: unknown) {
        if (!controller.signal.aborted) {
          setProjection(null);
          setLoadIssue({
            detail:
              error instanceof Error
                ? error.message
                : `The ${profile.evidenceLabel.toLowerCase()} projection could not be loaded.`,
            title: profile.errorTitle,
          });
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    void load();
    return () => controller.abort();
  }, [client, context, filters, id, mode, organizationId, profile, refresh]);

  const selected = projection?.rows.find((row) => row.id === selectedId);
  const availableActions = useMemo(() => {
    if (!projection) return [];
    return projection.actions.filter(
      (action) => !action.targetRequired || selected?.allowedActionKeys.includes(action.key),
    );
  }, [projection, selected]);

  function openAction(action: WorkforceAction) {
    setMutationIssue(null);
    setSuccess(null);
    setReason('');
    setFields(
      Object.fromEntries(
        action.fields.map((field) => [field.key, fieldDefault(field, selected, context)]),
      ),
    );
    setActiveAction(action);
  }

  async function submitAction(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!activeAction) return;
    setMutationIssue(null);
    try {
      const normalizedReason = reason.trim().normalize('NFC');
      if (normalizedReason.length < 10 || normalizedReason.length > 500) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      const normalizedFields = Object.fromEntries(
        activeAction.fields
          .map((field) => {
            const value = (fields[field.key] ?? '').trim().normalize('NFC');
            if (field.required && !value) throw new Error(`${field.label} is required.`);
            if (field.inputType === 'uuid' && value && !uuidPattern.test(value)) {
              throw new Error(`${field.label} must contain a valid UUID.`);
            }
            return value ? [field.key, value] : null;
          })
          .filter((entry): entry is [string, string] => entry !== null),
      );
      const targetId = activeAction.targetRequired ? selected?.id : undefined;
      if (activeAction.targetRequired && !targetId)
        throw new Error(`Select the exact ${profile.nounSingular} first.`);
      const body: AiActionRequest = {
        fields: normalizedFields,
        reason: normalizedReason,
        ...(targetId ? { targetId } : {}),
      };
      const fingerprint = JSON.stringify({ action: activeAction.key, body });
      const key =
        lastAttempt.current?.fingerprint === fingerprint
          ? lastAttempt.current.key
          : idempotencyKey(activeAction.key, profile.idempotencyNamespace);
      lastAttempt.current = { fingerprint, key };
      setSubmitting(true);
      const result =
        mode === 'reporting'
          ? await (client as ReportingProjectionClient).performReportingAction(
              organizationId,
              id,
              activeAction.key,
              body,
              activeAction.ifMatchRequired ? selected?.etag : undefined,
              key,
            )
          : mode === 'billing'
            ? await (client as BillingProjectionClient).performBillingAction(
                organizationId,
                id,
                activeAction.key,
                body,
                activeAction.ifMatchRequired ? selected?.etag : undefined,
                key,
              )
            : mode === 'care-plan'
              ? await (client as CarePlanProjectionClient).performCarePlanAction(
                  organizationId,
                  id,
                  activeAction.key,
                  body,
                  activeAction.ifMatchRequired ? selected?.etag : undefined,
                  key,
                )
              : mode === 'followup'
                ? await (client as FollowupProjectionClient).performFollowupAction(
                    organizationId,
                    id,
                    activeAction.key,
                    body,
                    activeAction.ifMatchRequired ? selected?.etag : undefined,
                    key,
                  )
                : await (client as AiClient).performAiAction(
                    organizationId,
                    id,
                    activeAction.key,
                    body,
                    activeAction.ifMatchRequired ? selected?.etag : undefined,
                    key,
                  );
      if (!result.ok) {
        setMutationIssue(issueFromFailure(result));
        return;
      }
      lastAttempt.current = null;
      setProjection(result.data);
      const next = result.data.rows.find((row) => row.id === targetId) ?? result.data.rows[0];
      setSelectedId(next?.id ?? null);
      if (next) {
        const nextContext = contextForRow(next, context, mode);
        setContext(nextContext);
        setContextDraft(nextContext);
      }
      setActiveAction(null);
      setSuccess(`${activeAction.label} completed using the server-confirmed result.`);
    } catch (error) {
      setMutationIssue({
        detail: error instanceof Error ? error.message : 'The action could not be prepared.',
        title: 'Action not submitted',
      });
    } finally {
      setSubmitting(false);
    }
  }

  const moduleNumber = Number(id.slice(-2));
  const previousId =
    moduleNumber > 1 ? `${profile.prefix}-${String(moduleNumber - 1).padStart(2, '0')}` : null;
  const nextId =
    moduleNumber < profile.total
      ? `${profile.prefix}-${String(moduleNumber + 1).padStart(2, '0')}`
      : null;

  return (
    <Shell currentId={id} {...shell}>
      <div className="page-head workforce-page-head">
        <div>
          <span className="eyebrow">{id}</span>
          <h1>{projection?.title ?? registered.title}</h1>
          <p>{projection?.purpose ?? registered.purpose}</p>
        </div>
        <span className="badge success">
          <ShieldCheck aria-hidden="true" size={14} /> {profile.badgeLabel}
        </span>
      </div>

      {success && (
        <div className="workforce-success" role="status">
          <CheckCircle2 aria-hidden="true" size={19} />
          {success}
        </div>
      )}

      <details className="panel workforce-actions-panel">
        <summary>{profile.contextTitle}</summary>
        <p>{profile.contextGuidance}</p>
        <form
          className="workforce-filters"
          onSubmit={(event) => {
            event.preventDefault();
            const invalid = Object.entries(contextDraft).find(
              ([, value]) => value && !uuidPattern.test(value),
            );
            if (invalid) {
              setLoadIssue({
                detail: `${humanize(invalid[0])} must contain a valid UUID.`,
                title: `Invalid ${profile.evidenceLabel.toLowerCase()} context`,
              });
              return;
            }
            setContext(contextDraft);
            setSelectedId(null);
          }}
        >
          {(['patientId', 'encounterId', 'recordId'] as const).map((key) => (
            <label key={key}>
              {key === 'recordId' ? profile.nounSingular : humanize(key)}
              <input
                autoComplete="off"
                placeholder="Optional UUID"
                value={contextDraft[key] ?? ''}
                onChange={(event) =>
                  setContextDraft((current) => ({
                    ...current,
                    [key]: event.target.value.trim() || null,
                  }))
                }
              />
            </label>
          ))}
          <div className="workforce-filter-actions">
            <button className="secondary-button" type="submit">
              Apply context
            </button>
            <button
              className="text-action"
              type="button"
              onClick={() => {
                const cleared = { recordId: null, encounterId: null, patientId: null };
                setContext(cleared);
                setContextDraft(cleared);
                setSelectedId(null);
              }}
            >
              Clear
            </button>
          </div>
        </form>
      </details>

      {loading && (
        <section aria-live="polite" className="panel workforce-state">
          <LoaderCircle className="spin" aria-hidden="true" size={24} />
          <h2>Loading {profile.loadingLabel}…</h2>
          <p>CareOS is checking purpose, permission and minimum-necessary scope.</p>
        </section>
      )}

      {!loading && loadIssue && (
        <section className="panel workforce-state" role="alert">
          <AlertCircle aria-hidden="true" size={28} />
          <h2>{loadIssue.status === 403 ? 'This screen is not authorized' : loadIssue.title}</h2>
          <p>{loadIssue.detail}</p>
          {loadIssue.correlationId && <small>Reference: {loadIssue.correlationId}</small>}
          <button
            className="secondary-button"
            type="button"
            onClick={() => setRefresh((value) => value + 1)}
          >
            <RefreshCw aria-hidden="true" size={17} /> Retry
          </button>
        </section>
      )}

      {!loading && projection && (
        <>
          {projection.notices.map((notice, index) => (
            <aside
              className={`workforce-notice ${metricTone(notice.tone)}`}
              key={`${notice.title}-${index}`}
            >
              <AlertCircle aria-hidden="true" size={19} />
              <div>
                <strong>{notice.title}</strong>
                <p>{notice.detail}</p>
              </div>
            </aside>
          ))}

          {projection.metrics.length > 0 && (
            <section
              aria-label={`${profile.evidenceLabel} governance metrics`}
              className="workforce-metrics"
            >
              {projection.metrics.map((metric) => (
                <article className={`workforce-metric ${metricTone(metric.tone)}`} key={metric.key}>
                  <span>{metric.label}</span>
                  <strong>{metric.value.toLocaleString()}</strong>
                </article>
              ))}
            </section>
          )}

          <section className="panel workforce-directory-panel">
            <form
              aria-label={`${profile.evidenceLabel} filters`}
              className="workforce-filters"
              onSubmit={(event) => {
                event.preventDefault();
                const q = queryDraft.trim();
                setFilters({
                  limit: 25,
                  ...(q ? { q } : {}),
                  ...(statusDraft.trim() ? { status: statusDraft.trim() } : {}),
                });
              }}
            >
              <label>
                Search
                <span className="workforce-search-input">
                  <Search aria-hidden="true" size={17} />
                  <input
                    maxLength={100}
                    placeholder="Session or patient"
                    value={queryDraft}
                    onChange={(event) => setQueryDraft(event.target.value)}
                  />
                </span>
              </label>
              <label>
                Status
                <input
                  maxLength={80}
                  placeholder="All statuses"
                  value={statusDraft}
                  onChange={(event) => setStatusDraft(event.target.value)}
                />
              </label>
              <div className="workforce-filter-actions">
                <button className="secondary-button" type="submit">
                  Apply filters
                </button>
                <button
                  className="text-action"
                  type="button"
                  onClick={() => {
                    setQueryDraft('');
                    setStatusDraft('');
                    setFilters({ limit: 25 });
                  }}
                >
                  Clear
                </button>
              </div>
            </form>
            <div className="workforce-table-heading">
              <div>
                <h2>Authorized {profile.nounPlural}</h2>
                <p aria-live="polite">
                  {projection.rows.length}{' '}
                  {projection.rows.length === 1 ? profile.nounSingular : profile.nounPlural}{' '}
                  returned
                </p>
              </div>
              <button
                aria-label={`Refresh ${profile.nounPlural}`}
                className="icon-button"
                type="button"
                onClick={() => setRefresh((value) => value + 1)}
              >
                <RefreshCw aria-hidden="true" size={18} />
              </button>
            </div>
            {projection.rows.length === 0 ? (
              <div className="workforce-empty">
                <Search aria-hidden="true" size={25} />
                <h3>No authorized {profile.nounPlural} found</h3>
                <p>{profile.emptyGuidance}</p>
              </div>
            ) : (
              <div
                aria-label={`${projection.title} ${profile.nounPlural}`}
                className="table-wrap workforce-table-wrap"
                role="region"
                tabIndex={0}
              >
                <table className="workforce-table">
                  <caption className="sr-only">
                    {projection.title} authorized {profile.nounPlural}
                  </caption>
                  <thead>
                    <tr>
                      <th>
                        <span className="sr-only">Select</span>
                      </th>
                      {projection.columns.map((column) => (
                        <th key={column.key}>{column.label}</th>
                      ))}
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {projection.rows.map((row) => (
                      <tr className={selectedId === row.id ? 'selected' : ''} key={row.id}>
                        <td data-label="Select">
                          <input
                            aria-label={`Select ${profile.nounSingular} ${row.id}`}
                            checked={selectedId === row.id}
                            name={`${mode}-record`}
                            type="radio"
                            onChange={() => {
                              setSelectedId(row.id);
                              const next = contextForRow(row, context, mode);
                              setContext(next);
                              setContextDraft(next);
                            }}
                          />
                        </td>
                        {projection.columns.map((column) => (
                          <td data-label={column.label} key={column.key}>
                            {row.values[column.key] || '—'}
                          </td>
                        ))}
                        <td data-label="Status">
                          <span className={`badge ${statusTone(row.status)}`}>
                            {humanize(row.status)}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section aria-labelledby="ai-actions-title" className="panel workforce-actions-panel">
            <div>
              <h2 id="ai-actions-title">Available actions</h2>
              <p>Only permission-backed actions valid for the selected server state are shown.</p>
            </div>
            {availableActions.length === 0 ? (
              <p className="workforce-no-actions">
                No governed actions are available for this account and selection.
              </p>
            ) : (
              <div className="workforce-actions">
                {availableActions.map((action) => (
                  <button
                    className="primary-button"
                    key={action.key}
                    type="button"
                    onClick={() => openAction(action)}
                  >
                    {action.label}
                  </button>
                ))}
              </div>
            )}
          </section>

          <p className="workforce-generated-at">
            Projection generated {new Date(projection.generatedAt).toLocaleString()}.
          </p>
        </>
      )}

      {activeAction && (
        <div className="workforce-dialog-backdrop">
          <section
            aria-labelledby="ai-action-title"
            aria-modal="true"
            className="workforce-dialog"
            role="dialog"
          >
            <header>
              <div>
                <span className="eyebrow">{profile.actionEyebrow}</span>
                <h2 id="ai-action-title">{activeAction.label}</h2>
              </div>
              <button
                aria-label="Close action"
                className="icon-button"
                disabled={submitting}
                type="button"
                onClick={() => setActiveAction(null)}
              >
                <X aria-hidden="true" size={18} />
              </button>
            </header>
            <p>{profile.actionGuidance}</p>
            {mutationIssue && (
              <div className="workforce-inline-issue" role="alert">
                <strong>{mutationIssue.title}</strong>
                <p>{mutationIssue.detail}</p>
                {mutationIssue.correlationId && (
                  <small>Reference: {mutationIssue.correlationId}</small>
                )}
              </div>
            )}
            <form onSubmit={submitAction}>
              <div className="workforce-action-fields">
                {activeAction.fields.map((field) => (
                  <ActionField
                    field={field}
                    key={field.key}
                    value={fields[field.key] ?? ''}
                    onChange={(value) =>
                      setFields((current) => ({ ...current, [field.key]: value }))
                    }
                  />
                ))}
                <label className="full-width">
                  Reason
                  <textarea
                    maxLength={500}
                    minLength={10}
                    required
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                  />
                </label>
              </div>
              <footer>
                <button
                  className="secondary-button"
                  disabled={submitting}
                  type="button"
                  onClick={() => setActiveAction(null)}
                >
                  Cancel
                </button>
                <button className="primary-button" disabled={submitting} type="submit">
                  {submitting && <LoaderCircle className="spin" aria-hidden="true" size={17} />}{' '}
                  Confirm action
                </button>
              </footer>
            </form>
          </section>
        </div>
      )}

      <nav aria-label={`${profile.evidenceLabel} screen pagination`} className="page-pagination">
        {previousId ? (
          <a
            className="pagination-link"
            href={contextHref(previousId, context, profile.recordParameter)}
          >
            <ArrowLeft aria-hidden="true" size={17} /> Previous
          </a>
        ) : (
          <span aria-disabled="true" className="pagination-link pagination-disabled">
            <ArrowLeft aria-hidden="true" size={17} /> Previous
          </span>
        )}
        <span className="pagination-status">
          {moduleNumber} of {profile.total}
        </span>
        {nextId ? (
          <a
            className="pagination-link pagination-next"
            href={contextHref(nextId, context, profile.recordParameter)}
          >
            Next <ArrowRight aria-hidden="true" size={17} />
          </a>
        ) : (
          <span
            aria-disabled="true"
            className="pagination-link pagination-next pagination-disabled"
          >
            Next <ArrowRight aria-hidden="true" size={17} />
          </span>
        )}
      </nav>
    </Shell>
  );
}
