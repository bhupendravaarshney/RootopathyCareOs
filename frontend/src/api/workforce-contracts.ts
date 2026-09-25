import type {
  WorkforceAction,
  WorkforceColumn,
  WorkforceCredentialDocumentAccessRequest,
  WorkforceCredentialDocumentAccessResponse,
  WorkforceEvidenceAccessRequest,
  WorkforceEvidenceAccessResponse,
  WorkforceExportAccessResponse,
  WorkforceField,
  WorkforceImpactItem,
  WorkforceImpactPreviewResponse,
  WorkforceMetric,
  WorkforceNotice,
  WorkforceOption,
  WorkforceRow,
  WorkforceScreen,
} from './generated';

export type {
  WorkforceCredentialDocumentAccessRequest,
  WorkforceCredentialDocumentAccessResponse,
  WorkforceEvidenceAccessRequest,
  WorkforceEvidenceAccessResponse,
  WorkforceExportAccessRequest,
  WorkforceExportAccessResponse,
  WorkforceImpactItem,
  WorkforceImpactPreviewResponse,
} from './generated';

export type WorkforceResponseValidator<T> = (value: unknown) => value is T;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const screenPattern = /^M2-(0[1-9]|1[0-9]|2[0-9])$/;
const actionKeyPattern = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const fieldKeyPattern = /^[A-Za-z][A-Za-z0-9]{0,79}$/;
const workforceLinkPattern = /^#\/(?:M1-(?:0[1-9]|1[0-9]|2[0-3])|M2-(?:0[1-9]|1[0-9]|2[0-9]))$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const safeExportFilenamePattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,126}[A-Za-z0-9]$/;
const fieldTypes = new Set(['date', 'datetime-local', 'hidden', 'select', 'text', 'uuid']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(
  value: unknown,
  required: readonly string[],
  optional: readonly string[] = [],
): value is Record<string, unknown> {
  if (!isRecord(value)) return false;
  const permitted = new Set([...required, ...optional]);
  const keys = Object.keys(value);
  return required.every((key) => keys.includes(key)) && keys.every((key) => permitted.has(key));
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isOptionalNullableUuid(value: unknown): value is string | null | undefined {
  return value === undefined || value === null || isUuid(value);
}

function isDateTime(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function isArrayOf<T>(value: unknown, validator: (item: unknown) => item is T): value is T[] {
  return Array.isArray(value) && value.every(validator);
}

function hasUniqueValues(values: readonly string[]) {
  return new Set(values).size === values.length;
}

function isStringMap(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === 'string');
}

function isImpactItem(value: unknown): value is WorkforceImpactItem {
  return (
    hasExactKeys(value, ['code', 'tone', 'detail', 'affectedCount']) &&
    typeof value.code === 'string' &&
    /^[a-z][a-z0-9_]{1,79}$/.test(value.code) &&
    (value.tone === 'impact' || value.tone === 'warning' || value.tone === 'blocker') &&
    isNonEmptyString(value.detail) &&
    Array.from(value.detail).length <= 240 &&
    Number.isSafeInteger(value.affectedCount) &&
    Number(value.affectedCount) >= 0
  );
}

export function workforceCredentialDocumentAccessValidator(
  organizationId: string,
  credentialId: string,
  documentId: string,
  purposeCode?: WorkforceCredentialDocumentAccessRequest['purposeCode'],
): WorkforceResponseValidator<WorkforceCredentialDocumentAccessResponse> {
  return (value): value is WorkforceCredentialDocumentAccessResponse => {
    if (
      !hasExactKeys(value, [
        'credentialId',
        'documentId',
        'accessIntentId',
        'readUrl',
        'expiresAt',
        'mediaType',
        'byteCount',
        'evidenceDigest',
        'purposeCode',
      ]) ||
      value.credentialId !== credentialId ||
      value.documentId !== documentId ||
      !isUuid(value.credentialId) ||
      !isUuid(value.documentId) ||
      !isUuid(value.accessIntentId) ||
      !isDateTime(value.expiresAt) ||
      typeof value.readUrl !== 'string' ||
      typeof value.mediaType !== 'string' ||
      !/^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(value.mediaType) ||
      !Number.isSafeInteger(value.byteCount) ||
      Number(value.byteCount) < 1 ||
      typeof value.evidenceDigest !== 'string' ||
      !sha256Pattern.test(value.evidenceDigest)
    ) {
      return false;
    }
    if (
      !new Set([
        'credentialing_review',
        'regulatory_evidence',
        'security_investigation',
        'employment_record_request',
        'data_correction',
      ]).has(value.purposeCode as string)
    ) {
      return false;
    }
    const expectedUrl =
      `/api/v1/organizations/${organizationId}/workforce/credentials/${credentialId}` +
      `/documents/${documentId}/accesses/${value.accessIntentId}` +
      `?purposeCode=${value.purposeCode as string}`;
    return (
      value.readUrl === expectedUrl &&
      Date.parse(value.expiresAt as string) > Date.now() &&
      (purposeCode === undefined || value.purposeCode === purposeCode)
    );
  };
}

function isMetric(value: unknown): value is WorkforceMetric {
  return (
    hasExactKeys(value, ['key', 'label', 'value', 'tone']) &&
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label) &&
    typeof value.value === 'number' &&
    Number.isSafeInteger(value.value) &&
    value.value >= 0 &&
    (value.tone === 'neutral' ||
      value.tone === 'info' ||
      value.tone === 'success' ||
      value.tone === 'warning')
  );
}

function isColumn(value: unknown): value is WorkforceColumn {
  return (
    hasExactKeys(value, ['key', 'label']) &&
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label)
  );
}

function isRow(value: unknown, screenId: string): value is WorkforceRow {
  return (
    hasExactKeys(
      value,
      ['id', 'status', 'revision', 'etag', 'values', 'allowedActionKeys'],
      ['memberId'],
    ) &&
    isUuid(value.id) &&
    isOptionalNullableUuid(value.memberId) &&
    isNonEmptyString(value.status) &&
    typeof value.revision === 'number' &&
    Number.isSafeInteger(value.revision) &&
    value.revision >= 0 &&
    value.etag === `"m2:${screenId}:${value.id as string}:${value.revision as number}"` &&
    isStringMap(value.values) &&
    isArrayOf(
      value.allowedActionKeys,
      (key): key is string => typeof key === 'string' && actionKeyPattern.test(key),
    ) &&
    hasUniqueValues(value.allowedActionKeys as string[])
  );
}

function isOption(value: unknown): value is WorkforceOption {
  return (
    hasExactKeys(value, ['value', 'label']) &&
    isNonEmptyString(value.value) &&
    isNonEmptyString(value.label)
  );
}

function isField(value: unknown): value is WorkforceField {
  return (
    hasExactKeys(value, ['key', 'label', 'inputType', 'required', 'options'], ['help']) &&
    typeof value.key === 'string' &&
    fieldKeyPattern.test(value.key) &&
    isNonEmptyString(value.label) &&
    typeof value.inputType === 'string' &&
    fieldTypes.has(value.inputType) &&
    typeof value.required === 'boolean' &&
    (value.help === undefined || value.help === null || typeof value.help === 'string') &&
    isArrayOf(value.options, isOption) &&
    hasUniqueValues((value.options as WorkforceOption[]).map((option) => option.value))
  );
}

function isAction(value: unknown): value is WorkforceAction {
  if (!(
    hasExactKeys(
      value,
      ['key', 'label', 'style', 'targetRequired', 'ifMatchRequired', 'reasonRequired', 'fields'],
      ['href'],
    ) &&
    typeof value.key === 'string' &&
    actionKeyPattern.test(value.key) &&
    isNonEmptyString(value.label) &&
    (value.style === 'primary' || value.style === 'link') &&
    typeof value.targetRequired === 'boolean' &&
    typeof value.ifMatchRequired === 'boolean' &&
    typeof value.reasonRequired === 'boolean' &&
    (value.href === undefined || value.href === null || typeof value.href === 'string') &&
    isArrayOf(value.fields, isField)
  )) {
    return false;
  }
  const fields = value.fields as WorkforceField[];
  if (!hasUniqueValues(fields.map((field) => field.key))) return false;
  if (value.style === 'link') {
    return (
      typeof value.href === 'string' &&
      workforceLinkPattern.test(value.href) &&
      value.targetRequired === false &&
      value.ifMatchRequired === false &&
      value.reasonRequired === false &&
      fields.length === 0
    );
  }
  return (
    (value.href === undefined || value.href === null) &&
    (!value.ifMatchRequired || value.targetRequired)
  );
}

function isNotice(value: unknown): value is WorkforceNotice {
  return (
    hasExactKeys(value, ['tone', 'title', 'detail']) &&
    isNonEmptyString(value.tone) &&
    isNonEmptyString(value.title) &&
    isNonEmptyString(value.detail)
  );
}

export function workforceScreenValidator(
  organizationId: string,
  screenId: string,
): WorkforceResponseValidator<WorkforceScreen> {
  return (value): value is WorkforceScreen => {
    if (!(
      hasExactKeys(value, [
        'organizationId',
        'screenId',
        'title',
        'purpose',
        'generatedAt',
        'metrics',
        'columns',
        'rows',
        'actions',
        'notices',
        'nextCursor',
        'pageSize',
      ]) &&
      value.organizationId === organizationId &&
      value.screenId === screenId &&
      screenPattern.test(screenId) &&
      isNonEmptyString(value.title) &&
      isNonEmptyString(value.purpose) &&
      isDateTime(value.generatedAt) &&
      isArrayOf(value.metrics, isMetric) &&
      isArrayOf(value.columns, isColumn) &&
      isArrayOf(value.rows, (row): row is WorkforceRow => isRow(row, screenId)) &&
      isArrayOf(value.actions, isAction) &&
      isArrayOf(value.notices, isNotice) &&
      Number.isSafeInteger(value.pageSize) &&
      Number(value.pageSize) >= 1 &&
      Number(value.pageSize) <= 100 &&
      (value.nextCursor === null ||
        (typeof value.nextCursor === 'string' && /^[A-Za-z0-9_-]{1,2048}$/.test(value.nextCursor)))
    )) {
      return false;
    }
    const columns = value.columns as WorkforceColumn[];
    const rows = value.rows as WorkforceRow[];
    const actions = value.actions as WorkforceAction[];
    if (
      !hasUniqueValues(columns.map((column) => column.key)) ||
      !hasUniqueValues(rows.map((row) => row.id)) ||
      !hasUniqueValues(actions.map((action) => action.key))
    ) {
      return false;
    }
    const targetActions = new Set(
      actions.filter((action) => action.targetRequired).map((action) => action.key),
    );
    return rows.every((row) => row.allowedActionKeys.every((key) => targetActions.has(key)));
  };
}

export function workforceExportAccessValidator(
  organizationId: string,
  exportId: string,
): WorkforceResponseValidator<WorkforceExportAccessResponse> {
  return (value): value is WorkforceExportAccessResponse => {
    if (
      !hasExactKeys(value, [
        'exportId',
        'downloadUrl',
        'expiresAt',
        'artifactDigest',
        'contentType',
        'filename',
      ]) ||
      value.exportId !== exportId ||
      !isUuid(value.exportId) ||
      !isDateTime(value.expiresAt) ||
      typeof value.artifactDigest !== 'string' ||
      !sha256Pattern.test(value.artifactDigest) ||
      (value.contentType !== 'text/csv' && value.contentType !== 'application/x-ndjson') ||
      typeof value.filename !== 'string' ||
      !safeExportFilenamePattern.test(value.filename) ||
      typeof value.downloadUrl !== 'string'
    ) {
      return false;
    }

    const expectedPath = `/api/v1/organizations/${organizationId}/workforce/exports/${exportId}/download`;
    return value.downloadUrl === expectedPath && Date.parse(value.expiresAt) > Date.now();
  };
}

export function workforceEvidenceAccessValidator(
  evidenceId: string,
  memberId?: string | null,
  projection?: WorkforceEvidenceAccessRequest['projection'],
  purposeCode?: WorkforceEvidenceAccessRequest['purposeCode'],
): WorkforceResponseValidator<WorkforceEvidenceAccessResponse> {
  return (value): value is WorkforceEvidenceAccessResponse =>
    hasExactKeys(
      value,
      [
        'evidenceId',
        'occurredAt',
        'actorId',
        'actorKind',
        'operation',
        'eventName',
        'schemaVersion',
        'subjectType',
        'subjectId',
        'correlationId',
        'projection',
        'purposeCode',
        'redactionPolicyVersion',
        'payload',
      ],
      ['memberId'],
    ) &&
    value.evidenceId === evidenceId &&
    isUuid(value.evidenceId) &&
    (memberId == null || value.memberId === memberId) &&
    isOptionalNullableUuid(value.memberId) &&
    isDateTime(value.occurredAt) &&
    isUuid(value.actorId) &&
    (value.actorKind === 'user' || value.actorKind === 'service') &&
    isNonEmptyString(value.operation) &&
    isNonEmptyString(value.eventName) &&
    typeof value.schemaVersion === 'number' &&
    Number.isInteger(value.schemaVersion) &&
    value.schemaVersion >= 1 &&
    isNonEmptyString(value.subjectType) &&
    isUuid(value.subjectId) &&
    isNonEmptyString(value.correlationId) &&
    Array.from(value.correlationId).length <= 128 &&
    (value.projection === 'workforce-audit-detail-v1' ||
      value.projection === 'member-evidence-detail-v1') &&
    (value.purposeCode === 'workforce_operations' ||
      value.purposeCode === 'credentialing_review' ||
      value.purposeCode === 'regulatory_evidence' ||
      value.purposeCode === 'security_investigation' ||
      value.purposeCode === 'employment_record_request' ||
      value.purposeCode === 'data_correction') &&
    isNonEmptyString(value.redactionPolicyVersion) &&
    isRecord(value.payload) &&
    (projection === undefined || value.projection === projection) &&
    (purposeCode === undefined || value.purposeCode === purposeCode);
}

export function workforceImpactPreviewValidator(
  screenId: string,
  actionKey: string,
  targetId: string,
  revision: number,
): WorkforceResponseValidator<WorkforceImpactPreviewResponse> {
  return (value): value is WorkforceImpactPreviewResponse =>
    hasExactKeys(value, [
      'screenId',
      'actionKey',
      'targetId',
      'revision',
      'digest',
      'token',
      'expiresAt',
      'blocked',
      'items',
    ]) &&
    value.screenId === screenId &&
    screenPattern.test(value.screenId as string) &&
    value.actionKey === actionKey &&
    actionKeyPattern.test(value.actionKey as string) &&
    value.targetId === targetId &&
    isUuid(value.targetId) &&
    value.revision === revision &&
    Number.isSafeInteger(value.revision) &&
    Number(value.revision) >= 0 &&
    typeof value.digest === 'string' &&
    sha256Pattern.test(value.digest) &&
    typeof value.token === 'string' &&
    /^[A-Za-z0-9_-]{32,4096}$/.test(value.token) &&
    isDateTime(value.expiresAt) &&
    Date.parse(value.expiresAt) > Date.now() &&
    typeof value.blocked === 'boolean' &&
    isArrayOf(value.items, isImpactItem) &&
    value.items.length >= 1 &&
    value.items.length <= 32 &&
    value.blocked === value.items.some((item) => item.tone === 'blocker');
}
