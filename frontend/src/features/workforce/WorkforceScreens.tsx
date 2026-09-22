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
import type {
  WorkforceCredentialDocumentAccessRequest,
  WorkforceCredentialDocumentAccessResponse,
  WorkforceEvidenceAccessRequest,
  WorkforceEvidenceAccessResponse,
  WorkforceExportAccessResponse,
  WorkforceImpactPreviewResponse,
} from '../../api/workforce-contracts';
import type {
  WorkforceAction,
  WorkforceField,
  WorkforceOption,
  WorkforceRow,
  WorkforceScreen,
} from '../../api/generated';
import { Shell, type ShellSessionProps } from '../../components/Shell';
import { findScreen } from '../../data/screens';
import type { WorkforceClient } from './workforce-types';

type ScreenFilters = { q?: string; status?: string; limit: number };
type UiIssue = { title: string; detail: string; correlationId?: string; status?: number };
type ActionSubmission = {
  evidenceIds: string[];
  fields: Record<string, string>;
  file: File | null;
  impactToken?: string;
  reason: string;
  targetId: string | null;
};

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const maximumDocumentBytes = 25 * 1024 * 1024;
const impactActions = new Set([
  'request-person-merge',
  'suspend-registration',
  'revoke-registration',
  'suspend-credential',
  'revoke-credential',
  'suspend-scope',
  'end-scope',
  'transfer-assignment',
  'suspend-assignment',
  'reactivate-assignment',
  'end-assignment',
  'suspend-member',
  'reactivate-member',
  'request-offboarding',
]);

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

function evidenceValue(value: unknown) {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (Array.isArray(value) && value.every((entry) => ['string', 'number', 'boolean'].includes(typeof entry))) {
    return value.map(String).join(', ');
  }
  return 'Structured evidence retained';
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

function personMatchOptions(selected: WorkforceRow | undefined): WorkforceOption[] {
  if (!selected) return [];
  const options: WorkforceOption[] = [];
  for (let index = 0; ; index += 1) {
    const value = selected.values[`candidate.${index}.value`];
    const label = selected.values[`candidate.${index}.label`];
    if (!value || !label) break;
    options.push({ value, label });
  }
  return options;
}

function credentialEvidenceOptions(selected: WorkforceRow | undefined): WorkforceOption[] {
  if (!selected) return [];
  const options: WorkforceOption[] = [];
  for (let index = 0; ; index += 1) {
    const value = selected.values[`evidence.${index}.id`];
    const label = selected.values[`evidence.${index}.label`];
    if (!value || !label) break;
    options.push({ value, label });
  }
  return options;
}

function ActionDialog({
  action,
  busy,
  defaultFieldValues,
  issue,
  onClose,
  onPreview,
  onSubmit,
  selected,
}: {
  action: WorkforceAction;
  busy: boolean;
  defaultFieldValues: Record<string, string>;
  issue: UiIssue | null;
  onClose(): void;
  onPreview(submission: ActionSubmission): Promise<WorkforceImpactPreviewResponse | null>;
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
  const [impactPreview, setImpactPreview] = useState<WorkforceImpactPreviewResponse | null>(null);
  const matchOptions = personMatchOptions(selected);
  const evidenceOptions = credentialEvidenceOptions(selected);
  const [selectedEvidenceIds, setSelectedEvidenceIds] = useState<string[]>(() =>
    evidenceOptions.map((option) => option.value),
  );
  const [fields, setFields] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      action.fields.map((field) => [
        field.key,
        field.key === 'matchRunId' && selected
          ? selected.values.matchRunId ?? ''
          : field.key === 'candidateReference' && selected
            ? selected.values.candidateReference ?? ''
            : field.key === 'documentId' && evidenceOptions.length > 0
              ? evidenceOptions[0].value
              : defaultFieldValues[field.key] ?? field.options[0]?.value ?? '',
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
          'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
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

  useEffect(() => {
    if (issue && impactPreview && !busy) {
      setImpactPreview(null);
    }
  }, [busy, impactPreview, issue]);

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
            const submission: ActionSubmission = {
              evidenceIds: selectedEvidenceIds,
              fields,
              file,
              reason,
              targetId: action.targetRequired ? targetId : null,
            };
            if (impactActions.has(action.key) && impactPreview === null) {
              void onPreview(submission).then((preview) => {
                if (preview) setImpactPreview(preview);
              });
              return;
            }
            void onSubmit({
              ...submission,
              ...(impactPreview ? { impactToken: impactPreview.token } : {}),
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
                onChange={(event) => {
                  setTargetId(event.target.value);
                  setImpactPreview(null);
                }}
              />
              <small>
                {selected
                  ? `${selected.values.primary ?? 'Record'} · ${humanize(selected.status)}`
                  : 'Use the UUID from an authorized CareOS workforce record.'}
              </small>
            </label>
          )}

          <div className="workforce-action-fields">
            {action.fields.map((field) => {
              const projectedField: WorkforceField =
                field.key === 'candidateReference' && matchOptions.length > 0
                  ? { ...field, inputType: 'select' as const, options: matchOptions }
                  : field.key === 'documentId' && evidenceOptions.length > 0
                    ? { ...field, inputType: 'select' as const, options: evidenceOptions }
                  : field;
              return (
                <ActionField
                  field={projectedField}
                  key={field.key}
                  value={fields[field.key] ?? ''}
                  onChange={(value) => {
                    setFields((current) => {
                      if (action.key === 'record-match-decision' && field.key === 'decisionCode') {
                        const reference = value === 'use_existing'
                          ? matchOptions[1]?.value ?? ''
                          : matchOptions[0]?.value ?? '';
                        return { ...current, [field.key]: value, candidateReference: reference };
                      }
                      return { ...current, [field.key]: value };
                    });
                    setImpactPreview(null);
                  }}
                />
              );
            })}
          </div>

          {action.key === 'decide-credential' && (
            <fieldset className="full-width workforce-evidence-selection">
              <legend>Clean evidence used for this decision</legend>
              {evidenceOptions.length === 0 ? (
                <p className="help">No clean promoted evidence is available.</p>
              ) : (
                evidenceOptions.map((option) => (
                  <label className="check-row" key={option.value}>
                    <input
                      checked={selectedEvidenceIds.includes(option.value)}
                      type="checkbox"
                      onChange={(event) => {
                        setSelectedEvidenceIds((current) =>
                          event.target.checked
                            ? [...current, option.value]
                            : current.filter((value) => value !== option.value),
                        );
                      }}
                    />
                    <span><strong>Clean promoted evidence</strong><small>{option.label}</small></span>
                  </label>
                ))
              )}
            </fieldset>
          )}

          {action.key === 'upload-document' && (
            <label className="full-width workforce-file-field">
              Evidence file
              <input
                accept="application/pdf,image/jpeg,image/png"
                required
                type="file"
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null);
                  setImpactPreview(null);
                }}
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
              onChange={(event) => {
                setReason(event.target.value);
                setImpactPreview(null);
              }}
            />
            <small>Use 10–500 characters. Reasons become governed evidence.</small>
          </label>

          {impactPreview && (
            <section aria-labelledby="workforce-impact-title" className="workforce-impact-preview">
              <header>
                <div>
                  <span className="eyebrow">Fresh server impact</span>
                  <h3 id="workforce-impact-title">Review before confirmation</h3>
                </div>
                <span className={`badge ${impactPreview.blocked ? 'danger' : 'warning'}`}>
                  {impactPreview.blocked ? 'Blocked' : 'Expires soon'}
                </span>
              </header>
              <ul>
                {impactPreview.items.map((item) => (
                  <li className={item.tone} key={item.code}>
                    <strong>{humanize(item.code)}</strong>
                    <span>{item.detail}</span>
                    <small>{item.affectedCount} affected</small>
                  </li>
                ))}
              </ul>
              <p>
                Digest <code>{impactPreview.digest.slice(0, 12)}…</code> · expires{' '}
                {new Date(impactPreview.expiresAt).toLocaleTimeString()}
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
              disabled={
                busy ||
                needsSelectedRevision ||
                Boolean(impactPreview?.blocked) ||
                (action.key === 'decide-credential' && selectedEvidenceIds.length === 0)
              }
              type="submit"
            >
              {busy && <LoaderCircle className="spin" aria-hidden="true" size={17} />}
              {busy
                ? impactActions.has(action.key) && impactPreview === null
                  ? 'Reviewing impact…'
                  : 'Submitting…'
                : impactActions.has(action.key) && impactPreview === null
                  ? 'Review impact'
                  : action.label}
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
  if (field.inputType === 'hidden') {
    return <input name={field.key} type="hidden" value={value} readOnly />;
  }

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
  const [evidenceAccess, setEvidenceAccess] = useState<WorkforceEvidenceAccessResponse | null>(null);
  const [credentialDocumentAccess, setCredentialDocumentAccess] =
    useState<WorkforceCredentialDocumentAccessResponse | null>(null);
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
    setExportAccess(null);
    setEvidenceAccess(null);
    setCredentialDocumentAccess(null);
    setCursorStack([]);
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
    if (!credentialDocumentAccess) return;
    const remaining = Date.parse(credentialDocumentAccess.expiresAt) - Date.now();
    if (remaining <= 0) {
      setCredentialDocumentAccess(null);
      return;
    }
    const timeout = window.setTimeout(
      () => setCredentialDocumentAccess(null),
      Math.min(remaining, 2_147_483_647),
    );
    return () => window.clearTimeout(timeout);
  }, [credentialDocumentAccess]);

  useEffect(() => {
    const controller = new AbortController();
    const activeCursor = cursorStack.at(-1);
    setLoading(true);
    setLoadIssue(null);
    setSuccess(null);
    void client
      .getWorkforceScreen(
        organizationId,
        id,
        {
          limit: filters.limit,
          q: filters.q,
          status: filters.status,
          ...(activeCursor ? { cursor: activeCursor } : {}),
        },
        { signal: controller.signal },
      )
      .then((result) => {
        if (controller.signal.aborted) return;
        if (!result.ok) {
          if (activeCursor && (result.status === 400 || result.status === 409)) {
            setCursorStack([]);
            setSuccess('The result set changed, the page cursor expired, or its filters no longer matched. Pagination restarted.');
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
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [client, cursorStack, filters, id, organizationId, refresh]);

  const selected = projection?.rows.find((row) => row.id === selectedId);
  const availableActions = projection?.actions.filter(
    (action) =>
      !selected ||
      !action.targetRequired ||
      selected.allowedActionKeys.includes(action.key),
  ) ?? [];
  const moduleNumber = Number(id.slice(3));
  const previousId = moduleNumber > 1 ? `M2-${String(moduleNumber - 1).padStart(2, '0')}` : null;
  const nextId = moduleNumber < 29 ? `M2-${String(moduleNumber + 1).padStart(2, '0')}` : null;

  async function requestImpactPreview(
    submission: ActionSubmission,
  ): Promise<WorkforceImpactPreviewResponse | null> {
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
      const reasonLength = Array.from(reason).length;
      if (activeAction.reasonRequired && (reasonLength < 10 || reasonLength > 500)) {
        throw new Error('Reason must contain 10 to 500 characters.');
      }
      const memberId = selected.memberId ?? null;
      setSubmitting(true);
      const result = await client.previewWorkforceImpact(
        organizationId,
        id,
        activeAction.key,
        {
          decision: fields.decisionCode ?? null,
          evidenceIds: submission.evidenceIds,
          fields,
          memberId,
          reason: reason || null,
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
        evidenceIds: submission.evidenceIds,
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

      if (activeAction.key === 'access-evidence') {
        if (!selected) {
          throw new Error('Select an evidence record before requesting restricted detail.');
        }
        const projection = fields.projection as WorkforceEvidenceAccessRequest['projection'];
        const purposeCode = fields.purposeCode as WorkforceEvidenceAccessRequest['purposeCode'];
        const accessResult = await client.accessWorkforceEvidence(
          organizationId,
          selected.id,
          {
            ...(selected.memberId ? { memberId: selected.memberId } : {}),
            projection,
            purposeCode,
            reason,
          },
          key,
        );
        if (!accessResult.ok) {
          setMutationIssue(issueFromFailure(accessResult));
          return;
        }
        lastAttempt.current = null;
        setEvidenceAccess(accessResult.data);
        setActiveAction(null);
        setSuccess('Restricted evidence opened and the access was audited.');
        return;
      }

      if (activeAction.key === 'access-credential-document') {
        if (!selected) {
          throw new Error('Select a credential before requesting clean evidence access.');
        }
        const documentId = fields.documentId;
        if (!documentId || !uuidPattern.test(documentId)) {
          throw new Error('Select a clean promoted evidence document.');
        }
        const purposeCode = fields.purposeCode as
          WorkforceCredentialDocumentAccessRequest['purposeCode'];
        const accessResult = await client.accessWorkforceCredentialDocument(
          organizationId,
          selected.id,
          documentId,
          { purposeCode, reason },
        );
        if (!accessResult.ok) {
          setMutationIssue(issueFromFailure(accessResult));
          return;
        }
        lastAttempt.current = null;
        setCredentialDocumentAccess(accessResult.data);
        setActiveAction(null);
        setSuccess('Short-lived clean evidence access was granted and audited.');
        return;
      }

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
            evidenceIds: submission.evidenceIds,
            fields,
            ...(submission.impactToken ? { impactToken: submission.impactToken } : {}),
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

      {credentialDocumentAccess && (
        <div className="workforce-export-access" role="status">
          <ShieldCheck aria-hidden="true" size={19} />
          <div>
            <strong>Clean evidence access ready</strong>
            <p>
              {credentialDocumentAccess.mediaType} · {credentialDocumentAccess.byteCount} bytes ·
              expires {new Date(credentialDocumentAccess.expiresAt).toLocaleTimeString()}
            </p>
          </div>
          <button
            className="primary-button"
            onClick={() => {
              window.open(
                credentialDocumentAccess.readUrl,
                '_blank',
                'noopener,noreferrer',
              );
            }}
            type="button"
          >
            Open evidence
          </button>
          <button
            aria-label="Close clean evidence access"
            className="icon-button"
            onClick={() => setCredentialDocumentAccess(null)}
            type="button"
          >
            <X aria-hidden="true" size={18} />
          </button>
        </div>
      )}

      {evidenceAccess && (
        <section aria-labelledby="workforce-evidence-title" className="panel workforce-evidence-detail">
          <header>
            <div>
              <span className="eyebrow">Audited restricted detail</span>
              <h2 id="workforce-evidence-title">{humanize(evidenceAccess.eventName)}</h2>
              <p>
                {new Date(evidenceAccess.occurredAt).toLocaleString()} · {evidenceAccess.operation}
              </p>
            </div>
            <button
              aria-label="Close restricted evidence detail"
              className="icon-button"
              onClick={() => setEvidenceAccess(null)}
              type="button"
            >
              <X aria-hidden="true" size={18} />
            </button>
          </header>
          <dl>
            <div><dt>Evidence ID</dt><dd>{evidenceAccess.evidenceId}</dd></div>
            <div><dt>Subject</dt><dd>{evidenceAccess.subjectType} · {evidenceAccess.subjectId}</dd></div>
            <div><dt>Actor</dt><dd>{evidenceAccess.actorKind} · {evidenceAccess.actorId}</dd></div>
            <div><dt>Correlation</dt><dd>{evidenceAccess.correlationId}</dd></div>
            <div><dt>Purpose</dt><dd>{humanize(evidenceAccess.purposeCode)}</dd></div>
            <div><dt>Redaction policy</dt><dd>{evidenceAccess.redactionPolicyVersion}</dd></div>
            {Object.entries(evidenceAccess.payload).map(([key, value]) => (
              <div key={key}><dt>{humanize(key)}</dt><dd>{evidenceValue(value)}</dd></div>
            ))}
          </dl>
        </section>
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
                  limit: 25,
                  ...(queryDraft.trim() ? { q: queryDraft.trim() } : {}),
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
                    minLength={2}
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
            <div aria-label="Workforce result pages" className="workforce-cursor-pager">
              <button
                className="secondary-button"
                disabled={cursorStack.length === 0}
                onClick={() => setCursorStack((current) => current.slice(0, -1))}
                type="button"
              >
                <ArrowLeft aria-hidden="true" size={16} /> Previous
              </button>
              <span>Page {cursorStack.length + 1} · up to {projection.pageSize} records</span>
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

          <section aria-labelledby="workforce-actions-title" className="panel workforce-actions-panel">
            <div>
              <h2 id="workforce-actions-title">Available actions</h2>
              <p>CareOS combines your live permission with the selected record’s server-projected state.</p>
            </div>
            {availableActions.length === 0 ? (
              <p className="workforce-no-actions">No governed actions are available for this account.</p>
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
          defaultFieldValues={{
            filterSearch: filters.q ?? '',
            filterStatus: filters.status ?? '',
          }}
          issue={mutationIssue}
          selected={selected}
          onClose={() => {
            if (!submitting) {
              setActiveAction(null);
              setMutationIssue(null);
            }
          }}
          onSubmit={submitAction}
          onPreview={requestImpactPreview}
        />
      )}
    </Shell>
  );
}
