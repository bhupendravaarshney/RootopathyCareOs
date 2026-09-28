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
import { useEffect, useRef, useState } from 'react';
import type { ApiFailure } from '../../api/client';
import type {
  AssessmentActionRequest,
  AssessmentRow,
  AssessmentScreen,
  WorkforceAction,
  WorkforceField,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { findScreen } from '../../data/screens';
import type { AssessmentClient } from './assessment-types';

type ScreenFilters = { q?: string; status?: string; limit: number };
type UiIssue = { title: string; detail: string; correlationId?: string; status?: number };
type WorkflowContext = {
  patientId: string | null;
  encounterId: string | null;
  assessmentSessionId: string | null;
};
type ActionSubmission = {
  fields: Record<string, string>;
  reason: string;
  targetId: string | null;
};

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function issueFromFailure(failure: ApiFailure): UiIssue {
  return {
    correlationId: failure.correlationId,
    detail: failure.problem.detail,
    status: failure.status,
    title: failure.problem.title,
  };
}

function emptyContext(): WorkflowContext {
  return { assessmentSessionId: null, encounterId: null, patientId: null };
}

function contextFromHash(): WorkflowContext {
  const query = window.location.hash.split('?', 2)[1];
  const parameters = new URLSearchParams(query ?? '');
  const context = emptyContext();
  for (const key of ['patientId', 'encounterId', 'assessmentSessionId'] as const) {
    const value = parameters.get(key);
    if (value && uuidPattern.test(value)) context[key] = value;
  }
  return context;
}

function contextHref(href: string, context: WorkflowContext) {
  const parameters = new URLSearchParams();
  for (const key of ['patientId', 'encounterId', 'assessmentSessionId'] as const) {
    if (context[key]) parameters.set(key, context[key]);
  }
  const query = parameters.toString();
  return query ? `${href}?${query}` : href;
}

function contextForRow(row: AssessmentRow, current: WorkflowContext) {
  return {
    assessmentSessionId: row.assessmentSessionId ?? current.assessmentSessionId,
    encounterId: row.encounterId ?? current.encounterId,
    patientId: row.patientId ?? current.patientId,
  };
}

function metricTone(tone: string) {
  return ['neutral', 'info', 'success', 'warning'].includes(tone) ? tone : 'neutral';
}

function statusTone(status: string) {
  if (['active', 'acknowledged', 'completed', 'confirmed', 'resolved', 'signed'].includes(status)) {
    return 'success';
  }
  if (['cancelled', 'entered_in_error', 'error', 'failed', 'refuted'].includes(status)) {
    return 'danger';
  }
  if (
    ['draft', 'in_progress', 'on_hold', 'planned', 'provisional', 'unacknowledged'].includes(status)
  ) {
    return 'warning';
  }
  return 'neutral';
}

function humanize(value: string) {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (character) => character.toUpperCase());
}

function idempotencyKey(actionKey: string) {
  if (!globalThis.crypto?.randomUUID) {
    throw new Error('Secure random identifiers are unavailable in this browser.');
  }
  return `m6:${actionKey}:${globalThis.crypto.randomUUID()}`;
}

function normalizedFields(action: WorkforceAction, values: Record<string, string>) {
  return Object.fromEntries(
    action.fields
      .map((field) => {
        const value = values[field.key]?.trim() ?? '';
        if (!value) return null;
        if (field.inputType === 'uuid' && !uuidPattern.test(value)) {
          throw new Error(`${field.label} must contain a valid UUID.`);
        }
        if (field.inputType === 'datetime-local') {
          const instant = new Date(value);
          if (!Number.isFinite(instant.getTime())) {
            throw new Error(`${field.label} must contain a valid date and time.`);
          }
          return [field.key, instant.toISOString()];
        }
        return [field.key, value.normalize('NFC')];
      })
      .filter((entry): entry is [string, string] => entry !== null),
  );
}

function fieldDefault(field: WorkforceField, selected: AssessmentRow | undefined) {
  if (!selected) return field.options[0]?.value ?? '';
  if (field.key.endsWith('PractitionerId')) {
    return selected.values.responsiblePractitionerId ?? '';
  }
  return selected.values[field.key] ?? field.options[0]?.value ?? '';
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
  if (field.inputType === 'hidden') {
    return <input name={field.key} readOnly type="hidden" value={value} />;
  }
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
        {field.help && <small>{field.help}</small>}
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
        {field.help && <small>{field.help}</small>}
      </label>
    );
  }
  if (field.inputType === 'checkbox') {
    return (
      <label className="checkbox-row">
        <input
          checked={value === 'true'}
          type="checkbox"
          onChange={(event) => onChange(event.target.checked ? 'true' : '')}
        />
        <span>{field.label}</span>
        {field.help && <small>{field.help}</small>}
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
      {field.help && <small>{field.help}</small>}
    </label>
  );
}

function ActionDialog({
  action,
  busy,
  issue,
  onClose,
  onSubmit,
  selected,
}: {
  action: WorkforceAction;
  busy: boolean;
  issue: UiIssue | null;
  onClose(): void;
  onSubmit(submission: ActionSubmission): Promise<void>;
  selected: AssessmentRow | undefined;
}) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const busyRef = useRef(busy);
  const onCloseRef = useRef(onClose);
  const onSubmitRef = useRef(onSubmit);
  const lastAutosave = useRef<string | null>(null);
  const [targetId] = useState(selected?.id ?? '');
  const [reason, setReason] = useState('');
  const [autosaveStatus, setAutosaveStatus] = useState<'idle' | 'saving'>('idle');
  const [autosaveAttemptedFingerprint, setAutosaveAttemptedFingerprint] = useState<string | null>(
    null,
  );
  const [fields, setFields] = useState<Record<string, string>>(() =>
    Object.fromEntries(action.fields.map((field) => [field.key, fieldDefault(field, selected)])),
  );
  const needsSelectedRevision = action.targetRequired && !selected;
  const autosaveReady =
    action.key === 'save-section-response' &&
    !busy &&
    !needsSelectedRevision &&
    action.fields.every((field) => {
      if (!field.required) return true;
      const value = fields[field.key]?.trim() ?? '';
      return value.length > 0 && (field.inputType !== 'uuid' || uuidPattern.test(value));
    });
  const autosaveFingerprint = JSON.stringify({ fields, reason, targetId });

  useEffect(() => {
    busyRef.current = busy;
    onCloseRef.current = onClose;
    onSubmitRef.current = onSubmit;
  }, [busy, onClose, onSubmit]);

  useEffect(() => {
    if (!autosaveReady || lastAutosave.current === autosaveFingerprint) return;
    const timer = window.setTimeout(() => {
      lastAutosave.current = autosaveFingerprint;
      setAutosaveAttemptedFingerprint(autosaveFingerprint);
      setAutosaveStatus('saving');
      void onSubmitRef.current({ fields, reason, targetId }).finally(() => {
        setAutosaveStatus('idle');
      });
    }, 700);
    return () => window.clearTimeout(timer);
  }, [autosaveFingerprint, autosaveReady, fields, reason, targetId]);

  useEffect(() => {
    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const focusable = Array.from(
        dialog.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled])',
        ) ?? [],
      );
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.current?.focus();
        return;
      }
      const first = focusable[0]!;
      const last = focusable[focusable.length - 1]!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previouslyFocused?.focus();
    };
  }, [action.key]);

  return (
    <div className="workforce-dialog-backdrop">
      <section
        aria-labelledby="assessment-action-title"
        aria-modal="true"
        className="workforce-dialog"
        ref={dialog}
        role="dialog"
        tabIndex={-1}
      >
        <header>
          <div>
            <span className="eyebrow">Governed assessment action</span>
            <h2 id="assessment-action-title">{action.label}</h2>
          </div>
          <button
            aria-label="Close action"
            className="icon-button"
            disabled={busy}
            onClick={onClose}
            ref={closeButton}
            type="button"
          >
            <X aria-hidden="true" size={20} />
          </button>
        </header>

        {needsSelectedRevision && (
          <div className="workforce-inline-issue" role="alert">
            <AlertCircle aria-hidden="true" size={18} />
            <p>Select a current server record before opening this revision-controlled action.</p>
          </div>
        )}

        <form
          onSubmit={(event) => {
            event.preventDefault();
            void onSubmit({
              fields,
              reason,
              targetId: action.targetRequired ? targetId : null,
            });
          }}
        >
          {action.targetRequired && (
            <label className="full-width">
              Selected record
              <input autoComplete="off" readOnly required value={targetId} />
              <small>
                {selected
                  ? `${selected.values.primary ?? selected.id} · ${humanize(selected.status)}`
                  : 'Select an authorized assessment record.'}
              </small>
            </label>
          )}

          <div className="workforce-action-fields">
            {action.fields.map((field) => (
              <ActionField
                field={field}
                key={field.key}
                value={fields[field.key] ?? ''}
                onChange={(value) => {
                  setAutosaveStatus('idle');
                  setFields((current) => ({ ...current, [field.key]: value }));
                }}
              />
            ))}
          </div>

          <label className="full-width">
            Reason{action.reasonRequired ? '' : ' (optional)'}
            <textarea
              maxLength={500}
              minLength={10}
              required={action.reasonRequired}
              value={reason}
              onChange={(event) => {
                setAutosaveStatus('idle');
                setReason(event.target.value);
              }}
            />
            <small>Use 10–500 characters. Reasons become governed evidence.</small>
          </label>

          {action.key === 'save-section-response' && (
            <div className="workforce-inline-issue" role="status">
              {autosaveStatus === 'saving' ? (
                <LoaderCircle className="spin" aria-hidden="true" size={18} />
              ) : (
                <ShieldCheck aria-hidden="true" size={18} />
              )}
              <div>
                <strong>
                  {autosaveStatus === 'saving'
                    ? 'Saving draft…'
                    : autosaveReady && autosaveAttemptedFingerprint !== autosaveFingerprint
                      ? 'Autosave queued'
                      : 'Conflict-aware autosave'}
                </strong>
                <p>
                  Complete the required fields and pause for 700 ms. CareOS saves against the latest
                  server revision and never overwrites a conflicting update.
                </p>
              </div>
            </div>
          )}

          {issue && (
            <div className="workforce-inline-issue" role="alert">
              <AlertCircle aria-hidden="true" size={18} />
              <div>
                <strong>{issue.title}</strong>
                <p>{issue.detail}</p>
                {issue.correlationId && <small>Reference: {issue.correlationId}</small>}
              </div>
            </div>
          )}

          <footer>
            <button className="secondary-button" disabled={busy} onClick={onClose} type="button">
              Cancel
            </button>
            <button
              aria-busy={busy}
              className="primary-button"
              disabled={busy || needsSelectedRevision}
              type="submit"
            >
              {busy && <LoaderCircle className="spin" aria-hidden="true" size={17} />}
              {busy
                ? 'Submitting…'
                : action.key === 'save-section-response'
                  ? 'Save now'
                  : action.label}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

export function AssessmentScreenPage({
  client,
  id,
  organizationId,
  shell,
}: {
  client: AssessmentClient;
  id: string;
  organizationId: string;
  shell: ShellSessionProps;
}) {
  const registered = findScreen(id);
  const initialContext = contextFromHash();
  const [context, setContext] = useState<WorkflowContext>(initialContext);
  const [contextDraft, setContextDraft] = useState<WorkflowContext>(initialContext);
  const [projection, setProjection] = useState<AssessmentScreen | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadIssue, setLoadIssue] = useState<UiIssue | null>(null);
  const [mutationIssue, setMutationIssue] = useState<UiIssue | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [activeAction, setActiveAction] = useState<WorkforceAction | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [queryDraft, setQueryDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [filters, setFilters] = useState<ScreenFilters>({ limit: 25 });
  const [cursorStack, setCursorStack] = useState<string[]>([]);
  const [refresh, setRefresh] = useState(0);
  const lastAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const activeCursor = cursorStack.at(-1);
    const load = async () => {
      await Promise.resolve();
      if (controller.signal.aborted) return;
      setLoading(true);
      setLoadIssue(null);
      setSuccess(null);
      const result = await client.getAssessmentScreen(
        organizationId,
        id,
        {
          ...(context.assessmentSessionId
            ? { assessmentSessionId: context.assessmentSessionId }
            : {}),
          ...(context.encounterId ? { encounterId: context.encounterId } : {}),
          ...(context.patientId ? { patientId: context.patientId } : {}),
          limit: filters.limit,
          q: filters.q,
          status: filters.status,
          ...(activeCursor ? { cursor: activeCursor } : {}),
        },
        { signal: controller.signal },
      );
      if (controller.signal.aborted) return;
      if (!result.ok) {
        if (activeCursor && (result.status === 400 || result.status === 409)) {
          setCursorStack([]);
          setSuccess('The result set changed or the page cursor expired. Pagination restarted.');
          return;
        }
        setLoadIssue(issueFromFailure(result));
        setProjection(null);
        return;
      }
      setProjection(result.data);
      setSelectedId((current) =>
        current && result.data.rows.some((row) => row.id === current) ? current : null,
      );
    };
    void load().finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [client, context, cursorStack, filters, id, organizationId, refresh]);

  const selected = projection?.rows.find((row) => row.id === selectedId);
  const availableActions =
    projection?.actions.filter(
      (action) =>
        action.style === 'link' ||
        !selected ||
        !action.targetRequired ||
        selected.allowedActionKeys.includes(action.key),
    ) ?? [];
  const moduleNumber = Number(id.slice(4));
  const previousId = moduleNumber > 1 ? `COS-${String(moduleNumber - 1).padStart(2, '0')}` : null;
  const nextId = moduleNumber < 27 ? `COS-${String(moduleNumber + 1).padStart(2, '0')}` : null;

  function applyRowContext(row: AssessmentRow) {
    const nextContext = contextForRow(row, context);
    setContext(nextContext);
    setContextDraft(nextContext);
  }

  async function submitAction(submission: ActionSubmission) {
    if (!activeAction) return;
    setMutationIssue(null);
    setSuccess(null);
    try {
      const fields = normalizedFields(activeAction, submission.fields);
      const targetId = submission.targetId?.trim() || null;
      if (activeAction.targetRequired && (!targetId || !uuidPattern.test(targetId))) {
        throw new Error('Select a record with a valid target UUID.');
      }
      if (activeAction.ifMatchRequired && targetId !== selected?.id) {
        throw new Error('The selected server revision no longer matches this action.');
      }
      const reason = submission.reason.trim().normalize('NFC');
      if (reason && (reason.length < 10 || reason.length > 500)) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      if (activeAction.reasonRequired && !reason) {
        throw new Error('A reason is required for this action.');
      }
      const body: AssessmentActionRequest = {
        assessmentSessionId: selected?.assessmentSessionId ?? context.assessmentSessionId,
        encounterId: selected?.encounterId ?? context.encounterId,
        fields,
        patientId: selected?.patientId ?? context.patientId,
        reason: reason || null,
        targetId,
      };
      const fingerprint = JSON.stringify({ action: activeAction.key, body, screen: id });
      const key =
        lastAttempt.current?.fingerprint === fingerprint
          ? lastAttempt.current.key
          : idempotencyKey(activeAction.key);
      lastAttempt.current = { fingerprint, key };
      setSubmitting(true);
      const result = await client.performAssessmentAction(
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
      const nextSelected =
        result.data.rows.find((row) => row.id === selectedId) ??
        (result.data.rows.length === 1 ? result.data.rows[0] : undefined);
      setSelectedId(nextSelected?.id ?? null);
      if (nextSelected) applyRowContext(nextSelected);
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

  const visibleColumns = projection?.columns.filter((column) => column.key !== 'status') ?? [];

  return (
    <Shell currentId={id} {...shell}>
      <div className="page-head workforce-page-head">
        <div>
          <span className="eyebrow">{id}</span>
          <h1>{projection?.title ?? registered.title}</h1>
          <p>{projection?.purpose ?? registered.purpose}</p>
        </div>
        <span className="badge success">
          <ShieldCheck aria-hidden="true" size={14} /> Server governed
        </span>
      </div>

      {success && (
        <div className="workforce-success" role="status">
          <CheckCircle2 aria-hidden="true" size={19} />
          <span>{success}</span>
        </div>
      )}

      {selected && (
        <section aria-label="Verified patient context" className="panel workforce-actions-panel">
          <div>
            <span className="eyebrow">Verified patient</span>
            <h2>{selected.values.primary ?? 'Authorized patient'}</h2>
            <p>
              {selected.values.secondary ?? 'Identifier restricted'} · Verification:{' '}
              {humanize(selected.values.patientVerification ?? 'not_recorded')}
            </p>
          </div>
          <div>
            <strong>{selected.values.clinician ?? 'Responsible clinician not projected'}</strong>
            <p>
              Completion {selected.values.completion ?? '0/27'} · Patient safety flags{' '}
              {selected.values.patientAlerts ?? '0'} · Open assessment red flags{' '}
              {selected.values.openRedFlags ?? '0'}
            </p>
          </div>
        </section>
      )}

      <details className="panel workforce-actions-panel">
        <summary>Workflow context</summary>
        <p>Carry an authorized patient, encounter and assessment session through the workflow.</p>
        <form
          className="workforce-filters"
          onSubmit={(event) => {
            event.preventDefault();
            for (const [key, value] of Object.entries(contextDraft)) {
              if (value && !uuidPattern.test(value)) {
                setLoadIssue({
                  detail: `${humanize(key)} must contain a valid UUID.`,
                  title: 'Invalid workflow context',
                });
                return;
              }
            }
            setContext(contextDraft);
            setSelectedId(null);
            setCursorStack([]);
          }}
        >
          {(['patientId', 'encounterId', 'assessmentSessionId'] as const).map((key) => (
            <label key={key}>
              {humanize(key)}
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
              onClick={() => {
                const cleared = emptyContext();
                setContext(cleared);
                setContextDraft(cleared);
                setSelectedId(null);
                setCursorStack([]);
              }}
              type="button"
            >
              Clear
            </button>
          </div>
        </form>
      </details>

      {loading && (
        <section aria-live="polite" className="panel workforce-state">
          <LoaderCircle className="spin" aria-hidden="true" size={24} />
          <h2>Loading authorized assessment data…</h2>
          <p>CareOS is checking the organization, permission and minimum projection.</p>
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
            onClick={() => setRefresh((value) => value + 1)}
            type="button"
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
            <section aria-label="Assessment metrics" className="workforce-metrics">
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
              aria-label="Assessment record filters"
              className="workforce-filters"
              onSubmit={(event) => {
                event.preventDefault();
                const query = queryDraft.trim();
                if (query.length === 1) {
                  setLoadIssue({
                    detail: 'Assessment searches require at least two characters.',
                    title: 'Search is too short',
                  });
                  return;
                }
                setFilters({
                  limit: 25,
                  ...(query ? { q: query } : {}),
                  ...(statusDraft.trim() ? { status: statusDraft.trim() } : {}),
                });
                setCursorStack([]);
              }}
            >
              <label>
                Search
                <span className="workforce-search-input">
                  <Search aria-hidden="true" size={17} />
                  <input
                    maxLength={100}
                    placeholder="Patient number or service"
                    value={queryDraft}
                    onChange={(event) => setQueryDraft(event.target.value)}
                  />
                </span>
              </label>
              <label>
                Status
                <input
                  maxLength={120}
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
                  disabled={!queryDraft && !statusDraft && !filters.q && !filters.status}
                  onClick={() => {
                    setQueryDraft('');
                    setStatusDraft('');
                    setFilters({ limit: 25 });
                    setCursorStack([]);
                  }}
                  type="button"
                >
                  Clear
                </button>
              </div>
            </form>

            <div className="workforce-table-heading">
              <div>
                <h2>Authorized assessment records</h2>
                <p aria-live="polite">
                  {projection.rows.length} record{projection.rows.length === 1 ? '' : 's'} returned
                </p>
              </div>
              <button
                aria-label="Refresh assessment records"
                className="icon-button"
                onClick={() => setRefresh((value) => value + 1)}
                type="button"
              >
                <RefreshCw aria-hidden="true" size={18} />
              </button>
            </div>

            {projection.rows.length === 0 ? (
              <div className="workforce-empty">
                <Search aria-hidden="true" size={25} />
                <h3>No authorized records found</h3>
                <p>Adjust the filters, apply a workflow context or use an available action.</p>
              </div>
            ) : (
              <div
                aria-label={`${projection.title} records`}
                className="table-wrap workforce-table-wrap"
                role="region"
                tabIndex={0}
              >
                <table className="workforce-table">
                  <caption className="sr-only">{projection.title} authorized records</caption>
                  <thead>
                    <tr>
                      <th>
                        <span className="sr-only">Select</span>
                      </th>
                      {visibleColumns.map((column) => (
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
                            aria-label={`Select ${row.values.primary ?? row.id}`}
                            checked={selectedId === row.id}
                            name="assessment-record"
                            type="radio"
                            onChange={() => {
                              setSelectedId(row.id);
                              applyRowContext(row);
                            }}
                          />
                        </td>
                        {visibleColumns.map((column) => (
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

            <div aria-label="Assessment result pages" className="workforce-cursor-pager">
              <button
                className="secondary-button"
                disabled={cursorStack.length === 0}
                onClick={() => setCursorStack((current) => current.slice(0, -1))}
                type="button"
              >
                <ArrowLeft aria-hidden="true" size={16} /> Previous
              </button>
              <span>
                Page {cursorStack.length + 1} · up to {projection.pageSize} records
              </span>
              <button
                className="secondary-button"
                disabled={!projection.nextCursor}
                onClick={() => {
                  if (projection.nextCursor) {
                    setCursorStack((current) => [...current, projection.nextCursor!]);
                  }
                }}
                type="button"
              >
                Next <ArrowRight aria-hidden="true" size={16} />
              </button>
            </div>
          </section>

          <section
            aria-labelledby="assessment-actions-title"
            className="panel workforce-actions-panel"
          >
            <div>
              <h2 id="assessment-actions-title">Available actions</h2>
              <p>
                CareOS combines live permission with the selected record’s server-projected state.
              </p>
            </div>
            {availableActions.length === 0 ? (
              <p className="workforce-no-actions">
                No governed actions are available for this account and selection.
              </p>
            ) : (
              <div className="workforce-actions">
                {availableActions.map((action) =>
                  action.style === 'link' && action.href ? (
                    <a
                      className="secondary-button"
                      href={contextHref(action.href, context)}
                      key={action.key}
                    >
                      {action.label} <ArrowRight aria-hidden="true" size={16} />
                    </a>
                  ) : (
                    <button
                      className="primary-button"
                      key={action.key}
                      onClick={() => {
                        setMutationIssue(null);
                        setActiveAction(action);
                      }}
                      type="button"
                    >
                      {action.label}
                    </button>
                  ),
                )}
              </div>
            )}
          </section>

          <p className="workforce-generated-at">
            Server projection generated {new Date(projection.generatedAt).toLocaleString()}.
          </p>
        </>
      )}

      <nav aria-label="Assessment screen pagination" className="page-pagination">
        {previousId ? (
          <a className="pagination-link" href={contextHref(`#/${previousId}`, context)}>
            <ArrowLeft /> {previousId}
          </a>
        ) : (
          <span aria-disabled="true" className="pagination-link pagination-disabled">
            <ArrowLeft /> COS-01
          </span>
        )}
        <span className="pagination-status">{moduleNumber} of 27</span>
        {nextId ? (
          <a className="pagination-link pagination-next" href={contextHref(`#/${nextId}`, context)}>
            {nextId} <ArrowRight />
          </a>
        ) : (
          <span
            aria-disabled="true"
            className="pagination-link pagination-next pagination-disabled"
          >
            COS-27 <ArrowRight />
          </span>
        )}
      </nav>

      {activeAction && (
        <ActionDialog
          action={activeAction}
          busy={submitting}
          issue={mutationIssue}
          selected={selected}
          onClose={() => {
            if (!submitting) {
              setActiveAction(null);
              setMutationIssue(null);
            }
          }}
          onSubmit={submitAction}
        />
      )}
    </Shell>
  );
}
