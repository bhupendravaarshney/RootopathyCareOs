import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  ExternalLink,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldCheck,
  X,
} from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { ApiFailure } from '../../api/client';
import type {
  DocumentActionRequest,
  DocumentRow,
  DocumentScreen,
  DocumentUploadMetadata,
  WorkforceAction,
  WorkforceField,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { LocalDemoData } from '../../components/LocalDemoData';
import { isLocalDemoOrganization } from '../../data/local-demo';
import { findScreen } from '../../data/screens';
import type { DocumentClient } from './document-types';

type ScreenFilters = { q?: string; status?: string; limit: number };
type UiIssue = { title: string; detail: string; correlationId?: string; status?: number };
type WorkflowContext = {
  patientId: string | null;
  documentId: string | null;
  diagnosticReportId: string | null;
};
type ActionSubmission = {
  fields: Record<string, string>;
  file: File | null;
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
  return { diagnosticReportId: null, documentId: null, patientId: null };
}

function contextFromHash(): WorkflowContext {
  const query = window.location.hash.split('?', 2)[1];
  const parameters = new URLSearchParams(query ?? '');
  const context = emptyContext();
  for (const key of ['patientId', 'documentId', 'diagnosticReportId'] as const) {
    const value = parameters.get(key);
    if (value && uuidPattern.test(value)) context[key] = value;
  }
  return context;
}

function contextHref(href: string, context: WorkflowContext) {
  const parameters = new URLSearchParams();
  for (const key of ['patientId', 'documentId', 'diagnosticReportId'] as const) {
    if (context[key]) parameters.set(key, context[key]);
  }
  const query = parameters.toString();
  return query ? `${href}?${query}` : href;
}

function contextForRow(row: DocumentRow, current: WorkflowContext): WorkflowContext {
  return {
    diagnosticReportId: row.diagnosticReportId ?? current.diagnosticReportId,
    documentId: row.documentId ?? current.documentId,
    patientId: row.patientId ?? current.patientId,
  };
}

function metricTone(tone: string) {
  return ['neutral', 'info', 'success', 'warning'].includes(tone) ? tone : 'neutral';
}

function statusTone(status: string) {
  if (['acknowledged', 'clean', 'completed', 'final', 'granted', 'resolved'].includes(status)) {
    return 'success';
  }
  if (['entered_in_error', 'infected', 'rejected', 'revoked', 'scan_failed'].includes(status)) {
    return 'danger';
  }
  if (['open', 'preliminary', 'quarantined', 'scanning'].includes(status)) return 'warning';
  return 'neutral';
}

function humanize(value: string) {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (character) => character.toUpperCase());
}

function idempotencyKey(actionKey: string) {
  if (!globalThis.crypto?.randomUUID) {
    throw new Error('Secure random identifiers are unavailable in this browser.');
  }
  return `m7:${actionKey}:${globalThis.crypto.randomUUID()}`;
}

async function sha256(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error('Secure document hashing is unavailable in this browser.');
  }
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function normalizedFields(action: WorkforceAction, values: Record<string, string>) {
  return Object.fromEntries(
    action.fields
      .filter((field) => field.inputType !== 'file')
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

function fieldDefault(
  field: WorkforceField,
  selected: DocumentRow | undefined,
  context: WorkflowContext,
) {
  const contextValue = context[field.key as keyof WorkflowContext];
  if (typeof contextValue === 'string') return contextValue;
  return selected?.values[field.key] ?? field.options[0]?.value ?? '';
}

function ActionField({
  field,
  onChange,
  onFile,
  value,
}: {
  field: WorkforceField;
  onChange(value: string): void;
  onFile(file: File | null): void;
  value: string;
}) {
  if (field.inputType === 'file') {
    return (
      <label className="full-width">
        {field.label}
        <input
          accept="application/pdf,image/jpeg,image/png,text/plain"
          required={field.required}
          type="file"
          onChange={(event) => onFile(event.target.files?.[0] ?? null)}
        />
        {field.help && <small>{field.help}</small>}
      </label>
    );
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
      </label>
    );
  }
  return (
    <label>
      {field.label}
      <input
        autoComplete="off"
        maxLength={20000}
        pattern={
          field.inputType === 'uuid'
            ? '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[1-8][0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}'
            : undefined
        }
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
  context,
  issue,
  onClose,
  onSubmit,
  selected,
}: {
  action: WorkforceAction;
  busy: boolean;
  context: WorkflowContext;
  issue: UiIssue | null;
  onClose(): void;
  onSubmit(submission: ActionSubmission): Promise<void>;
  selected: DocumentRow | undefined;
}) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const busyRef = useRef(busy);
  const [file, setFile] = useState<File | null>(null);
  const [reason, setReason] = useState('');
  const [fields, setFields] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      action.fields
        .filter((field) => field.inputType !== 'file')
        .map((field) => [field.key, fieldDefault(field, selected, context)]),
    ),
  );
  const needsSelectedRevision = action.targetRequired && !selected;

  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  useEffect(() => {
    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;
      const focusable = Array.from(
        dialog.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])',
        ) ?? [],
      );
      if (focusable.length === 0) return;
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
  }, [action.key, onClose]);

  return (
    <div className="workforce-dialog-backdrop">
      <section
        aria-labelledby="document-action-title"
        aria-modal="true"
        className="workforce-dialog"
        ref={dialog}
        role="dialog"
        tabIndex={-1}
      >
        <header>
          <div>
            <span className="eyebrow">Governed document action</span>
            <h2 id="document-action-title">{action.label}</h2>
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
            void onSubmit({ fields, file, reason, targetId: selected?.id ?? null });
          }}
        >
          {action.targetRequired && (
            <label className="full-width">
              Selected record
              <input autoComplete="off" readOnly required value={selected?.id ?? ''} />
              <small>
                {selected?.values.primary ?? 'Select an authorized document or result.'}
              </small>
            </label>
          )}

          <div className="workforce-action-fields">
            {action.fields.map((field) => (
              <ActionField
                field={field}
                key={field.key}
                value={fields[field.key] ?? ''}
                onChange={(value) => setFields((current) => ({ ...current, [field.key]: value }))}
                onFile={setFile}
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
              onChange={(event) => setReason(event.target.value)}
            />
            <small>Use 10–500 characters. Reasons become governed evidence.</small>
          </label>

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
              {busy ? 'Submitting…' : action.label}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

export function DocumentScreenPage({
  client,
  id,
  organizationId,
  shell,
}: {
  client: DocumentClient;
  id: string;
  organizationId: string;
  shell: ShellSessionProps;
}) {
  const registered = findScreen(id);
  const initialContext = contextFromHash();
  const [context, setContext] = useState<WorkflowContext>(initialContext);
  const [contextDraft, setContextDraft] = useState<WorkflowContext>(initialContext);
  const [projection, setProjection] = useState<DocumentScreen | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadIssue, setLoadIssue] = useState<UiIssue | null>(null);
  const [mutationIssue, setMutationIssue] = useState<UiIssue | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [accessPath, setAccessPath] = useState<string | null>(null);
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
      const result = await client.getDocumentScreen(
        organizationId,
        id,
        {
          ...(context.diagnosticReportId ? { diagnosticReportId: context.diagnosticReportId } : {}),
          ...(context.documentId ? { documentId: context.documentId } : {}),
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
  const moduleNumber = Number(id.slice(3));
  const previousId = moduleNumber > 1 ? `P7-${String(moduleNumber - 1).padStart(2, '0')}` : null;
  const nextId = moduleNumber < 11 ? `P7-${String(moduleNumber + 1).padStart(2, '0')}` : null;

  function applyRowContext(row: DocumentRow) {
    const nextContext = contextForRow(row, context);
    setContext(nextContext);
    setContextDraft(nextContext);
  }

  function rememberResult(next: DocumentScreen, label: string) {
    setProjection(next);
    const nextSelected =
      next.rows.find((row) => row.id === selectedId) ??
      (next.rows.length === 1 ? next.rows[0] : undefined);
    setSelectedId(nextSelected?.id ?? null);
    if (nextSelected) applyRowContext(nextSelected);
    setActiveAction(null);
    setSuccess(`${label} completed using the server-confirmed result.`);
  }

  async function submitAction(submission: ActionSubmission) {
    if (!activeAction) return;
    setMutationIssue(null);
    setSuccess(null);
    setAccessPath(null);
    try {
      const fields = normalizedFields(activeAction, submission.fields);
      const targetId = submission.targetId?.trim() || null;
      if (activeAction.targetRequired && (!targetId || !uuidPattern.test(targetId))) {
        throw new Error('Select a record with a valid target UUID.');
      }
      const reason = submission.reason.trim().normalize('NFC');
      if (reason && (reason.length < 10 || reason.length > 500)) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      if (activeAction.reasonRequired && !reason) {
        throw new Error('A reason is required for this action.');
      }
      const fingerprint = JSON.stringify({
        action: activeAction.key,
        fields,
        file: submission.file
          ? [submission.file.name, submission.file.size, submission.file.lastModified]
          : null,
        reason,
        targetId,
      });
      const key =
        lastAttempt.current?.fingerprint === fingerprint
          ? lastAttempt.current.key
          : idempotencyKey(activeAction.key);
      lastAttempt.current = { fingerprint, key };
      setSubmitting(true);

      if (activeAction.key === 'upload-document') {
        if (!submission.file) throw new Error('Select a document file to upload.');
        const patientId = fields.patientId ?? context.patientId;
        if (!patientId || !uuidPattern.test(patientId)) {
          throw new Error('A valid patient UUID is required for document upload.');
        }
        const replacementDocumentId = fields.replacementDocumentId;
        if (
          replacementDocumentId &&
          (!selected || (selected.documentId ?? selected.id) !== replacementDocumentId)
        ) {
          throw new Error('Select the exact current document before uploading a replacement.');
        }
        const metadata: DocumentUploadMetadata = {
          patientId,
          title: fields.title ?? '',
          documentTypeKey: fields.documentTypeKey ?? '',
          sourceKey: fields.sourceKey ?? '',
          sha256: await sha256(submission.file),
          reason,
          ...(fields.encounterId ? { encounterId: fields.encounterId } : {}),
          ...(fields.assessmentSessionId
            ? { assessmentSessionId: fields.assessmentSessionId }
            : {}),
          ...(replacementDocumentId ? { replacementDocumentId } : {}),
        };
        const result = await client.uploadDocument(
          organizationId,
          metadata,
          submission.file,
          replacementDocumentId ? selected?.etag : undefined,
          key,
        );
        if (!result.ok) {
          setMutationIssue(issueFromFailure(result));
          return;
        }
        lastAttempt.current = null;
        rememberResult(result.data, activeAction.label);
        return;
      }

      if (activeAction.key === 'access-document') {
        if (!selected) throw new Error('Select the clean document version to open.');
        const documentId = selected.documentId ?? context.documentId;
        if (!documentId) throw new Error('The selected row does not expose a document identifier.');
        const purposeKey = fields.purposeKey as
          'clinical_care' | 'result_review' | 'patient_request' | 'security_investigation';
        const result = await client.createDocumentAccess(
          organizationId,
          documentId,
          {
            ...(selected.documentVersionId
              ? { documentVersionId: selected.documentVersionId }
              : {}),
            purposeKey,
            reason,
          },
          key,
        );
        if (!result.ok) {
          setMutationIssue(issueFromFailure(result));
          return;
        }
        lastAttempt.current = null;
        setAccessPath(result.data.accessPath);
        setActiveAction(null);
        setSuccess('Purpose-bound access is ready for one short-lived server-authorized open.');
        return;
      }

      const body: DocumentActionRequest = {
        diagnosticReportId: selected?.diagnosticReportId ?? context.diagnosticReportId,
        documentId: selected?.documentId ?? context.documentId,
        documentVersionId: selected?.documentVersionId ?? null,
        fields,
        patientId: selected?.patientId ?? context.patientId,
        reason: reason || null,
        targetId,
      };
      const result = await client.performDocumentAction(
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
      rememberResult(result.data, activeAction.label);
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
          <span className="eyebrow">{registered.group}</span>
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
          {accessPath && (
            <a className="secondary-button" href={accessPath} rel="noreferrer" target="_blank">
              Open clean document <ExternalLink aria-hidden="true" size={16} />
            </a>
          )}
        </div>
      )}

      <details className="panel workforce-actions-panel">
        <summary>Document context</summary>
        <p>Carry exact patient, document and diagnostic-report identifiers across the workflow.</p>
        <form
          className="workforce-filters"
          onSubmit={(event) => {
            event.preventDefault();
            for (const [key, value] of Object.entries(contextDraft)) {
              if (value && !uuidPattern.test(value)) {
                setLoadIssue({
                  detail: `${humanize(key)} must contain a valid UUID.`,
                  title: 'Invalid document context',
                });
                return;
              }
            }
            setContext(contextDraft);
            setSelectedId(null);
            setCursorStack([]);
          }}
        >
          {(['patientId', 'documentId', 'diagnosticReportId'] as const).map((key) => (
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
          <h2>Loading authorized document data…</h2>
          <p>CareOS is checking organization, permission and minimum-necessary projection.</p>
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
            <section aria-label="Document and result metrics" className="workforce-metrics">
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
              aria-label="Document and result filters"
              className="workforce-filters"
              onSubmit={(event) => {
                event.preventDefault();
                const query = queryDraft.trim();
                if (query.length === 1) {
                  setLoadIssue({
                    detail: 'Searches require at least two characters.',
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
                    placeholder="Document, patient or result"
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
                <h2>Authorized records</h2>
                <p aria-live="polite">
                  {projection.rows.length} record{projection.rows.length === 1 ? '' : 's'} returned
                </p>
              </div>
              <button
                aria-label="Refresh document records"
                className="icon-button"
                onClick={() => setRefresh((value) => value + 1)}
                type="button"
              >
                <RefreshCw aria-hidden="true" size={18} />
              </button>
            </div>

            {projection.rows.length === 0 && isLocalDemoOrganization(organizationId) ? (
              <LocalDemoData screenId={id} />
            ) : projection.rows.length === 0 ? (
              <div className="workforce-empty">
                <Search aria-hidden="true" size={25} />
                <h3>No authorized records found</h3>
                <p>Adjust the filters, apply a document context or use an available action.</p>
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
                            name="document-record"
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

            <div aria-label="Document result pages" className="workforce-cursor-pager">
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
                  if (projection.nextCursor)
                    setCursorStack((current) => [...current, projection.nextCursor!]);
                }}
                type="button"
              >
                Next <ArrowRight aria-hidden="true" size={16} />
              </button>
            </div>
          </section>

          <section
            aria-labelledby="document-actions-title"
            className="panel workforce-actions-panel"
          >
            <div>
              <h2 id="document-actions-title">Available actions</h2>
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

      <nav aria-label="Document screen pagination" className="page-pagination">
        {previousId ? (
          <a className="pagination-link" href={contextHref(`#/${previousId}`, context)}>
            <ArrowLeft /> {findScreen(previousId).title}
          </a>
        ) : (
          <span aria-disabled="true" className="pagination-link pagination-disabled">
            <ArrowLeft /> Previous
          </span>
        )}
        <span className="pagination-status">{moduleNumber} of 11</span>
        {nextId ? (
          <a className="pagination-link pagination-next" href={contextHref(`#/${nextId}`, context)}>
            {findScreen(nextId).title} <ArrowRight />
          </a>
        ) : (
          <span
            aria-disabled="true"
            className="pagination-link pagination-next pagination-disabled"
          >
            Next <ArrowRight />
          </span>
        )}
      </nav>

      {activeAction && (
        <ActionDialog
          action={activeAction}
          busy={submitting}
          context={context}
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
