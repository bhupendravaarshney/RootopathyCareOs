import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Download,
  FileUp,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldCheck,
  X,
} from 'lucide-react';
import {
  useEffect,
  useRef,
  useState,
} from 'react';
import type { ApiFailure, ApiResult } from '../../api/client';
import type { WorkforceExportAccessResponse } from '../../api/workforce-contracts';
import type {
  WorkforceAction,
  WorkforceField,
  WorkforceRow,
  WorkforceScreen,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { findScreen } from '../../data/screens';
import type { WorkforceClient } from './workforce-types';

type ScreenFilters = { q?: string; status?: string; limit: number };
type UiIssue = { title: string; detail: string; correlationId?: string; status?: number };
type ActionSubmission = {
  fields: Record<string, string>;
  file: File | null;
  reason: string;
  targetId: string | null;
};

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const maximumDocumentBytes = 25 * 1024 * 1024;

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
  if (['active', 'approved', 'complete', 'completed', 'verified', 'activated'].includes(status)) {
    return 'success';
  }
  if (['rejected', 'failed', 'expired', 'revoked', 'suspended', 'blocked'].includes(status)) {
    return 'danger';
  }
  if (['submitted', 'pending', 'in_review', 'offboarding', 'warning'].includes(status)) {
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
  return `m2:${actionKey}:${globalThis.crypto.randomUUID()}`;
}

async function sha256(file: File) {
  if (!globalThis.crypto?.subtle) {
    throw new Error('Secure document hashing is unavailable in this browser.');
  }
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join(
    '',
  );
}

function normalizedFields(action: WorkforceAction, values: Record<string, string>) {
  return Object.fromEntries(
    action.fields
      .map((field) => {
        const value = values[field.key]?.trim() ?? '';
        if (!value) return null;
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
  selected: WorkforceRow | undefined;
}) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const busyRef = useRef(busy);
  const onCloseRef = useRef(onClose);
  busyRef.current = busy;
  onCloseRef.current = onClose;
  const [targetId, setTargetId] = useState(selected?.id ?? '');
  const [reason, setReason] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [fields, setFields] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      action.fields.map((field) => [
        field.key,
        field.key === 'matchRunId' && selected
          ? selected.id
          : field.key === 'candidateReference' && selected
            ? selected.values.context ?? ''
            : field.options[0]?.value ?? '',
      ]),
    ),
  );

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
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
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ) ?? [],
      ).filter((element) => element.getAttribute('aria-hidden') !== 'true');
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.current?.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
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
        aria-labelledby="workforce-action-title"
        aria-modal="true"
        className="workforce-dialog"
        ref={dialog}
        role="dialog"
        tabIndex={-1}
      >
        <header>
          <div>
            <span className="eyebrow">Governed action</span>
            <h2 id="workforce-action-title">{action.label}</h2>
          </div>
          <button
            aria-label="Close action"
            className="icon-button"
            disabled={busy}
            onClick={onClose}
            ref={closeButton}
            type="button"
          >
            <X size={20} />
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
              file,
              reason,
              targetId: action.targetRequired ? targetId : null,
            });
          }}
        >
          {action.targetRequired && (
            <label className="full-width">
              {selected ? 'Selected record' : 'Target or workforce member ID'}
              <input
                autoComplete="off"
                pattern="[0-9a-fA-F-]{36}"
                readOnly={Boolean(selected)}
                required
                value={targetId}
                onChange={(event) => setTargetId(event.target.value)}
              />
              <small>
                {selected
                  ? `${selected.values.primary ?? 'Record'} · ${humanize(selected.status)}`
                  : 'Use the UUID from an authorized CareOS workforce record.'}
              </small>
            </label>
          )}

          <div className="workforce-action-fields">
            {action.fields.map((field) => (
              <ActionField
                field={field}
                key={field.key}
                value={fields[field.key] ?? ''}
                onChange={(value) =>
                  setFields((current) => ({ ...current, [field.key]: value }))
                }
              />
            ))}
          </div>

          {action.key === 'upload-document' && (
            <label className="full-width workforce-file-field">
              Evidence file
              <input
                accept="application/pdf,image/jpeg,image/png"
                required
                type="file"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
              <small>PDF, JPEG or PNG; maximum 25 MiB. The file enters private quarantine.</small>
            </label>
          )}

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
        <select required={field.required} value={value} onChange={(event) => onChange(event.target.value)}>
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

  const type = field.inputType === 'uuid' ? 'text' : field.inputType;
  return (
    <label>
      {field.label}
      <input
        autoComplete="off"
        pattern={field.inputType === 'uuid' ? '[0-9a-fA-F-]{36}' : undefined}
        required={field.required}
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
      {field.help && <small>{field.help}</small>}
    </label>
  );
}

export function WorkforceScreenPage({
  client,
  id,
  organizationId,
  shell,
}: {
  client: WorkforceClient;
  id: string;
  organizationId: string;
  shell: ShellSessionProps;
}) {
  const registered = findScreen(id);
  const [projection, setProjection] = useState<WorkforceScreen | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadIssue, setLoadIssue] = useState<UiIssue | null>(null);
  const [mutationIssue, setMutationIssue] = useState<UiIssue | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [exportAccess, setExportAccess] = useState<WorkforceExportAccessResponse | null>(null);
  const [activeAction, setActiveAction] = useState<WorkforceAction | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [queryDraft, setQueryDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState('');
  const [filters, setFilters] = useState<ScreenFilters>({ limit: 50 });
  const [refresh, setRefresh] = useState(0);
  const lastAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    setExportAccess(null);
  }, [id, organizationId]);

  useEffect(() => {
    if (!exportAccess) return;
    const remaining = Date.parse(exportAccess.expiresAt) - Date.now();
    if (remaining <= 0) {
      setExportAccess(null);
      return;
    }
    const timeout = window.setTimeout(
      () => setExportAccess(null),
      Math.min(remaining, 2_147_483_647),
    );
    return () => window.clearTimeout(timeout);
  }, [exportAccess]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setLoadIssue(null);
    setSuccess(null);
    void client
      .getWorkforceScreen(
        organizationId,
        id,
        { limit: filters.limit, q: filters.q, status: filters.status },
        { signal: controller.signal },
      )
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          setLoadIssue(issueFromFailure(result));
          setProjection(null);
          return;
        }
        setProjection(result.data);
        setSelectedId((current) =>
          current && result.data.rows.some((row) => row.id === current) ? current : null,
        );
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [client, filters, id, organizationId, refresh]);

  const selected = projection?.rows.find((row) => row.id === selectedId);
  const moduleNumber = Number(id.slice(3));
  const previousId = moduleNumber > 1 ? `M2-${String(moduleNumber - 1).padStart(2, '0')}` : null;
  const nextId = moduleNumber < 29 ? `M2-${String(moduleNumber + 1).padStart(2, '0')}` : null;

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
      const reason = submission.reason.trim().normalize('NFC');
      if (reason && (Array.from(reason).length < 10 || Array.from(reason).length > 500)) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      if (activeAction.reasonRequired && !reason) {
        throw new Error('A reason is required for this action.');
      }

      let digest: string | undefined;
      if (activeAction.key === 'upload-document') {
        if (!submission.file) throw new Error('Choose an evidence file.');
        if (submission.file.size < 1 || submission.file.size > maximumDocumentBytes) {
          throw new Error('Evidence files must contain 1 byte to 25 MiB.');
        }
        if (!['application/pdf', 'image/jpeg', 'image/png'].includes(submission.file.type)) {
          throw new Error('Evidence must be a PDF, JPEG or PNG file with a declared media type.');
        }
        digest = await sha256(submission.file);
      }

      const memberId =
        selected?.memberId ??
        (!selected && activeAction.targetRequired && !activeAction.ifMatchRequired
          ? targetId
          : null);
      const fingerprint = JSON.stringify({
        action: activeAction.key,
        digest,
        fields,
        memberId,
        reason,
        screen: id,
        targetId,
      });
      const key =
        lastAttempt.current?.fingerprint === fingerprint
          ? lastAttempt.current.key
          : idempotencyKey(activeAction.key);
      lastAttempt.current = { fingerprint, key };
      setSubmitting(true);

      if (activeAction.key === 'access-export') {
        if (!selected || selected.status !== 'ready') {
          throw new Error('Select a ready export job before requesting access.');
        }
        const accessResult = await client.accessWorkforceExport(
          organizationId,
          selected.id,
          { purposeKey: fields.purposeKey!, reason },
          selected.etag,
          key,
        );
        if (!accessResult.ok) {
          setMutationIssue(issueFromFailure(accessResult));
          return;
        }
        lastAttempt.current = null;
        setExportAccess(accessResult.data);
        setActiveAction(null);
        setSuccess('A short-lived, read-only export access grant is ready.');
        return;
      }

      let result: ApiResult<WorkforceScreen>;
      if (activeAction.key === 'upload-document') {
        result = await client.uploadWorkforceCredentialDocument(
          organizationId,
          targetId!,
          {
            memberId,
            reason,
            retentionClass: fields.retentionClass!,
            sha256: digest!,
          },
          submission.file!,
          key,
        );
      } else {
        result = await client.performWorkforceAction(
          organizationId,
          id,
          activeAction.key,
          {
            decision: fields.decisionCode ?? null,
            evidenceIds: [],
            fields,
            memberId,
            reason: reason || null,
            targetId,
          },
          activeAction.ifMatchRequired ? selected?.etag : undefined,
          key,
        );
      }

      if (!result.ok) {
        setMutationIssue(issueFromFailure(result));
        return;
      }
      lastAttempt.current = null;
      setProjection(result.data);
      setSelectedId((current) =>
        current && result.data.rows.some((row) => row.id === current) ? current : null,
      );
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

      {exportAccess && (
        <div className="workforce-export-access" role="status">
          <Download aria-hidden="true" size={19} />
          <div>
            <strong>Private export ready</strong>
            <p>
              This link expires {new Date(exportAccess.expiresAt).toLocaleString()} and is not
              stored by CareOS in this browser.
            </p>
          </div>
          <a
            className="primary-button"
            href={exportAccess.downloadUrl}
            referrerPolicy="no-referrer"
            rel="noreferrer"
          >
            Download {exportAccess.filename}
          </a>
        </div>
      )}

      {loading && (
        <section aria-live="polite" className="panel workforce-state">
          <LoaderCircle className="spin" aria-hidden="true" size={24} />
          <h2>Loading authorized workforce data…</h2>
          <p>CareOS is checking the current organization, permission and minimum projection.</p>
        </section>
      )}

      {!loading && loadIssue && (
        <section className="panel workforce-state" role="alert">
          <AlertCircle aria-hidden="true" size={28} />
          <h2>{loadIssue.status === 403 ? 'This screen is not authorized' : loadIssue.title}</h2>
          <p>{loadIssue.detail}</p>
          {loadIssue.correlationId && <small>Reference: {loadIssue.correlationId}</small>}
          <button className="secondary-button" onClick={() => setRefresh((value) => value + 1)}>
            <RefreshCw aria-hidden="true" size={17} /> Retry
          </button>
        </section>
      )}

      {!loading && projection && (
        <>
          {projection.notices.map((notice, index) => (
            <aside className={`workforce-notice ${metricTone(notice.tone)}`} key={`${notice.title}-${index}`}>
              <AlertCircle aria-hidden="true" size={19} />
              <div>
                <strong>{notice.title}</strong>
                <p>{notice.detail}</p>
              </div>
            </aside>
          ))}

          {projection.metrics.length > 0 && (
            <section aria-label="Workforce metrics" className="workforce-metrics">
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
              className="workforce-filters"
              onSubmit={(event) => {
                event.preventDefault();
                setFilters({
                  limit: 50,
                  ...(queryDraft.trim() ? { q: queryDraft.trim() } : {}),
                  ...(statusDraft.trim() ? { status: statusDraft.trim() } : {}),
                });
              }}
            >
              <label>
                Search
                <span className="workforce-search-input">
                  <Search aria-hidden="true" size={17} />
                  <input
                    maxLength={120}
                    placeholder="Name, reference or record"
                    value={queryDraft}
                    onChange={(event) => setQueryDraft(event.target.value)}
                  />
                </span>
              </label>
              <label>
                Status
                <input
                  maxLength={40}
                  placeholder="All statuses"
                  value={statusDraft}
                  onChange={(event) => setStatusDraft(event.target.value)}
                />
              </label>
              <div className="workforce-filter-actions">
                <button className="secondary-button" type="submit">Apply filters</button>
                <button
                  className="text-action"
                  disabled={!queryDraft && !statusDraft && !filters.q && !filters.status}
                  onClick={() => {
                    setQueryDraft('');
                    setStatusDraft('');
                    setFilters({ limit: 50 });
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
                aria-label="Refresh workforce records"
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
                <p>Adjust the filters or use an available creation action.</p>
              </div>
            ) : (
              <div className="table-wrap workforce-table-wrap" role="region" aria-label={`${projection.title} records`} tabIndex={0}>
                <table className="workforce-table">
                  <caption className="sr-only">{projection.title} authorized records</caption>
                  <thead>
                    <tr>
                      <th><span className="sr-only">Select</span></th>
                      {projection.columns.map((column) => <th key={column.key}>{column.label}</th>)}
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
                            name="workforce-record"
                            type="radio"
                            onChange={() => setSelectedId(row.id)}
                          />
                        </td>
                        {projection.columns.map((column) => (
                          <td data-label={column.label} key={column.key}>
                            {row.values[column.key] || '—'}
                          </td>
                        ))}
                        <td data-label="Status">
                          <span className={`badge ${statusTone(row.status)}`}>{humanize(row.status)}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section aria-labelledby="workforce-actions-title" className="panel workforce-actions-panel">
            <div>
              <h2 id="workforce-actions-title">Available actions</h2>
              <p>CareOS shows only actions authorized by the server for your current organization.</p>
            </div>
            {projection.actions.length === 0 ? (
              <p className="workforce-no-actions">No governed actions are available for this account.</p>
            ) : (
              <div className="workforce-actions">
                {projection.actions.map((action) =>
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
                      {action.key === 'upload-document' && <FileUp aria-hidden="true" size={17} />}
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

      <nav aria-label="Workforce screen pagination" className="page-pagination">
        {previousId ? (
          <a className="pagination-link" href={`#/${previousId}`}><ArrowLeft /> {previousId}</a>
        ) : (
          <span aria-disabled="true" className="pagination-link pagination-disabled"><ArrowLeft /> M2-01</span>
        )}
        <span className="pagination-status">{moduleNumber} of 29</span>
        {nextId ? (
          <a className="pagination-link pagination-next" href={`#/${nextId}`}>{nextId} <ArrowRight /></a>
        ) : (
          <span aria-disabled="true" className="pagination-link pagination-next pagination-disabled">M2-29 <ArrowRight /></span>
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
