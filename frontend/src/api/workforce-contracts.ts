import type {
  WorkforceAction,
  WorkforceColumn,
  WorkforceField,
  WorkforceMetric,
  WorkforceNotice,
  WorkforceOption,
  WorkforceRow,
  WorkforceScreen,
} from './generated';

export type WorkforceResponseValidator<T> = (value: unknown) => value is T;

export type WorkforceExportAccessRequest = {
  purposeKey: string;
  reason: string;
};

export type WorkforceExportAccessResponse = {
  exportId: string;
  downloadUrl: string;
  expiresAt: string;
  artifactDigest: string;
  contentType: 'text/csv' | 'application/x-ndjson';
  filename: string;
};

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const screenPattern = /^M2-(0[1-9]|1[0-9]|2[0-9])$/;
const strongEtagPattern = /^"[A-Za-z0-9._:-]{1,128}"$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const safeExportFilenamePattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,126}[A-Za-z0-9]$/;
const fieldTypes = new Set(['date', 'datetime-local', 'select', 'text', 'uuid']);

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

function isStringMap(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === 'string');
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

function isRow(value: unknown): value is WorkforceRow {
  return (
    hasExactKeys(value, ['id', 'status', 'revision', 'etag', 'values'], ['memberId']) &&
    isUuid(value.id) &&
    isOptionalNullableUuid(value.memberId) &&
    isNonEmptyString(value.status) &&
    typeof value.revision === 'number' &&
    Number.isSafeInteger(value.revision) &&
    value.revision >= 0 &&
    typeof value.etag === 'string' &&
    strongEtagPattern.test(value.etag) &&
    isStringMap(value.values)
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
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label) &&
    typeof value.inputType === 'string' &&
    fieldTypes.has(value.inputType) &&
    typeof value.required === 'boolean' &&
    (value.help === undefined || value.help === null || typeof value.help === 'string') &&
    isArrayOf(value.options, isOption)
  );
}

function isAction(value: unknown): value is WorkforceAction {
  return (
    hasExactKeys(
      value,
      [
        'key',
        'label',
        'style',
        'targetRequired',
        'ifMatchRequired',
        'reasonRequired',
        'fields',
      ],
      ['href'],
    ) &&
    isNonEmptyString(value.key) &&
    isNonEmptyString(value.label) &&
    (value.style === 'primary' || value.style === 'link') &&
    typeof value.targetRequired === 'boolean' &&
    typeof value.ifMatchRequired === 'boolean' &&
    typeof value.reasonRequired === 'boolean' &&
    (value.href === undefined || value.href === null || isNonEmptyString(value.href)) &&
    isArrayOf(value.fields, isField)
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
  return (value): value is WorkforceScreen =>
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
    ]) &&
    value.organizationId === organizationId &&
    value.screenId === screenId &&
    screenPattern.test(screenId) &&
    isNonEmptyString(value.title) &&
    isNonEmptyString(value.purpose) &&
    isDateTime(value.generatedAt) &&
    isArrayOf(value.metrics, isMetric) &&
    isArrayOf(value.columns, isColumn) &&
    isArrayOf(value.rows, isRow) &&
    isArrayOf(value.actions, isAction) &&
    isArrayOf(value.notices, isNotice);
}

export function workforceExportAccessValidator(
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

    try {
      const url = new URL(value.downloadUrl);
      return (url.protocol === 'https:' || url.protocol === 'http:') && Date.parse(value.expiresAt) > Date.now();
    } catch {
      return false;
    }
  };
}
