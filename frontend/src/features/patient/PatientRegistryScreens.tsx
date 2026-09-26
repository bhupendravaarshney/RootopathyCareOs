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
import type { PatientRegistryImpactPreviewResponse } from '../../api/patient-contracts';
import type {
  PatientRegistryActionRequest,
  PatientRegistryRow,
  PatientRegistryScreen,
  WorkforceAction,
  WorkforceField,
  WorkforceOption,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { findScreen } from '../../data/screens';
import type { PatientRegistryClient } from './patient-types';

type ScreenFilters = { q?: string; status?: string; limit: number };
type UiIssue = { title: string; detail: string; correlationId?: string; status?: number };
type ActionSubmission = {
  fields: Record<string, string>;
  impactToken?: string;
  reason: string;
  targetId: string | null;
};

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const impactActions = new Set(['request-patient-merge', 'execute-patient-merge']);

function issueFromFailure(failure: ApiFailure): UiIssue {
  return {
    correlationId: failure.correlationId,
    detail: failure.problem.detail,
    status: failure.status,
    title: failure.problem.title,
  };
}

function metricTone(tone: string) {
  return ['neutral', 'info', 'success', 'warning'].includes(tone) ? tone : 'neutral';
}

function statusTone(status: string) {
  if (['active', 'approved', 'complete', 'completed', 'verified', 'ready'].includes(status)) {
    return 'success';
  }
  if (
    [
      'rejected',
      'failed',
      'expired',
      'revoked',
      'suspended',
      'blocked',
      'entered_in_error',
    ].includes(status)
  ) {
    return 'danger';
  }
  if (['submitted', 'pending', 'under_review', 'duplicate_review', 'collecting'].includes(status)) {
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
  return `m3:${actionKey}:${globalThis.crypto.randomUUID()}`;
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

function fieldDefault(field: WorkforceField, selected: PatientRegistryRow | undefined) {
  if (!selected) return field.options[0]?.value ?? '';
  const direct = selected.values[field.key];
  if (direct) return direct;
  if (field.key === 'survivorPatientId') {
    return selected.values.patientAId ?? '';
  }
  if (field.key === 'duplicatePatientId') {
    return selected.values.patientBId ?? '';
  }
  return field.options[0]?.value ?? '';
}

function projectedField(
  field: WorkforceField,
  rows: PatientRegistryRow[],
  selected: PatientRegistryRow | undefined,
): WorkforceField {
  if (field.key === 'selectedPatientId') {
    const options = rows
      .filter((row) => row.id !== selected?.id && row.patientId === row.id)
      .map<WorkforceOption>((row) => ({
        label: `${row.values.primary ?? 'Patient'} · ${row.values.secondary ?? row.id}`,
        value: row.id,
      }));
    if (options.length > 0) return { ...field, inputType: 'select', options };
  }
  if (field.key === 'survivorPatientId' || field.key === 'duplicatePatientId') {
    const options = [
      selected?.values.patientAId
        ? {
            label: `First candidate · ${selected.values.patientAId}`,
            value: selected.values.patientAId,
          }
        : null,
      selected?.values.patientBId
        ? {
            label: `Second candidate · ${selected.values.patientBId}`,
            value: selected.values.patientBId,
          }
        : null,
    ].filter((option): option is WorkforceOption => option !== null);
    if (options.length > 0) return { ...field, inputType: 'select', options };
  }
  return field;
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
  return (
    <label>
      {field.label}
      <input
        autoComplete="off"
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
  onPreview,
  onSubmit,
  rows,
  selected,
}: {
  action: WorkforceAction;
  busy: boolean;
  issue: UiIssue | null;
  onClose(): void;
  onPreview(submission: ActionSubmission): Promise<PatientRegistryImpactPreviewResponse | null>;
  onSubmit(submission: ActionSubmission): Promise<void>;
  rows: PatientRegistryRow[];
  selected: PatientRegistryRow | undefined;
}) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const busyRef = useRef(busy);
  const onCloseRef = useRef(onClose);
  const [targetId, setTargetId] = useState(selected?.id ?? '');
  const [reason, setReason] = useState('');
  const [impactPreview, setImpactPreview] = useState<PatientRegistryImpactPreviewResponse | null>(
    null,
  );
  const currentImpactPreview = issue ? null : impactPreview;
  const [fields, setFields] = useState<Record<string, string>>(() =>
    Object.fromEntries(action.fields.map((field) => [field.key, fieldDefault(field, selected)])),
  );

  useEffect(() => {
    busyRef.current = busy;
    onCloseRef.current = onClose;
  }, [busy, onClose]);

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

  const needsSelectedRevision = action.ifMatchRequired && !selected;

  return (
    <div className="workforce-dialog-backdrop">
      <section
        aria-labelledby="patient-action-title"
        aria-modal="true"
        className="workforce-dialog"
        ref={dialog}
        role="dialog"
        tabIndex={-1}
      >
        <header>
          <div>
            <span className="eyebrow">Governed patient action</span>
            <h2 id="patient-action-title">{action.label}</h2>
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
            const submission: ActionSubmission = {
              fields,
              reason,
              targetId: action.targetRequired ? targetId : null,
            };
            if (impactActions.has(action.key) && currentImpactPreview === null) {
              void onPreview(submission).then((preview) => {
                if (preview) setImpactPreview(preview);
              });
              return;
            }
            void onSubmit({
              ...submission,
              ...(currentImpactPreview ? { impactToken: currentImpactPreview.token } : {}),
            });
          }}
        >
          {action.targetRequired && (
            <label className="full-width">
              {selected ? 'Selected record' : 'Target patient record ID'}
              <input
                autoComplete="off"
                pattern="[0-9a-fA-F-]{36}"
                readOnly={Boolean(selected)}
                required
                value={targetId}
                onChange={(event) => {
                  setTargetId(event.target.value);
                  setImpactPreview(null);
                }}
              />
              <small>
                {selected
                  ? `${selected.values.primary ?? 'Record'} · ${humanize(selected.status)}`
                  : 'Use the UUID from an authorized CareOS patient record.'}
              </small>
            </label>
          )}

          <div className="workforce-action-fields">
            {action.fields.map((field) => {
              const projected = projectedField(field, rows, selected);
              return (
                <ActionField
                  field={projected}
                  key={field.key}
                  value={fields[field.key] ?? ''}
                  onChange={(value) => {
                    setFields((current) => ({ ...current, [field.key]: value }));
                    setImpactPreview(null);
                  }}
                />
              );
            })}
          </div>

          <label className="full-width">
            Reason{action.reasonRequired ? '' : ' (optional)'}
            <textarea
              maxLength={500}
              minLength={10}
              required={action.reasonRequired}
              value={reason}
              onChange={(event) => {
                setReason(event.target.value);
                setImpactPreview(null);
              }}
            />
            <small>Use 10–500 characters. Reasons become governed evidence.</small>
          </label>

          {currentImpactPreview && (
            <section aria-labelledby="patient-impact-title" className="workforce-impact-preview">
              <header>
                <div>
                  <span className="eyebrow">Fresh server impact</span>
                  <h3 id="patient-impact-title">Review before confirmation</h3>
                </div>
                <span className={`badge ${currentImpactPreview.blocked ? 'danger' : 'warning'}`}>
                  {currentImpactPreview.blocked ? 'Blocked' : 'Expires soon'}
                </span>
              </header>
              <ul>
                {currentImpactPreview.items.map((item) => (
                  <li className={item.tone} key={item.code}>
                    <strong>{humanize(item.code)}</strong>
                    <span>{item.detail}</span>
                    <small>{item.affectedCount} affected</small>
                  </li>
                ))}
              </ul>
              <p>
                Digest <code>{currentImpactPreview.digest.slice(0, 12)}…</code> · expires{' '}
                {new Date(currentImpactPreview.expiresAt).toLocaleTimeString()}
              </p>
            </section>
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
              disabled={busy || needsSelectedRevision || Boolean(currentImpactPreview?.blocked)}
              type="submit"
            >
              {busy && <LoaderCircle className="spin" aria-hidden="true" size={17} />}
              {busy
                ? impactActions.has(action.key) && currentImpactPreview === null
                  ? 'Reviewing impact…'
                  : 'Submitting…'
                : impactActions.has(action.key) && currentImpactPreview === null
                  ? 'Review impact'
                  : action.label}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

function patientIdFor(row: PatientRegistryRow | undefined) {
  if (!row) return null;
  if (row.patientId && uuidPattern.test(row.patientId)) return row.patientId;
  const projected = row.values.patientId;
  return projected && uuidPattern.test(projected) ? projected : null;
}

function registrationIdFor(screenId: string, row: PatientRegistryRow | undefined) {
  if (!row) return null;
  const projected = row.values.registrationId;
  if (projected && uuidPattern.test(projected)) return projected;
  return ['P3-03', 'P3-04', 'P3-12'].includes(screenId) ? row.id : null;
}

const patientContextScreens = new Set([
  'P3-06',
  'P3-07',
  'P3-08',
  'P3-09',
  'P3-10',
  'P3-11',
  'P3-13',
  'P3-16',
]);

export function PatientRegistryScreenPage({
  client,
  id,
  organizationId,
  shell,
}: {
  client: PatientRegistryClient;
  id: string;
  organizationId: string;
  shell: ShellSessionProps;
}) {
  const registered = findScreen(id);
  const [projection, setProjection] = useState<PatientRegistryScreen | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadIssue, setLoadIssue] = useState<UiIssue | null>(null);
  const [mutationIssue, setMutationIssue] = useState<UiIssue | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [activeAction, setActiveAction] = useState<WorkforceAction | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [patientContextId, setPatientContextId] = useState<string | null>(null);
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
      const result = await client.getPatientRegistryScreen(
        organizationId,
        id,
        {
          ...(patientContextId ? { patientId: patientContextId } : {}),
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
  }, [client, cursorStack, filters, id, organizationId, patientContextId, refresh]);

  const selected = projection?.rows.find((row) => row.id === selectedId);
  const availableActions =
    projection?.actions.filter(
      (action) =>
        action.style === 'link' ||
        !selected ||
        !action.targetRequired ||
        selected.allowedActionKeys.includes(action.key),
    ) ?? [];
  const moduleNumber = Number(id.slice(3));
  const previousId = moduleNumber > 1 ? `P3-${String(moduleNumber - 1).padStart(2, '0')}` : null;
  const nextId = moduleNumber < 16 ? `P3-${String(moduleNumber + 1).padStart(2, '0')}` : null;

  async function requestImpactPreview(
    submission: ActionSubmission,
  ): Promise<PatientRegistryImpactPreviewResponse | null> {
    if (!activeAction || !selected || !impactActions.has(activeAction.key)) return null;
    setMutationIssue(null);
    setSuccess(null);
    try {
      const fields = normalizedFields(activeAction, submission.fields);
      const targetId = submission.targetId?.trim() || null;
      if (!targetId || !uuidPattern.test(targetId) || targetId !== selected.id) {
        throw new Error('Select the current server record before reviewing impact.');
      }
      const reason = submission.reason.trim().normalize('NFC');
      if (reason.length < 10 || reason.length > 500) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      setSubmitting(true);
      const result = await client.previewPatientRegistryImpact(
        organizationId,
        id,
        activeAction.key,
        {
          decision: fields.decisionCode ?? null,
          fields,
          patientId: patientIdFor(selected),
          registrationId: registrationIdFor(id, selected),
          reason,
          targetId,
        },
        selected.etag,
        selected.revision,
      );
      if (!result.ok) {
        setMutationIssue(issueFromFailure(result));
        return null;
      }
      return result.data;
    } catch (error) {
      setMutationIssue({
        detail: error instanceof Error ? error.message : 'The impact could not be reviewed.',
        title: 'Impact review unavailable',
      });
      return null;
    } finally {
      setSubmitting(false);
    }
  }

  async function submitAction(submission: ActionSubmission) {
    if (!activeAction) return;
    setMutationIssue(null);
    setSuccess(null);
    try {
      const fields = normalizedFields(activeAction, submission.fields);
      const targetId = submission.targetId?.trim() || null;
      if (activeAction.targetRequired && (!targetId || !uuidPattern.test(targetId))) {
        throw new Error('Select a record or provide a valid target UUID.');
      }
      if (activeAction.ifMatchRequired && targetId !== selected?.id) {
        throw new Error('The selected server revision no longer matches this action.');
      }
      if (fields.dispositionCode === 'use_existing' && !fields.selectedPatientId) {
        throw new Error('Select the existing patient for this registration disposition.');
      }
      const reason = submission.reason.trim().normalize('NFC');
      if (reason && (reason.length < 10 || reason.length > 500)) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      if (activeAction.reasonRequired && !reason) {
        throw new Error('A reason is required for this action.');
      }
      const body: PatientRegistryActionRequest = {
        decision: fields.decisionCode ?? fields.dispositionCode ?? null,
        fields,
        ...(submission.impactToken ? { impactToken: submission.impactToken } : {}),
        patientId: patientIdFor(selected),
        registrationId: registrationIdFor(id, selected),
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
      const result = await client.performPatientRegistryAction(
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
      setSelectedId((current) => {
        if (current && result.data.rows.some((row) => row.id === current)) return current;
        return result.data.rows.length === 1 ? result.data.rows[0]!.id : null;
      });
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

      {loading && (
        <section aria-live="polite" className="panel workforce-state">
          <LoaderCircle className="spin" aria-hidden="true" size={24} />
          <h2>Loading authorized patient data…</h2>
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
            <section aria-label="Patient registry metrics" className="workforce-metrics">
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
              aria-label="Patient record filters"
              className="workforce-filters"
              onSubmit={(event) => {
                event.preventDefault();
                const query = queryDraft.trim();
                if (query.length === 1) {
                  setLoadIssue({
                    detail: 'Patient searches require at least two characters.',
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
                    placeholder="Name, patient number or record"
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
                <h2>Authorized patient records</h2>
                <p aria-live="polite">
                  {projection.rows.length} record{projection.rows.length === 1 ? '' : 's'} returned
                  {patientContextId ? ' for the selected patient context' : ''}
                </p>
              </div>
              <div className="workforce-filter-actions">
                {patientContextId && (
                  <button
                    className="text-action"
                    onClick={() => {
                      setPatientContextId(null);
                      setSelectedId(null);
                      setCursorStack([]);
                    }}
                    type="button"
                  >
                    All patients
                  </button>
                )}
                <button
                  aria-label="Refresh patient records"
                  className="icon-button"
                  onClick={() => setRefresh((value) => value + 1)}
                  type="button"
                >
                  <RefreshCw aria-hidden="true" size={18} />
                </button>
              </div>
            </div>

            {projection.rows.length === 0 ? (
              <div className="workforce-empty">
                <Search aria-hidden="true" size={25} />
                <h3>No authorized records found</h3>
                <p>Adjust the filters or use an available registration action.</p>
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
                            name="patient-record"
                            type="radio"
                            onChange={() => {
                              setSelectedId(row.id);
                              const patientId = patientIdFor(row);
                              if (
                                patientContextScreens.has(id) &&
                                patientId === row.id &&
                                patientId !== patientContextId
                              ) {
                                setPatientContextId(patientId);
                                setCursorStack([]);
                              }
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

            <div aria-label="Patient result pages" className="workforce-cursor-pager">
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
            aria-labelledby="patient-actions-title"
            className="panel workforce-actions-panel"
          >
            <div>
              <h2 id="patient-actions-title">Available actions</h2>
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
                    <a className="secondary-button" href={action.href} key={action.key}>
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

      <nav aria-label="Patient registry screen pagination" className="page-pagination">
        {previousId ? (
          <a className="pagination-link" href={`#/${previousId}`}>
            <ArrowLeft /> {previousId}
          </a>
        ) : (
          <span aria-disabled="true" className="pagination-link pagination-disabled">
            <ArrowLeft /> P3-01
          </span>
        )}
        <span className="pagination-status">{moduleNumber} of 16</span>
        {nextId ? (
          <a className="pagination-link pagination-next" href={`#/${nextId}`}>
            {nextId} <ArrowRight />
          </a>
        ) : (
          <span
            aria-disabled="true"
            className="pagination-link pagination-next pagination-disabled"
          >
            P3-16 <ArrowRight />
          </span>
        )}
      </nav>

      {activeAction && (
        <ActionDialog
          action={activeAction}
          busy={submitting}
          issue={mutationIssue}
          rows={projection?.rows ?? []}
          selected={selected}
          onClose={() => {
            if (!submitting) {
              setActiveAction(null);
              setMutationIssue(null);
            }
          }}
          onPreview={requestImpactPreview}
          onSubmit={submitAction}
        />
      )}
    </Shell>
  );
}
